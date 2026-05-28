package com.debugbundle.android.network

import com.debugbundle.android.DebugBundle
import com.debugbundle.android.DebugBundleCaptureSink
import com.debugbundle.android.DebugBundleRequestInfo
import com.debugbundle.android.DebugBundleResponseInfo
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class DebugBundleOkHttpInterceptor @JvmOverloads constructor(
    private val tracePropagationTargets: List<DebugBundleTracePropagationTarget> = emptyList(),
    private val captureSink: DebugBundleCaptureSink = DebugBundle,
    private val traceIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val nanoTimeProvider: () -> Long = System::nanoTime,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!shouldInstrument(request)) {
            return chain.proceed(request)
        }
        request.header(PROBE_TRIGGER_HEADER)?.let(captureSink::activateProbeTriggerToken)

        val existingTraceId = request.header(TRACE_HEADER)
        val effectiveTraceId = existingTraceId ?: traceIdFactory()
        val instrumentedRequest = if (existingTraceId == null) {
            request.newBuilder()
                .header(TRACE_HEADER, effectiveTraceId)
                .build()
        } else {
            request
        }

        val startedAt = nanoTimeProvider()
        val response = chain.proceed(instrumentedRequest)
        val durationMillis = ((nanoTimeProvider() - startedAt).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

        captureSink.recordRequest(
            request = instrumentedRequest.toDebugBundleRequestInfo(effectiveTraceId),
            response = response.toDebugBundleResponseInfo(durationMillis),
            context = emptyMap(),
            recordBreadcrumb = true,
        )
        return response
    }

    private fun shouldInstrument(request: Request): Boolean {
        if (tracePropagationTargets.isEmpty()) {
            return false
        }
        val url = request.url.toString()
        val host = request.url.host
        return tracePropagationTargets.any { it.matches(url, host) }
    }

    private fun normalizedRoute(request: Request): String {
        return request.url.encodedPath.takeIf { it.isNotBlank() } ?: request.url.toString()
    }

    private fun Request.toDebugBundleRequestInfo(traceId: String): DebugBundleRequestInfo {
        return DebugBundleRequestInfo(
            method = method,
            url = url.toString(),
            headers = headers.toFlatMap(),
            routeTemplate = normalizedRoute(this),
            traceId = traceId,
        )
    }

    private fun Response.toDebugBundleResponseInfo(durationMillis: Long): DebugBundleResponseInfo {
        return DebugBundleResponseInfo(
            statusCode = code,
            durationMillis = durationMillis,
            headers = headers.toFlatMap(),
        )
    }

    private fun okhttp3.Headers.toFlatMap(): Map<String, String> {
        return names().associateWith { name -> values(name).joinToString(",") }
    }

    companion object {
        const val TRACE_HEADER: String = "X-DebugBundle-Trace-Id"
        const val PROBE_TRIGGER_HEADER: String = "X-DebugBundle-Probe-Trigger"

        private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    }
}
