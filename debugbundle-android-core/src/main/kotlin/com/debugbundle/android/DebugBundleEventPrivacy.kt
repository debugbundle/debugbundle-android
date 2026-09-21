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
        ),
        correlation = event.correlation?.copy(
            requestId = event.correlation.requestId?.let(::safeText),
            traceId = event.correlation.traceId?.let(::safeText),
            sessionId = event.correlation.sessionId?.let(::safeText),
            userIdHash = event.correlation.userIdHash?.let(::safeText),
        ),
    )
}

