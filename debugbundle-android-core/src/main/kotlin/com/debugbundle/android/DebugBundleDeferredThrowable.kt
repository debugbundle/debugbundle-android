package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import java.lang.ref.WeakReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Non-owning attachment; only the existing worker may inspect application Throwable methods/monitors. */
internal class DebugBundleDeferredThrowable(
    error: Throwable,
    private val redactor: DebugBundleRedactor,
    private val log: Boolean = false,
    private val canCaptureEvidence: () -> Boolean = { false },
    private val reported: Boolean = false,
) {
    private val reference = WeakReference(error)

    fun enrich(event: DebugBundleEnvelope): DebugBundleEnvelope = runCatching {
        val error = reference.get() ?: return if (reported && canCaptureEvidence()) enrichDebugBundleReportedException(event, redactor) else event
        val message = error.message ?: error.javaClass.simpleName
        val frames = error.stackTrace
        val details = redactor.sanitize(buildJsonObject {
            put("type", error.javaClass.name)
            put("message", message)
            put("stack_trace", JsonArray(frames.take(50).map { JsonPrimitive(it.toString()) }))
        }) as JsonObject
        if (log) {
            val attributes = event.payload["attributes"] as? JsonObject ?: JsonObject(emptyMap())
            event.copy(payload = JsonObject(event.payload + ("attributes" to JsonObject(attributes + ("throwable" to details)))))
        } else {
            val canonical = canonicalizeAndroidEnvelope(event.copy(payload = JsonObject(
                event.payload.filterKeys { it !in setOf("name", "message", "stack") } + ("error" to details),
            )), event.device ?: JsonObject(emptyMap()))
            if (reported && canCaptureEvidence()) return enrichDebugBundleReportedException(canonical, redactor)
            val graph = if (canCaptureEvidence()) DebugBundleExceptionEvidence.capture(error, message, frames, redactor) else null
            if (graph == null) canonical else canonical.copy(payload = JsonObject(canonical.payload + ("exception_evidence" to graph)))
        }
    }.also { reference.clear() }.getOrDefault(event)

    internal fun clear() = reference.clear()
}
