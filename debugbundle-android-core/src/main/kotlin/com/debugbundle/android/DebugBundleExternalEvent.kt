package com.debugbundle.android

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun parseDebugBundleExternalEvent(event: JsonObject): DebugBundleEnvelope? {
    if (!EXTERNAL_EVENT_ROOT_KEYS.containsAll(event.keys)) {
        return null
    }
    val schemaVersion = event.string("schema_version") ?: return null
    if (schemaVersion !in EXTERNAL_SCHEMA_VERSIONS) {
        return null
    }
    val eventId = event.string("event_id") ?: return null
    if (runCatching { UUID.fromString(eventId) }.isFailure) {
        return null
    }
    val eventType = event.string("event_type") ?: return null
    if (eventType !in EXTERNAL_EVENT_TYPES) {
        return null
    }
    val sdkName = event.string("sdk_name") ?: return null
    if (sdkName != REACT_NATIVE_SDK_NAME) {
        return null
    }
    val sdkVersion = event.string("sdk_version")?.takeIf(String::isNotBlank) ?: return null
    val occurredAt = event.string("occurred_at") ?: return null
    if (runCatching { Instant.parse(occurredAt) }.isFailure) {
        return null
    }
    val service = event["service"] as? JsonObject ?: return null
    if (!EXTERNAL_SERVICE_KEYS.containsAll(service.keys)) {
        return null
    }
    val serviceName = service.string("name")?.takeIf(String::isNotBlank) ?: return null
    val environment = service.string("environment")?.takeIf(String::isNotBlank) ?: return null
    val payload = event["payload"] as? JsonObject ?: return null
    val correlation = event["correlation"] as? JsonObject

    return DebugBundleEnvelope(
        schemaVersion = "2026-03-01",
        eventId = eventId.lowercase(),
        eventType = eventType,
        sdkName = sdkName,
        sdkVersion = sdkVersion,
        service = DebugBundleServiceDescriptor(
            name = serviceName,
            environment = environment,
            runtime = service.string("runtime")?.takeIf(String::isNotBlank) ?: "react-native",
            framework = service.string("framework")?.takeIf(String::isNotBlank),
        ),
        occurredAt = occurredAt,
        correlation = correlation?.let {
            DebugBundleCorrelation(
                requestId = it.nullableString("request_id"),
                traceId = it.nullableString("trace_id"),
                sessionId = it.nullableString("session_id"),
                userIdHash = it.nullableString("user_id_hash"),
            )
        },
        payload = payload,
        device = event["device"] as? JsonObject,
        context = event["context"] as? JsonObject,
    )
}

private fun JsonObject.string(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.nullableString(key: String): String? {
    if (this[key] == null || this[key] is JsonNull) {
        return null
    }
    return string(key)
}

private val EXTERNAL_SCHEMA_VERSIONS = setOf("1", "2026-03-01")
internal fun parseExternalLogLevel(value: String): DebugBundleLogLevel {
    return when (value.lowercase()) {
        "debug" -> DebugBundleLogLevel.Debug
        "info" -> DebugBundleLogLevel.Info
        "error" -> DebugBundleLogLevel.Error
        "fatal" -> DebugBundleLogLevel.Fatal
        "critical" -> DebugBundleLogLevel.Critical
        else -> DebugBundleLogLevel.Warning
    }
}

internal fun shouldCaptureDebugBundleExternalEnvelope(
    config: DebugBundleConfig,
    policy: DebugBundleCapturePolicy,
    event: DebugBundleEnvelope,
): Boolean {
    return when (event.eventType) {
        DebugBundleEventTypes.LOG_EVENT -> {
            val level = (event.payload["level"] as? JsonPrimitive)
                ?.content
                ?.let(::parseExternalLogLevel)
                ?: DebugBundleLogLevel.Warning
            policy.capturesLog(level, config.captureLogs, config.logLevel)
        }

        DebugBundleEventTypes.REQUEST_EVENT -> {
            if (!config.captureNetwork) {
                false
            } else {
                val status = ((event.payload["response_status"] ?: event.payload["status_code"]) as? JsonPrimitive)
                    ?.content
                    ?.toIntOrNull()
                    ?: 0
                val path = ((event.payload["path"] ?: event.payload["url"]) as? JsonPrimitive)
                    ?.content
                    .orEmpty()
                val method = (event.payload["method"] as? JsonPrimitive)?.content.orEmpty()
                policy.capturesStandaloneRequestEvent(status, path, method)
            }
        }

        DebugBundleEventTypes.FRONTEND_BREADCRUMB ->
            policy.capturesStandaloneBreadcrumbs()

        DebugBundleEventTypes.PROBE_EVENT ->
            policy.capturesStandaloneProbeEvents()

        else -> true
    }
}

internal const val REACT_NATIVE_SDK_NAME = "@debugbundle/sdk-react-native"
internal val EXTERNAL_SESSION_EVENT_TYPES = setOf(
    DebugBundleEventTypes.FRONTEND_BREADCRUMB,
    DebugBundleEventTypes.LOG_EVENT,
    DebugBundleEventTypes.REQUEST_EVENT,
)
private val EXTERNAL_EVENT_TYPES = setOf(
    DebugBundleEventTypes.FRONTEND_EXCEPTION,
    DebugBundleEventTypes.FRONTEND_BREADCRUMB,
    DebugBundleEventTypes.LOG_EVENT,
    DebugBundleEventTypes.REQUEST_EVENT,
    DebugBundleEventTypes.ERROR_SUPPRESSED,
    DebugBundleEventTypes.PROBE_EVENT,
)
private val EXTERNAL_EVENT_ROOT_KEYS = setOf(
    "schema_version",
    "event_id",
    "event_type",
    "sdk_name",
    "sdk_version",
    "service",
    "occurred_at",
    "correlation",
    "context",
    "payload",
    "device",
)
private val EXTERNAL_SERVICE_KEYS = setOf("name", "environment", "runtime", "framework")
