package com.debugbundle.android

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Converts both current in-memory events and events restored from the legacy
 * durable queue into the canonical ingestion contract before transport.
 */
internal fun canonicalizeAndroidEnvelope(
    event: DebugBundleEnvelope,
    currentDevice: JsonObject,
): DebugBundleEnvelope {
    val legacyDevice = event.device
    val payloadDevice = event.payload["device"] as? JsonObject
    val canonicalDevice = canonicalizeAndroidDevice(payloadDevice ?: legacyDevice ?: currentDevice)
    val rawPayload = event.payload
    val canonicalPayload = when (event.eventType) {
        DebugBundleEventTypes.FRONTEND_EXCEPTION -> canonicalExceptionPayload(rawPayload, canonicalDevice)
        DebugBundleEventTypes.LOG_EVENT -> canonicalLogPayload(rawPayload, canonicalDevice)
        DebugBundleEventTypes.REQUEST_EVENT -> canonicalRequestPayload(rawPayload, canonicalDevice)
        DebugBundleEventTypes.FRONTEND_BREADCRUMB -> canonicalBreadcrumbPayload(rawPayload, canonicalDevice)
        DebugBundleEventTypes.PROBE_EVENT -> canonicalProbePayload(rawPayload, canonicalDevice)
        DebugBundleEventTypes.ERROR_SUPPRESSED -> canonicalSuppressionPayload(rawPayload, canonicalDevice)
        else -> rawPayload
    }
    val envelopeContext = event.context ?: (rawPayload["context"] as? JsonObject)
    return event.copy(
        payload = canonicalPayload,
        device = legacyDevice ?: currentDevice,
        context = envelopeContext,
    )
}

private fun canonicalExceptionPayload(payload: JsonObject, device: JsonObject): JsonObject {
    val legacyError = payload["error"] as? JsonObject
    val name = payload.string("name")
        ?: legacyError?.string("name")
        ?: legacyError?.string("type")
        ?: "Error"
    val message = payload.string("message")
        ?.takeIf(String::isNotBlank)
        ?: legacyError?.string("message")?.takeIf(String::isNotBlank)
        ?: name
    val stack = payload.string("stack")
        ?.takeIf(String::isNotBlank)
        ?: legacyError?.string("stack")?.takeIf(String::isNotBlank)
        ?: legacyError?.stackString()
        ?: "$name: $message"

    return buildJsonObject {
        put("name", name)
        put("message", message)
        put("stack", stack)
        payload["route"]?.let { put("route", it) }
        payload["breadcrumbs"]?.let { put("breadcrumbs", it) }
        put("device", device)
        payload["probe_data"]?.let { put("probe_data", canonicalizeInlineProbeData(it)) }
    }
}

private fun canonicalLogPayload(payload: JsonObject, device: JsonObject): JsonObject {
    val attributes = LinkedHashMap<String, JsonElement>()
    (payload["attributes"] as? JsonObject)?.let(attributes::putAll)
    (payload["context"] as? JsonObject)?.let(attributes::putAll)
    listOf("logged_at", "thread_name", "logger", "tag", "coroutine_name", "throwable").forEach { key ->
        payload[key]?.let { attributes[key] = it }
    }
    return buildJsonObject {
        put("level", payload["level"] ?: JsonPrimitive("warning"))
        put("message", payload["message"] ?: JsonPrimitive("Log event"))
        put("attributes", JsonObject(attributes))
        put("device", device)
    }
}

private fun canonicalRequestPayload(payload: JsonObject, device: JsonObject): JsonObject {
    val url = payload.string("url")
    val parsedUri = url?.let { runCatching { URI(it) }.getOrNull() }
    val path = payload.string("path")
        ?: parsedUri?.rawPath?.takeIf(String::isNotBlank)
        ?: url?.takeIf(String::isNotBlank)
        ?: "/"
    val query = payload["query"] as? JsonObject ?: parseQuery(parsedUri?.rawQuery)
    val durationMillis = payload.nonNegativeLong("duration_ms")

    return buildJsonObject {
        put("method", payload["method"] ?: JsonPrimitive("UNKNOWN"))
        put("path", path)
        put("query", query)
        put("headers", payload["headers"] as? JsonObject ?: JsonObject(emptyMap()))
        payload["body"]?.let { put("body", it) }
        put("response_status", payload["response_status"] ?: JsonPrimitive(0))
        put("duration_ms", durationMillis)
        payload["route_template"]?.let { put("route_template", it) }
        put("response_headers", payload["response_headers"] as? JsonObject ?: JsonObject(emptyMap()))
        payload["response_body"]?.let { put("response_body", it) }
        put("device", device)
    }
}

private fun canonicalBreadcrumbPayload(payload: JsonObject, device: JsonObject): JsonObject {
    return buildJsonObject {
        put("breadcrumb_type", payload["breadcrumb_type"] ?: JsonPrimitive("custom"))
        payload["route"]?.let { put("route", it) }
        put("data", payload["data"] as? JsonObject ?: JsonObject(emptyMap()))
        put("device", device)
    }
}

private fun canonicalProbePayload(payload: JsonObject, device: JsonObject): JsonObject {
    val rawData = payload["data"]
    val data = rawData as? JsonObject ?: buildJsonObject {
        put("value", rawData ?: JsonNull)
    }
    return buildJsonObject {
        put("label", payload["label"] ?: JsonPrimitive("unknown"))
        put("data", data)
        put("activation_id", payload["activation_id"] ?: JsonNull)
        put("probe_label_pattern", payload["probe_label_pattern"] ?: JsonPrimitive("*"))
        put("device", device)
    }
}

