package com.debugbundle.android

import com.debugbundle.android.crash.DebugBundleFatalCrashRecord
import com.debugbundle.android.crash.DebugBundleFatalCrashStore
import com.debugbundle.android.crash.DebugBundleUncaughtExceptionHandler
import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleFatalCrashHandlerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `uncaught exception handler persists bounded crash data and delegates to previous handler`() {
        val crashStore = RecordingFatalCrashStore()
        val delegated = AtomicBoolean(false)
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegated.set(true) }
        val handler = DebugBundleUncaughtExceptionHandler(
            crashStore = crashStore,
            previous = previous,
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
        )

        handler.uncaughtException(
            Thread.currentThread(),
            IllegalStateException("x".repeat(1024)),
        )

        val record = crashStore.record
        assertEquals("2026-05-28T10:15:30Z", record?.occurredAt)
        assertEquals(512, record?.errorMessage?.length)
        assertTrue((record?.stackTrace?.size ?: 0) > 0)
        assertTrue(delegated.get())
    }

    @Test
    fun `pending fatal crash is replayed once on next launch and then cleared`() {
        withTemporaryDefaultHandler(Thread.UncaughtExceptionHandler { _, _ -> }) {
            val queuePath = tempDir.resolve("queue.json")
            val crashPath = tempDir.resolve("fatal-crash.json")
            val firstClient = DebugBundleClient.create(
                config = DebugBundleConfig(
                    projectToken = "token",
                    service = "checkout-android",
                    flushInterval = 50.milliseconds,
                    offlineQueuePath = queuePath,
                    fatalCrashPath = crashPath,
                ),
                transport = RecordingTransport(),
                remoteConfigClient = balancedRemoteConfigClient(),
                clock = { Instant.parse("2026-05-28T10:15:30Z") },
                random = { 0.0 },
                executor = Executors.newSingleThreadScheduledExecutor(),
            )

            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
                Thread.currentThread(),
                IllegalStateException("fatal boom"),
            )
            firstClient.close()

            val secondTransport = RecordingTransport()
            val secondClient = DebugBundleClient.create(
                config = DebugBundleConfig(
                    projectToken = "token",
                    service = "checkout-android",
                    flushInterval = 50.milliseconds,
                    offlineQueuePath = queuePath,
                    fatalCrashPath = crashPath,
                ),
                transport = secondTransport,
                remoteConfigClient = balancedRemoteConfigClient(),
                clock = { Instant.parse("2026-05-28T10:16:30Z") },
                random = { 0.0 },
                executor = Executors.newSingleThreadScheduledExecutor(),
            )

            secondClient.flush()

            assertEquals(listOf("frontend_exception"), secondTransport.events.map { it.eventType })
            val payload = secondTransport.events.single().payload
            val error = payload["error"] as JsonObject
            assertEquals("fatal boom", (error["message"] as JsonPrimitive).content)
            val context = payload["context"] as JsonObject
            assertEquals("uncaught_exception_handler", (context["mechanism"] as JsonPrimitive).content)
            assertEquals("next_launch", (context["crash_delivery"] as JsonPrimitive).content)
            val breadcrumbs = payload["breadcrumbs"] as JsonArray
            assertTrue(breadcrumbs.isEmpty())
            secondClient.close()

            val thirdTransport = RecordingTransport()
            val thirdClient = DebugBundleClient.create(
                config = DebugBundleConfig(
                    projectToken = "token",
                    service = "checkout-android",
                    flushInterval = 50.milliseconds,
                    offlineQueuePath = queuePath,
                    fatalCrashPath = crashPath,
                ),
                transport = thirdTransport,
                remoteConfigClient = balancedRemoteConfigClient(),
                clock = { Instant.parse("2026-05-28T10:17:30Z") },
                random = { 0.0 },
                executor = Executors.newSingleThreadScheduledExecutor(),
            )

            thirdClient.flush()
            assertTrue(thirdTransport.events.isEmpty())
            thirdClient.close()
        }
    }

    @Test
    fun `fatal capture can be disabled`() {
        withTemporaryDefaultHandler(Thread.UncaughtExceptionHandler { _, _ -> }) {
            val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            val client = DebugBundleClient.create(
                config = DebugBundleConfig(
                    projectToken = "token",
                    service = "checkout-android",
                    flushInterval = 50.milliseconds,
                    offlineQueuePath = tempDir.resolve("disabled-queue.json"),
                    fatalCrashPath = tempDir.resolve("disabled-crash.json"),
                    captureFatalExceptions = false,
                ),
                transport = RecordingTransport(),
                remoteConfigClient = balancedRemoteConfigClient(),
                clock = { Instant.parse("2026-05-28T10:15:30Z") },
                random = { 0.0 },
                executor = Executors.newSingleThreadScheduledExecutor(),
            )

            assertEquals(originalHandler, Thread.getDefaultUncaughtExceptionHandler())
            client.close()
        }
    }

    @Test
    fun `blank token does not install fatal handler`() {
        withTemporaryDefaultHandler(Thread.UncaughtExceptionHandler { _, _ -> }) {
            val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
            val client = DebugBundleClient.create(
                config = DebugBundleConfig(
                    projectToken = "",
                    service = "checkout-android",
                    flushInterval = 50.milliseconds,
                    offlineQueuePath = tempDir.resolve("blank-token-queue.json"),
                    fatalCrashPath = tempDir.resolve("blank-token-crash.json"),
                ),
                transport = RecordingTransport(),
                remoteConfigClient = balancedRemoteConfigClient(),
                clock = { Instant.parse("2026-05-28T10:15:30Z") },
                random = { 0.0 },
                executor = Executors.newSingleThreadScheduledExecutor(),
            )

            assertEquals(originalHandler, Thread.getDefaultUncaughtExceptionHandler())
            client.close()
        }
    }

    private fun withTemporaryDefaultHandler(
        handler: Thread.UncaughtExceptionHandler,
        block: () -> Unit,
    ) {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler)
        try {
            block()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    private class RecordingFatalCrashStore : DebugBundleFatalCrashStore {
        var record: DebugBundleFatalCrashRecord? = null

        override fun load(): DebugBundleFatalCrashRecord? = record

        override fun persist(record: DebugBundleFatalCrashRecord) {
            this.record = record
        }

        override fun clear() {
            record = null
        }
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
