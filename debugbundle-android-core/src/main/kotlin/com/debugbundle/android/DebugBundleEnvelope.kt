package com.debugbundle.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class DebugBundleEnvelope(
    @SerialName("schema_version")
    val schemaVersion: String,
    @SerialName("event_id")
    val eventId: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("sdk_name")
    val sdkName: String,
    @SerialName("sdk_version")
    val sdkVersion: String,
    val service: DebugBundleServiceDescriptor,
    @SerialName("occurred_at")
    val occurredAt: String,
    val correlation: DebugBundleCorrelation? = null,
    val payload: JsonObject,
    val device: JsonObject? = null,
)

@Serializable
data class DebugBundleServiceDescriptor(
    val name: String,
    val environment: String,
    val runtime: String = "kotlin",
    val framework: String? = null,
)

@Serializable
data class DebugBundleCorrelation(
    @SerialName("trace_id")
    val traceId: String? = null,
)

internal object DebugBundleEventTypes {
    const val FRONTEND_EXCEPTION = "frontend_exception"
    const val FRONTEND_BREADCRUMB = "frontend_breadcrumb"
    const val LOG_EVENT = "log_event"
    const val REQUEST_EVENT = "request_event"
    const val ERROR_SUPPRESSED = "error_suppressed"
    const val PROBE_EVENT = "probe_event"
}
