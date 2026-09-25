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
) {
    private val reference = WeakReference(error)

    fun enrich(event: DebugBundleEnvelope): DebugBundleEnvelope = runCatching {
        val error = reference.get() ?: return event
        val details = redactor.sanitize(buildJsonObject {
            put("type", error.javaClass.name)
            put("message", error.message ?: error.javaClass.simpleName)
            put("stack_trace", JsonArray(error.stackTrace.take(50).map { JsonPrimitive(it.toString()) }))
        }) as JsonObject
        if (log) {
            val attributes = event.payload["attributes"] as? JsonObject ?: JsonObject(emptyMap())
            event.copy(payload = JsonObject(event.payload + ("attributes" to JsonObject(attributes + ("throwable" to details)))))
        } else {
            canonicalizeAndroidEnvelope(event.copy(payload = JsonObject(
                event.payload.filterKeys { it !in setOf("name", "message", "stack") } + ("error" to details),
            )), event.device ?: JsonObject(emptyMap()))
        }
    }.getOrDefault(event)

    internal fun clear() = reference.clear()
}