private fun canonicalSuppressionPayload(payload: JsonObject, device: JsonObject): JsonObject {
    return buildJsonObject {
        put("fingerprint", payload["fingerprint"] ?: JsonPrimitive("unknown"))
        put("suppressed_count", payload["suppressed_count"] ?: JsonPrimitive(0))
        put("window_seconds", payload["window_seconds"] ?: JsonPrimitive(1))
        put("first_seen", payload["first_seen"] ?: JsonPrimitive("1970-01-01T00:00:00Z"))
        put("last_seen", payload["last_seen"] ?: JsonPrimitive("1970-01-01T00:00:00Z"))
        put("device", device)
    }
}

private fun canonicalizeAndroidDevice(raw: JsonObject): JsonObject {
    val canonicalOs = raw["os"] as? JsonObject
    val screenWidth = raw.nonNegativeInt("screen_width")
        ?: (raw["screen"] as? JsonObject)?.nonNegativeInt("width")
        ?: 0
    val screenHeight = raw.nonNegativeInt("screen_height")
        ?: (raw["screen"] as? JsonObject)?.nonNegativeInt("height")
        ?: 0
    val viewport = raw["viewport"] as? JsonObject
    val rawDeviceType = raw.string("device_type")
    val deviceType = rawDeviceType?.takeIf { it in CANONICAL_DEVICE_TYPES } ?: "unknown"

    return buildJsonObject {
        put("user_agent", raw["user_agent"] ?: JsonNull)
        put(
            "os",
            buildJsonObject {
                put("name", canonicalOs?.get("name") ?: raw["os_name"] ?: JsonNull)
                put("version", canonicalOs?.get("version") ?: raw["os_version"] ?: JsonNull)
            },
        )
        put("device_type", deviceType)
        put(
            "screen",
            buildJsonObject {
                put("width", screenWidth)
                put("height", screenHeight)
            },
        )
        put(
            "viewport",
            buildJsonObject {
                put("width", viewport?.nonNegativeInt("width") ?: screenWidth)
                put("height", viewport?.nonNegativeInt("height") ?: screenHeight)
            },
        )
        put("device_pixel_ratio", raw["device_pixel_ratio"] ?: JsonNull)
        put("touch_capable", raw["touch_capable"] ?: JsonPrimitive(true))
        put("language", raw["language"] ?: raw["locale"] ?: JsonNull)
        put("connection_type", raw["connection_type"] ?: JsonNull)
        put("color_scheme_preference", raw["color_scheme_preference"] ?: JsonNull)
        put("app_version", raw["app_version"] ?: JsonNull)
        put("build_number", raw["build_number"] ?: JsonNull)
        put("release_channel", raw["release_channel"] ?: JsonNull)
        put("api_level", raw.nonNegativeElement("api_level"))
        put("manufacturer", raw["manufacturer"] ?: JsonNull)
        put("model", raw["model"] ?: JsonNull)
        put("timezone", raw["timezone"] ?: JsonNull)
        put("battery_level", raw.nonNegativeElement("battery_level"))
        put("battery_charging", raw["battery_charging"] ?: JsonNull)
        put("free_disk_bytes", raw.nonNegativeElement("free_disk_bytes"))
        put("free_memory_bytes", raw.nonNegativeElement("free_memory_bytes"))
        put("jailbroken", raw["jailbroken"] ?: raw["rooted"] ?: JsonNull)
    }
}

private fun parseQuery(rawQuery: String?): JsonObject {
    if (rawQuery.isNullOrBlank()) {
        return JsonObject(emptyMap())
    }
    val values = LinkedHashMap<String, MutableList<String>>()
    rawQuery.split("&").forEach { part ->
        val segments = part.split("=", limit = 2)
        val key = decodeQuerySegment(segments[0])
        val value = decodeQuerySegment(segments.getOrElse(1) { "" })
        values.getOrPut(key) { mutableListOf() }.add(value)
    }
    return JsonObject(
        values.mapValues { (_, entries) ->
            if (entries.size == 1) JsonPrimitive(entries.single()) else JsonArray(entries.map(::JsonPrimitive))
        },
    )
}

private fun canonicalizeInlineProbeData(value: JsonElement): JsonElement {
    val container = value as? JsonObject ?: return buildJsonObject {
        put("version", 1)
        put(
            "items",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("label", "default")
                        put("data", buildJsonObject { put("value", value) })
                        put("timestamp", "1970-01-01T00:00:00Z")
                        put("activation_id", JsonNull)
                    },
                ),
            ),
        )
    }
    val items = container["items"] as? JsonArray ?: return container
    return JsonObject(
        container + (
            "items" to JsonArray(
                items.map { item ->
                    val itemObject = item as? JsonObject ?: return@map item
                    val data = itemObject["data"]
                    if (data is JsonObject) {
                        itemObject
                    } else {
                        JsonObject(
                            itemObject + (
                                "data" to buildJsonObject {
                                    put("value", data ?: JsonNull)
                                }
                            ),
                        )
                    }
                },
            )
        ),
    )
}

private fun decodeQuerySegment(value: String): String {
    return runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)
}

private fun JsonObject.string(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.nonNegativeInt(key: String): Int? {
    return (this[key] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0)
}

private fun JsonObject.nonNegativeLong(key: String): Long {
    return (this[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0
}

private fun JsonObject.nonNegativeElement(key: String): JsonElement {
    val primitive = this[key] as? JsonPrimitive ?: return JsonNull
    val number = primitive.content.toDoubleOrNull() ?: return JsonNull
    return JsonPrimitive(number.coerceAtLeast(0.0))
}

private fun JsonObject.stackString(): String? {
    val stackTrace = this["stack_trace"] as? JsonArray ?: return null
    return stackTrace
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .joinToString("\n")
        .takeIf(String::isNotBlank)
}

private val CANONICAL_DEVICE_TYPES = setOf("desktop", "mobile", "tablet", "unknown")
