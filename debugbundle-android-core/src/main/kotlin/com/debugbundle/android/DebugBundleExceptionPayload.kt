package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

private val exceptionJson = Json { encodeDefaults = true; explicitNulls = true }
internal fun buildDebugBundleExceptionPayload(error: Throwable, context: Map<String, Any?>, redactor: DebugBundleRedactor,
    breadcrumbs: List<DebugBundleBreadcrumb>, probeData: JsonObject?): JsonObject = buildJsonObject {
    put("error", redactor.sanitize(error)); put("context", redactor.sanitize(context))
    put("breadcrumbs", exceptionJson.encodeToJsonElement(ListSerializer(DebugBundleBreadcrumb.serializer()), breadcrumbs))
    probeData?.let { put("probe_data", it) }
}

/** Uses owned OS report text, never treating a constructed exception as a native ANR throw site. */
internal fun enrichDebugBundleReportedException(event: DebugBundleEnvelope, redactor: DebugBundleRedactor): DebugBundleEnvelope {
    val description = (event.context?.get("description") as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
    val primary = if (description == null) event else event.copy(payload = JsonObject(event.payload + ("message" to JsonPrimitive(description))))
    val nativeTrace = (event.context?.get("anr_trace_excerpt") as? JsonPrimitive)?.takeIf { it.isString && it.content != "[REDACTED]" }?.content
    val captureTrace = (primary.payload["stack"] as? JsonPrimitive)?.content.orEmpty()
    val supplied = (nativeTrace ?: captureTrace).take(4096).lineSequence().take(40).map(String::trim)
        .filter { it.startsWith("at ") || '(' in it && ')' in it }.take(20).toList()
    val record = com.debugbundle.android.crash.DebugBundleFatalCrashRecord(
        occurredAt = primary.occurredAt, threadName = "", errorType = (primary.payload["name"] as? JsonPrimitive)?.content ?: "ApplicationNotRespondingException",
        errorMessage = (primary.payload["message"] as? JsonPrimitive)?.content.orEmpty(), stackTrace = supplied,
        traceOrigin = if (nativeTrace == null) "capture" else "runtime_reported")
    val graph = DebugBundleFatalEvidence.capture(record, redactor) ?: return primary
    return primary.copy(payload = JsonObject(primary.payload + ("exception_evidence" to graph)))
}
