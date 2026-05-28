package com.debugbundle.android

import kotlin.time.Duration

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
) {
    val isSuccess: Boolean
        get() = statusCode in 200..299

    val shouldRetry: Boolean
        get() = statusCode == 429 || statusCode >= 500
}

fun interface DebugBundleTransport {
    fun send(request: DebugBundleTransportRequest): DebugBundleTransportResult
}
