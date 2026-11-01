package com.debugbundle.android

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Local permission and actual reader proof are reapplied to every delivery attempt. */
internal class DebugBundleEvidenceController(options: DebugBundleEvidenceOptions,
    val operations: DebugBundleOperations = DebugBundleOperations()) {
    private val options = options.snapshot()
    private val proof = DebugBundleEvidenceDeliveryState(this.options.evidenceCapabilities)
    val budget = DebugBundleDiagnosticBudget()
    fun canCaptureExceptions(): Boolean = options.captureExceptionEvidence && proof.supports("exception_evidence_v1")
    fun canCaptureRecovery(diagnosticsAllowed: Boolean): Boolean = diagnosticsAllowed && options.captureRecoveryObservations && proof.supports("recovery_v1")
    fun canHold(event: DebugBundleEnvelope): Boolean = event.eventType == "diagnostic_context" && budget.eligible(event) &&
        ((event.payload["kind"] as? JsonPrimitive)?.content != "recovery" || options.captureRecoveryObservations)
    fun capabilities(diagnosticsAllowed: Boolean): DebugBundleEvidenceCapabilities = DebugBundleEvidenceCapabilities(features = proof.descriptor().features.filter {
        when (it) { "exception_evidence_v1" -> canCaptureExceptions(); "recovery_v1" -> canCaptureRecovery(diagnosticsAllowed); "resource_observations_v1" -> diagnosticsAllowed; else -> true }
    })
    fun descriptor(): DebugBundleEvidenceCapabilities = proof.descriptor()
    fun observe(value: JsonElement?): Boolean = proof.observe(value)
    fun context(merged: Map<String, Any?>): Map<String, Any?> {
        if (!proof.supports("recovery_v1") && !proof.supports("resource_observations_v1")) return merged
        val failure = merged["failure_ref"] as? String ?: return merged
        val scope = merged["evidence_scope"] as? JsonObject ?: operations.current()?.wire() ?: return merged
        val reference = JsonObject(mapOf("version" to JsonPrimitive(1), "failure_ref" to JsonPrimitive(failure), "scope" to scope))
        if (!DebugBundleEvidenceWire.reference(reference)) return merged
        return merged.filterKeys { it !in setOf("failure_ref", "evidence_scope") } + ("evidence_reference" to reference)
    }
    fun recoveryPayload(observation: DebugBundleRecoveryObservation): JsonObject? {
        val scope = observation.scope?.wire() ?: operations.current()?.wire() ?: return null
        val values = linkedMapOf<String, JsonElement>("version" to JsonPrimitive(1), "kind" to JsonPrimitive("recovery"),
            "failure_ref" to JsonPrimitive(observation.failureRef), "scope" to scope,
            "attempt_id" to JsonPrimitive(observation.attemptId), "state" to JsonPrimitive(observation.state))
        observation.operation?.let { values["operation"] = JsonPrimitive(it) }
        observation.statusCode?.let { values["status_code"] = JsonPrimitive(it) }
        observation.durationMs?.let { values["duration_ms"] = JsonPrimitive(it) }
        return JsonObject(values)
    }
    fun filter(event: DebugBundleEnvelope, diagnosticsAllowed: Boolean, owned: Boolean = true): DebugBundleEnvelope? {
        if (event.eventType == "diagnostic_context") {
            val kind = (event.payload["kind"] as? JsonPrimitive)?.content
            val enabled = if (kind == "recovery") canCaptureRecovery(diagnosticsAllowed)
                else kind == "resource_observation" && diagnosticsAllowed && proof.supports("resource_observations_v1")
            return event.takeIf { enabled && (!owned || budget.eligible(event)) }
        }
        val remove = mutableSetOf<String>()
        if (!canCaptureExceptions()) { remove.add("exception_evidence"); remove.add("execution") }
        if (!diagnosticsAllowed || !proof.supports("resource_observations_v1")) remove.add("resource_observations")
        val removeReference = (!diagnosticsAllowed || !(options.captureRecoveryObservations && proof.supports("recovery_v1")) && !proof.supports("resource_observations_v1")) && DebugBundleEvidenceWire.reference(event.context?.get("evidence_reference"))
        if (remove.none(event.payload::containsKey) && !removeReference) return event
        val context = if (removeReference) JsonObject(event.context!!.filterKeys { it != "evidence_reference" }) else event.context
        return event.copy(payload = JsonObject(event.payload.filterKeys { it !in remove }), context = context)
    }
    fun reconcile(original: DebugBundleEnvelope, replacement: DebugBundleEnvelope?, diagnosticsAllowed: Boolean): DebugBundleEnvelope? {
        val originalId = original.eventId.takeIf { original.eventType == "diagnostic_context" }
        val filtered = replacement?.let { filter(it, diagnosticsAllowed, owned = false) }
        if (filtered?.eventType == "diagnostic_context") {
            if (!budget.replace(originalId, filtered)) { budget.release(originalId); return null }
        } else budget.release(originalId)
        return filtered
    }
    fun prepared(event: DebugBundleEnvelope) {
        if (event.eventType in setOf("frontend_exception", "backend_exception")) budget.register(event)
    }
    fun close() { operations.close(); budget.clear() }
}
