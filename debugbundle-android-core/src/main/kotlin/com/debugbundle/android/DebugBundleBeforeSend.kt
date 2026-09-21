package com.debugbundle.android

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

fun interface DebugBundleBeforeSend {
    fun apply(event: DebugBundleEnvelope): DebugBundleEnvelope?
}

internal fun applyDebugBundleBeforeSend(
    event: DebugBundleEnvelope,
    hook: DebugBundleBeforeSend?,
): DebugBundleEnvelope? {
    if (hook == null) {
        return event
    }
    return try {
        val result = hook.apply(
            event.copy(
                payload = JsonObject(event.payload.toMap()),
                context = event.context?.toMap()?.let(::JsonObject),
            ),
        ) ?: return null
        if (result.isValidBeforeSendEvent()) result else event
    } catch (_: Throwable) {
        event
    }
}

internal fun DebugBundleEnvelope.isValidBeforeSendEvent(): Boolean {
    if (
        schemaVersion.isBlank() ||
        eventType.isBlank() ||
        sdkName.isBlank() ||
        sdkVersion.isBlank() ||
        service.name.isBlank() ||
        service.environment.isBlank()
    ) {
        return false
    }
    if (runCatching { UUID.fromString(eventId) }.isFailure || runCatching { Instant.parse(occurredAt) }.isFailure) {
        return false
    }
    val requiredFields = REQUIRED_PAYLOAD_FIELDS[eventType] ?: return false
    val allowedFields = ALLOWED_PAYLOAD_FIELDS[eventType] ?: return false
    return requiredFields.all(payload::containsKey) &&
        payload.keys.all(allowedFields::contains) &&
        hasValidPayloadShape()
}

private fun DebugBundleEnvelope.hasValidPayloadShape(): Boolean {
    return when (eventType) {
        DebugBundleEventTypes.FRONTEND_EXCEPTION -> {
            payload.hasNonBlankStrings("name", "message", "stack") &&
                payload.optionalArray("breadcrumbs") &&
                payload.optionalObject("probe_data")
        }
        DebugBundleEventTypes.FRONTEND_BREADCRUMB -> {
            payload.hasNonBlankStrings("breadcrumb_type") && payload["data"] is JsonObject
        }
        DebugBundleEventTypes.LOG_EVENT -> {
            payload.hasNonBlankStrings("level", "message") && payload["attributes"] is JsonObject
        }
        DebugBundleEventTypes.REQUEST_EVENT -> {
            payload.hasNonBlankStrings("method", "path") &&
                payload["query"] is JsonObject &&
                payload["headers"] is JsonObject &&
                payload.isNonNegativeNumber("response_status") &&
                payload.isNonNegativeNumber("duration_ms") &&
                payload.optionalObject("response_headers")
        }
        DebugBundleEventTypes.ERROR_SUPPRESSED -> {
            payload.hasNonBlankStrings("fingerprint") &&
                payload.isNonNegativeInteger("suppressed_count") &&
                payload.isPositiveInteger("window_seconds") &&
                payload.isTimestamp("first_seen") &&
                payload.isTimestamp("last_seen")
        }
        DebugBundleEventTypes.PROBE_EVENT -> {
            payload.hasNonBlankStrings("label", "probe_label_pattern") &&
                payload["data"] is JsonObject &&
                payload.isNullableUuid("activation_id")
        }
        else -> false
    }
}

private fun JsonObject.hasNonBlankStrings(vararg fields: String): Boolean {
    return fields.all { field ->
        (this[field] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.isNotBlank() == true
    }
}

private fun JsonObject.optionalObject(field: String): Boolean {
    return field !in this || this[field] is JsonObject
}

private fun JsonObject.optionalArray(field: String): Boolean {
    return field !in this || this[field] is JsonArray
}

private fun JsonObject.isNonNegativeNumber(field: String): Boolean {
    val value = (this[field] as? JsonPrimitive)?.doubleOrNull
    return value != null && value.isFinite() && value >= 0
}

private fun JsonObject.isNonNegativeInteger(field: String): Boolean {
    val value = (this[field] as? JsonPrimitive)?.doubleOrNull
    return value != null && value.isFinite() && value >= 0 && value % 1.0 == 0.0
}

private fun JsonObject.isPositiveInteger(field: String): Boolean {
    val value = (this[field] as? JsonPrimitive)?.doubleOrNull
    return value != null && value.isFinite() && value > 0 && value % 1.0 == 0.0
}

private fun JsonObject.isTimestamp(field: String): Boolean {
    val value = (this[field] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: return false
    return runCatching { Instant.parse(value) }.isSuccess
}

private fun JsonObject.isNullableUuid(field: String): Boolean {
    val value = this[field]
    if (value == JsonNull) {
        return true
    }
    val uuid = (value as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: return false
    return runCatching { UUID.fromString(uuid) }.isSuccess
}

private val REQUIRED_PAYLOAD_FIELDS = mapOf(
    DebugBundleEventTypes.FRONTEND_EXCEPTION to setOf("name", "message", "stack"),
    DebugBundleEventTypes.FRONTEND_BREADCRUMB to setOf("breadcrumb_type", "data"),
    DebugBundleEventTypes.LOG_EVENT to setOf("level", "message", "attributes"),
    DebugBundleEventTypes.REQUEST_EVENT to setOf(
        "method",
        "path",
        "query",
        "headers",
        "response_status",
        "duration_ms",
    ),
    DebugBundleEventTypes.ERROR_SUPPRESSED to setOf(
        "fingerprint",
        "suppressed_count",
        "window_seconds",
        "first_seen",
        "last_seen",
    ),
    DebugBundleEventTypes.PROBE_EVENT to setOf("label", "data", "activation_id", "probe_label_pattern"),
)

private val ALLOWED_PAYLOAD_FIELDS = mapOf(
    DebugBundleEventTypes.FRONTEND_EXCEPTION to setOf(
        "name",
        "message",
        "stack",
        "route",
        "browser",
        "breadcrumbs",
        "device",
        "browser_event",
        "rejection_reason",
        "dom_context",
        "probe_data",
    ),
    DebugBundleEventTypes.FRONTEND_BREADCRUMB to setOf("breadcrumb_type", "route", "data", "device"),
    DebugBundleEventTypes.LOG_EVENT to setOf("level", "message", "attributes", "device"),
    DebugBundleEventTypes.REQUEST_EVENT to setOf(
        "method",
        "path",
        "query",
        "headers",
        "body",
        "response_status",
        "duration_ms",
        "route_template",
        "response_headers",
        "response_body",
        "device",
    ),
    DebugBundleEventTypes.ERROR_SUPPRESSED to setOf(
        "fingerprint",
        "suppressed_count",
        "window_seconds",
        "first_seen",
        "last_seen",
        "device",
    ),
    DebugBundleEventTypes.PROBE_EVENT to setOf(
        "label",
        "data",
        "activation_id",
        "probe_label_pattern",
        "device",
    ),
)
