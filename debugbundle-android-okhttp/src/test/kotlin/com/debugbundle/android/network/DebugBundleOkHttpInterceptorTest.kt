package com.debugbundle.android.network

import com.debugbundle.android.DebugBundleClient
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleOkHttpInterceptorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `matching target injects trace header and keeps network breadcrumb for later exception context`() {
        val transport = RecordingTransport()
        val client = newClient(transport, tempDir.resolve("matched-queue.json"))
        val interceptor = DebugBundleOkHttpInterceptor(
            tracePropagationTargets = listOf(DebugBundleTracePropagationTarget.host("api.example.com")),
            captureSink = client,
            traceIdFactory = { "trace-123" },
            nanoTimeProvider = sequenceClock(1_000_000L, 151_000_000L),
        )

        val chain = FakeChain(
            request = Request.Builder()
                .url("https://api.example.com/checkout")
                .build(),
            responseCode = 200,
        )

        interceptor.intercept(chain)
        client.captureException(IllegalStateException("boom"))
        client.flush()

        assertEquals("trace-123", chain.proceededRequest?.header(DebugBundleOkHttpInterceptor.TRACE_HEADER))
        assertEquals(1, transport.events.size)
        val breadcrumbs = transport.events.single().payload["breadcrumbs"] as JsonArray
        assertEquals(1, breadcrumbs.size)
        val breadcrumb = breadcrumbs.first() as JsonObject
        assertEquals("network_request", (breadcrumb["breadcrumb_type"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `non matching target leaves request untouched and records no breadcrumb`() {
        val transport = RecordingTransport()
        val client = newClient(transport, tempDir.resolve("unmatched-queue.json"))
        val interceptor = DebugBundleOkHttpInterceptor(
            tracePropagationTargets = listOf(DebugBundleTracePropagationTarget.host("api.example.com")),
            captureSink = client,
            traceIdFactory = { "trace-123" },
        )

        val chain = FakeChain(
            request = Request.Builder()
                .url("https://third-party.example.org/asset")
                .build(),
            responseCode = 200,
        )

        interceptor.intercept(chain)
        client.captureException(IllegalStateException("boom"))
        client.flush()

        assertNull(chain.proceededRequest?.header(DebugBundleOkHttpInterceptor.TRACE_HEADER))
        val breadcrumbs = transport.events.single().payload["breadcrumbs"] as JsonArray
        assertTrue(breadcrumbs.isEmpty())
        client.close()
    }

    @Test
    fun `existing trace header is preserved and five hundred responses emit request event`() {
        val transport = RecordingTransport()
        val client = newClient(transport, tempDir.resolve("existing-header-queue.json"))
        val interceptor = DebugBundleOkHttpInterceptor(
            tracePropagationTargets = listOf(DebugBundleTracePropagationTarget.hostSuffix("example.com")),
            captureSink = client,
            traceIdFactory = { "generated-trace" },
            nanoTimeProvider = sequenceClock(2_000_000L, 32_000_000L),
        )

        val chain = FakeChain(
            request = Request.Builder()
                .url("https://api.example.com/checkout")
                .header(DebugBundleOkHttpInterceptor.TRACE_HEADER, "caller-trace")
                .header("Authorization", "Bearer secret")
                .build(),
            responseCode = 503,
        )

        interceptor.intercept(chain)
        client.flush()

        assertEquals("caller-trace", chain.proceededRequest?.header(DebugBundleOkHttpInterceptor.TRACE_HEADER))
        assertEquals(listOf("request_event"), transport.events.map { it.eventType })
        assertEquals("caller-trace", transport.events.single().correlation?.traceId)
        val headers = transport.events.single().payload["headers"] as JsonObject
        assertEquals(setOf("x-debugbundle-trace-id"), headers.keys)
        client.close()
    }

    @Test
    fun `url prefix propagation target is exact and case sensitive`() {
        val target = DebugBundleTracePropagationTarget.urlPrefix("https://api.example.com/v2/")

        assertTrue(target.matches("https://api.example.com/v2/checkout", "api.example.com"))
        assertTrue(!target.matches("https://api.example.com/v1/checkout", "api.example.com"))
    }

    private fun newClient(transport: RecordingTransport, queuePath: Path): DebugBundleClient {
        return DebugBundleClient.create(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                flushInterval = 50.milliseconds,
                offlineQueuePath = queuePath,
            ),
            transport = transport,
            remoteConfigClient = balancedRemoteConfigClient(),
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }
    }

    private fun balancedRemoteConfigClient(): com.debugbundle.android.DebugBundleRemoteConfigClient {
        return com.debugbundle.android.DebugBundleRemoteConfigClient {
            com.debugbundle.android.DebugBundleRemoteConfigResult.Loaded(
                config = com.debugbundle.android.DebugBundleRemoteConfigResponse(
                    capturePolicy = com.debugbundle.android.DebugBundleRemoteCapturePolicy(
                        preset = "balanced",
                        captureLogs = "warning",
                        captureRequestEvents = "failures_only",
                        captureBreadcrumbs = "exception_only",
                        captureProbeEvents = "buffer_only",
                    ),
                ),
            )
        }
    }

    private fun sequenceClock(vararg ticks: Long): () -> Long {
        val iterator = ticks.iterator()
        val fallback = ticks.lastOrNull() ?: 0L
        var last = fallback
        return {
            if (iterator.hasNext()) {
                last = iterator.nextLong()
            }
            last
        }
    }

    private class FakeChain(
        private val request: Request,
        private val responseCode: Int,
    ) : Interceptor.Chain {
        var proceededRequest: Request? = null

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("test")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        override fun call(): Call {
            throw UnsupportedOperationException("Not used in tests")
        }

        override fun connectTimeoutMillis(): Int = 1_000

        override fun connection(): Connection? = null

        override fun readTimeoutMillis(): Int = 1_000

        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this

        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this

        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 1_000
    }
}
