package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun protectDebugBundleEnvelope(event: DebugBundleEnvelope, redactor: DebugBundleRedactor): DebugBundleEnvelope? {
    val identities = listOfNotNull(event.schemaVersion, event.sdkName, event.sdkVersion,
        event.correlation?.requestId, event.correlation?.traceId,
        event.correlation?.sessionId, event.correlation?.userIdHash)
    if (identities.any { (redactor.sanitize(it) as? JsonPrimitive)?.content != it }) return null
    val payload = redactor.sanitize(event.payload) as? JsonObject ?: return null
    val context = event.context?.let { redactor.sanitize(it) as? JsonObject ?: return null }
    val device = event.device?.let { redactor.sanitize(it) as? JsonObject ?: return null }
    fun safeText(value: String): String = (redactor.sanitize(value) as JsonPrimitive).content
    return event.copy(
        payload = payload,
        context = context,
        device = device,
        service = event.service.copy(
            name = safeText(event.service.name),
            environment = safeText(event.service.environment),
            runtime = safeText(event.service.runtime),
            framework = event.service.framework?.let(::safeText),
        ).also { it.runtimeIsAbsent = event.service.runtimeIsAbsent },
        correlation = event.correlation?.copy(
            requestId = event.correlation.requestId?.let(::safeText),
            traceId = event.correlation.traceId?.let(::safeText),
            sessionId = event.correlation.sessionId?.let(::safeText),
            userIdHash = event.correlation.userIdHash?.let(::safeText),
        ),
    )
}


/** Revalidate privacy and reduce optional data on the latest accepted primary only. */
internal fun protectDebugBundleEvidenceEnvelope(event: DebugBundleEnvelope, redactor: DebugBundleRedactor): DebugBundleEnvelope? {
    fun protect(candidate: DebugBundleEnvelope): DebugBundleEnvelope? = runCatching {
        protectDebugBundleEnvelope(candidate, redactor)?.takeIf(DebugBundleEnvelope::isValidBeforeSendEvent)
    }.getOrNull()
    protect(event)?.let { return it }
    for (mark in listOf(true, false)) withoutDebugBundleOptionalEvidence(event, mark)?.let { reduced -> protect(reduced)?.let { return it } }
    return null
}

internal fun withoutDebugBundleOptionalEvidence(event: DebugBundleEnvelope, mark: Boolean): DebugBundleEnvelope? {
    if (event.eventType !in setOf("backend_exception", DebugBundleEventTypes.FRONTEND_EXCEPTION)) return null
    val payload = event.payload.toMutableMap(); val limits = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("version" to JsonPrimitive(1))
    for (field in listOf("exception_evidence", "execution", "resource_observations")) if (payload.remove(field) != null) limits[field] = JsonPrimitive("budget_exceeded")
    if (limits.size == 1) return null
    val context = event.context?.toMutableMap() ?: linkedMapOf()
    if (DebugBundleEvidenceWire.reference(context["evidence_reference"])) context.remove("evidence_reference")
    if (mark) context["evidence_limits"] = JsonObject(limits)
    return event.copy(payload = JsonObject(payload), context = JsonObject(context))
}
