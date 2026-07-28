package com.debugbundle.android

import kotlin.time.Duration
import kotlinx.serialization.Serializable

data class DebugBundleTransportRequest(
    val projectToken: String,
    val endpoint: String,
    val events: List<DebugBundleEnvelope>,
    val timeout: Duration,
)

data class DebugBundleTransportResult(
    val statusCode: Int,
    val retryAfter: Duration = Duration.ZERO,
    val probeDirectives: List<DebugBundleRemoteProbeDirective>? = null,
    /**
     * Optional for source compatibility with existing custom transports. The
     * built-in HTTP transport always requires and parses this acknowledgement.
     */
    val acknowledgement: DebugBundleIngestionAcknowledgement? = null,
    val acknowledgementRequired: Boolean = false,
) {
    val isSuccess: Boolean
        get() = statusCode in 200..299

    val shouldRetry: Boolean
        get() = statusCode == 429 || statusCode >= 500
}

@Serializable
data class DebugBundleIngestionError(
    val index: Int,
    val reason: String,
)

@Serializable
data class DebugBundleIngestionAcknowledgement(
    val accepted: Int,
    val rejected: Int,
    val errors: List<DebugBundleIngestionError> = emptyList(),
)

fun interface DebugBundleTransport {
    fun send(request: DebugBundleTransportRequest): DebugBundleTransportResult
}
