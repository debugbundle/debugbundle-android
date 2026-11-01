package com.debugbundle.android

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** State is explicit: request completion and HTTP success do not establish recovery. */
data class DebugBundleRecoveryObservation @JvmOverloads constructor(
    val failureRef: String,
    val attemptId: String,
    val state: String,
    val scope: DebugBundleEvidenceScope? = null,
    val operation: String? = null,
    val statusCode: Int? = null,
    val durationMs: Double? = null,
    val traceId: String? = null,
    val sessionId: String? = null,
)

object DebugBundleEvidenceContext {
    @JvmStatic @JvmOverloads
    fun forFailure(failureRef: String, scope: DebugBundleEvidenceScope? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("failure_ref" to failureRef).apply { scope?.wire()?.let { put("evidence_scope", it) } }
}
