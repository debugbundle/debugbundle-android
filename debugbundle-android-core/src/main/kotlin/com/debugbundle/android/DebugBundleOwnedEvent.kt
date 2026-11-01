package com.debugbundle.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded independent JSON result ownership; no application Throwable/object projection here. */
internal object DebugBundleOwnedEvent {
    private class Budget(var nodes: Int = 4096, var bytes: Int = 8 * 1024 * 1024)
    fun copy(event: DebugBundleEnvelope): DebugBundleEnvelope {
        val budget = Budget()
        for (value in listOfNotNull(event.schemaVersion, event.eventId, event.eventType, event.sdkName, event.sdkVersion, event.occurredAt, event.service.name, event.service.environment, event.service.runtime, event.service.framework, event.correlation?.requestId, event.correlation?.traceId, event.correlation?.sessionId, event.correlation?.userIdHash)) use(value, budget)
        return event.copy(payload = copy(event.payload, budget, 0) as JsonObject, context = event.context?.let { copy(it, budget, 0) as JsonObject }, device = event.device?.let { copy(it, budget, 0) as JsonObject }, service = event.service.copy().also { it.runtimeIsAbsent = event.service.runtimeIsAbsent }, correlation = event.correlation?.copy())
    }
    private fun use(value: String, budget: Budget) {
        if (value.length > budget.bytes) throw EvidenceValidationUnavailable()
        budget.bytes -= value.toByteArray(Charsets.UTF_8).size
        if (budget.bytes < 0) throw EvidenceValidationUnavailable()
    }
    private fun copy(value: JsonElement, budget: Budget, depth: Int): JsonElement {
        if (--budget.nodes < 0 || budget.bytes < 0 || depth > 16) throw EvidenceValidationUnavailable()
        return when (value) {
            JsonNull -> JsonNull
            is JsonObject -> {
                if (value.size > budget.nodes / 2) throw EvidenceValidationUnavailable()
                JsonObject(linkedMapOf<String, JsonElement>().apply { for ((key, item) in value) { budget.nodes--; use(key, budget); put(key, copy(item, budget, depth + 1)) } })
            }
            is JsonArray -> {
                if (value.size > budget.nodes) throw EvidenceValidationUnavailable()
                JsonArray(value.map { copy(it, budget, depth + 1) })
            }
            is JsonPrimitive -> {
                use(value.content, budget)
                value // Native JSON primitive values are immutable.
            }
        }
    }
}
