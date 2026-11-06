package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleLoggingTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `logged throwable becomes one primary exception rather than a generic log`() {
        val transport = RecordingTransport()
        val client = newClient(transport, DebugBundleConfig(projectToken = "synthetic", service = "android",
            offlineQueuePath = tempDir.resolve("exception-log.json")))
        val error = IllegalStateException("synthetic outer", NullPointerException("synthetic cause"))
        try {
            client.captureLog("operation failed", DebugBundleLogLevel.Error, mapOf("throwable" to error, "logger" to "timber"))
            client.flush()
            assertEquals(1, transport.events.size)
            assertEquals("frontend_exception", transport.events.single().eventType)
            assertEquals("synthetic outer", (transport.events.single().payload["message"] as JsonPrimitive).content)
            client.captureLog("warning context", DebugBundleLogLevel.Warning, mapOf("throwable" to error))
            client.flush()
            assertEquals("log_event", transport.events.last().eventType)
        } finally { client.close() }
    }

    @Test
    fun `manual log capture respects configured minimum log level`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                flushInterval = 50.milliseconds,
                offlineQueuePath = tempDir.resolve("log-level-queue.json"),
                logLevel = DebugBundleLogLevel.Error,
            ),
        )

        client.captureLog("debug ignored", DebugBundleLogLevel.Debug)
        client.captureLog("warning ignored", DebugBundleLogLevel.Warning)
        client.captureLog("error kept", DebugBundleLogLevel.Error, mapOf("logger" to "manual"))
        client.flush()

        assertEquals(listOf("error kept"), transport.events.map { (it.payload["message"] as JsonPrimitive).content })
        val payload = transport.events.single().payload
        val attributes = payload["attributes"] as JsonObject
        assertEquals("manual", (attributes["logger"] as JsonPrimitive).content)
        assertTrue(attributes["thread_name"] is JsonPrimitive)
        client.close()
    }

    @Test
    fun `coroutine exception handler captures coroutine metadata`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                flushInterval = 50.milliseconds,
                offlineQueuePath = tempDir.resolve("coroutine-queue.json"),
            ),
        )

        val throwable = IllegalStateException("coroutine boom")
        runBlocking(CoroutineName("checkout-refresh")) {
            client.coroutineExceptionHandler(mapOf("screen" to "Checkout"))
                .handleException(coroutineContext, throwable)
        }
        client.flush()

        val context = transport.events.single().context as JsonObject
        assertEquals("Checkout", (context["screen"] as JsonPrimitive).content)
        assertEquals("coroutine_exception_handler", (context["mechanism"] as JsonPrimitive).content)
        assertEquals("checkout-refresh", (context["coroutine_name"] as JsonPrimitive).content)
        client.close()
    }

    private fun newClient(
        transport: RecordingTransport,
        config: DebugBundleConfig,
    ): DebugBundleClient {
        return DebugBundleClient.create(
            config = config,
            transport = transport,
            remoteConfigClient = balancedRemoteConfigClient(),
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }
    }

    private fun balancedRemoteConfigClient(): DebugBundleRemoteConfigClient {
        return DebugBundleRemoteConfigClient {
            DebugBundleRemoteConfigResult.Loaded(
                config = DebugBundleRemoteConfigResponse(
                    capturePolicy = DebugBundleRemoteCapturePolicy(
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
