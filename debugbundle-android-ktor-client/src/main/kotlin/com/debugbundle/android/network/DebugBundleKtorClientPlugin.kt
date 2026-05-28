package com.debugbundle.android.network

import com.debugbundle.android.DebugBundle
import com.debugbundle.android.DebugBundleCaptureSink
import com.debugbundle.android.DebugBundleRequestInfo
import com.debugbundle.android.DebugBundleResponseInfo
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.util.AttributeKey
import java.util.UUID

class DebugBundleKtorClientPluginConfig {
    var tracePropagationTargets: List<DebugBundleTracePropagationTarget> = emptyList()
    var captureSink: DebugBundleCaptureSink = DebugBundle
    var traceIdFactory: () -> String = { UUID.randomUUID().toString() }
    var nanoTimeProvider: () -> Long = System::nanoTime
}

val DebugBundleKtorClientPlugin = createClientPlugin(
    name = "DebugBundleKtorClientPlugin",
    createConfiguration = ::DebugBundleKtorClientPluginConfig,
) {
    val tracePropagationTargets = pluginConfig.tracePropagationTargets
    val captureSink = pluginConfig.captureSink
    val traceIdFactory = pluginConfig.traceIdFactory
    val nanoTimeProvider = pluginConfig.nanoTimeProvider
    val stateKey = AttributeKey<InstrumentedRequestState>("debugbundle-ktor-request-state")

    on(SendingRequest) { request, _ ->
        if (!shouldInstrument(request, tracePropagationTargets)) {
            return@on
        }
        request.headers[PROBE_TRIGGER_HEADER]?.let(captureSink::activateProbeTriggerToken)

        val existingTraceId = request.headers[TRACE_HEADER]
        val effectiveTraceId = existingTraceId ?: traceIdFactory()
        if (existingTraceId == null) {
            request.headers.append(TRACE_HEADER, effectiveTraceId)
        }
        request.attributes.put(
            stateKey,
            InstrumentedRequestState(
                traceId = effectiveTraceId,
                startedAtNanos = nanoTimeProvider(),
            ),
        )
    }

    onResponse { response ->
        val state = runCatching { response.call.attributes[stateKey] }.getOrNull() ?: return@onResponse
        val durationMillis = ((nanoTimeProvider() - state.startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

        captureSink.recordRequest(
            request = response.toDebugBundleRequestInfo(state.traceId),
            response = response.toDebugBundleResponseInfo(durationMillis),
            context = emptyMap(),
            recordBreadcrumb = true,
        )
    }
}

private data class InstrumentedRequestState(
    val traceId: String,
    val startedAtNanos: Long,
)

private fun shouldInstrument(
    request: HttpRequestBuilder,
    tracePropagationTargets: List<DebugBundleTracePropagationTarget>,
): Boolean {
    if (tracePropagationTargets.isEmpty()) {
        return false
    }
    val url = request.url.buildString()
    val host = request.url.host
    return tracePropagationTargets.any { it.matches(url, host) }
}

private fun normalizedRoute(response: HttpResponse): String {
    return response.call.request.url.encodedPath.takeIf { it.isNotBlank() } ?: response.call.request.url.toString()
}

private fun HttpResponse.toDebugBundleRequestInfo(traceId: String): DebugBundleRequestInfo {
    return DebugBundleRequestInfo(
        method = call.request.method.value,
        url = call.request.url.toString(),
        headers = call.request.headers.toFlatMap(),
        routeTemplate = normalizedRoute(this),
        traceId = traceId,
    )
}

private fun HttpResponse.toDebugBundleResponseInfo(durationMillis: Long): DebugBundleResponseInfo {
    return DebugBundleResponseInfo(
        statusCode = status.value,
        durationMillis = durationMillis,
        headers = headers.toFlatMap(),
    )
}

private fun io.ktor.http.Headers.toFlatMap(): Map<String, String> {
    return entries().associate { (name, values) -> name to values.joinToString(",") }
}

private const val TRACE_HEADER: String = "X-DebugBundle-Trace-Id"
private const val PROBE_TRIGGER_HEADER: String = "X-DebugBundle-Probe-Trigger"
private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
