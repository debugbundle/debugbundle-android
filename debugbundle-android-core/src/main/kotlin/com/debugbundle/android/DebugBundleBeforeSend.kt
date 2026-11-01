package com.debugbundle.android

import kotlinx.serialization.json.Json

fun interface DebugBundleBeforeSend {
    fun apply(event: DebugBundleEnvelope): DebugBundleEnvelope?
}

internal fun applyDebugBundleBeforeSend(event: DebugBundleEnvelope, hook: DebugBundleBeforeSend?): DebugBundleEnvelope? {
    if (hook == null) return event
    val input = try { DebugBundleOwnedEvent.copy(event) } catch (_: Throwable) { return event }
    val result = try { hook.apply(input) } catch (_: Throwable) { return event } ?: return null
    return try {
        val owned = DebugBundleOwnedEvent.copy(result)
        if (owned.isValidBeforeSendEvent()) owned else event
    } catch (_: EvidenceValidationUnavailable) { null }
    catch (_: Throwable) { event }
}

private val validationJson = Json { encodeDefaults = true; explicitNulls = true }
internal fun DebugBundleEnvelope.isValidBeforeSendEvent(): Boolean = DebugBundleEvidenceWire.accepts(validationJson.encodeToJsonElement(DebugBundleEnvelope.serializer(), this))
