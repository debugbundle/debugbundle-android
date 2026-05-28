package com.debugbundle.android.network

import com.debugbundle.android.DebugBundleClient
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.testkit.RecordingTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleKtorClientPluginTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `matching target injects trace header and keeps network breadcrumb for later exception context`() = runBlocking {
        val transport = RecordingTransport()
        val client = newClient(transport, tempDir.resolve("ktor-matched-queue.json"))
        var seenTraceHeader: String? = null
        val httpClient = HttpClient(
            MockEngine { request ->
                seenTraceHeader = request.headers[TRACE_HEADER]
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                )
            },
        ) {
            install(DebugBundleKtorClientPlugin) {
                tracePropagationTargets = listOf(DebugBundleTracePropagationTarget.host("api.example.com"))
                captureSink = client
                traceIdFactory = { "trace-123" }
                nanoTimeProvider = sequenceClock(1_000_000L, 151_000_000L)
            }
        }

        try {
            httpClient.get("https://api.example.com/checkout")
            client.captureException(IllegalStateException("boom"))
            client.flush()

            assertEquals("trace-123", seenTraceHeader)
            assertEquals(1, transport.events.size)
            val breadcrumbs = transport.events.single().payload["breadcrumbs"] as JsonArray
            assertEquals(1, breadcrumbs.size)
            val breadcrumb = breadcrumbs.first() as JsonObject
            assertEquals("network_request", (breadcrumb["breadcrumb_type"] as JsonPrimitive).content)
        } finally {
            httpClient.close()
            client.close()
        }
    }

    @Test
    fun `non matching target leaves request untouched and records no breadcrumb`() = runBlocking {
        val transport = RecordingTransport()
        val client = newClient(transport, tempDir.resolve("ktor-unmatched-queue.json"))
        var seenTraceHeader: String? = null
        val httpClient = HttpClient(
            MockEngine { request ->
                seenTraceHeader = request.headers[TRACE_HEADER]
                respond(
                    content = "{}",
                    status = HttpStatusCode.OK,
                )
            },
        ) {
            install(DebugBundleKtorClientPlugin) {
                tracePropagationTargets = listOf(DebugBundleTracePropagationTarget.host("api.example.com"))
                captureSink = client
                traceIdFactory = { "trace-123" }
            }
        }

        try {
            httpClient.get("https://third-party.example.org/asset")
            client.captureException(IllegalStateException("boom"))
            client.flush()

            assertNull(seenTraceHeader)
            val breadcrumbs = transport.events.single().payload["breadcrumbs"] as JsonArray
            assertTrue(breadcrumbs.isEmpty())
        } finally {
            httpClient.close()
            client.close()
        }
    }

    @Test
    fun `existing trace header is preserved and five hundred responses emit request event`() = runBlocking {
        val transport = RecordingTransport()
        val client = newClient(transport, tempDir.resolve("ktor-existing-header-queue.json"))
        var seenTraceHeader: String? = null
        val httpClient = HttpClient(
            MockEngine { request ->
                seenTraceHeader = request.headers[TRACE_HEADER]
                respond(
                    content = "{}",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.SetCookie to listOf("session=secret"),
                    ),
                )
            },
        ) {
            install(DebugBundleKtorClientPlugin) {
                tracePropagationTargets = listOf(DebugBundleTracePropagationTarget.hostSuffix("example.com"))
                captureSink = client
                traceIdFactory = { "generated-trace" }
                nanoTimeProvider = sequenceClock(2_000_000L, 32_000_000L)
            }
        }

        try {
            httpClient.get("https://api.example.com/checkout") {
                headers.append(TRACE_HEADER, "caller-trace")
                headers.append(HttpHeaders.Authorization, "Bearer secret")
            }
            client.flush()

            assertEquals("caller-trace", seenTraceHeader)
            assertEquals(listOf("request_event"), transport.events.map { it.eventType })
            assertEquals("caller-trace", transport.events.single().correlation?.traceId)
            val requestHeaders = transport.events.single().payload["headers"] as JsonObject
            assertEquals(setOf("x-debugbundle-trace-id", "accept"), requestHeaders.keys)
            val responseHeaders = transport.events.single().payload["response_headers"] as JsonObject
            assertEquals(setOf("content-type"), responseHeaders.keys)
        } finally {
            httpClient.close()
            client.close()
        }
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

    companion object {
        private const val TRACE_HEADER: String = "X-DebugBundle-Trace-Id"
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
}
