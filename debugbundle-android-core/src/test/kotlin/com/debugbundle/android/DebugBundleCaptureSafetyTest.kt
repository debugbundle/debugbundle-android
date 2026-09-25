package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class DebugBundleCaptureSafetyTest {
    @Test
    fun `blocked startup store does not hold construction or capture`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backing = InMemoryDebugBundleQueueStore()
        val store = object : DebugBundleQueueStore by backing {
            override fun snapshot(nowMillis: Long, limits: DebugBundleQueueLimits): List<QueuedDebugBundleEvent> {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return backing.snapshot(nowMillis, limits)
            }
        }
        val started = System.nanoTime()
        val client = newClient(store)
        try {
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val captured = System.nanoTime()
            repeat(20) { client.captureMessage("while store is held $it", DebugBundleLogLevel.Error) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - captured) < 2000)
        } finally {
            release.countDown()
            client.flush()
            client.close()
        }
    }

    @Test
    fun `held accepted hook does not hold capture or create parallel workers`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = RecordingTransport()
        val client = newClient(InMemoryDebugBundleQueueStore(), transport, DebugBundleBeforeSend { event ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            event
        })
        try {
            val captured = System.nanoTime()
            client.captureException(IllegalStateException("first"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - captured) < 2000)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val later = System.nanoTime()
            repeat(20) { client.captureException(IllegalStateException("later $it")) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - later) < 500)
            val flushed = System.nanoTime()
            client.flush(50.milliseconds)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flushed) < 500)
        } finally {
            release.countDown()
            client.flush()
            client.close()
        }
    }

    @Test
    fun `held throwable getter cannot hold public exception capture`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val error = object : RuntimeException() {
            override val message: String get() { entered.countDown(); release.await(); return "inspected" }
        }
        val executor = Executors.newSingleThreadExecutor()
        val client = newClient(InMemoryDebugBundleQueueStore())
        try {
            executor.submit { client.captureException(error) }.get(2, TimeUnit.SECONDS)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            executor.submit { client.captureException(IllegalStateException("other")) }.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); client.flush(); client.close(); executor.shutdownNow() }
    }

    @Test
    fun `held ordinary throwable monitor cannot hold public exception capture`() {
        val error = IllegalStateException("ordinary")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(error) { held.countDown(); release.await() } }.apply { start() }
        val caller = Executors.newSingleThreadExecutor()
        val client = newClient(InMemoryDebugBundleQueueStore())
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS))
            caller.submit { client.captureException(error) }.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); holder.join(2000); client.flush(); client.close(); caller.shutdownNow() }
    }

    @Test
    fun `held device provider does not hold construction capture or explicit finite flush`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        var client: DebugBundleClient? = null
        try {
            client = caller.submit<DebugBundleClient> { DebugBundleClient.create(
                config = DebugBundleConfig(projectToken = "token", captureFatalExceptions = false),
                transport = RecordingTransport(),
                deviceContextProvider = DebugBundleDeviceContextProvider {
                    entered.countDown(); release.await(); DebugBundleDeviceContext(model = "worker-model")
                },
                remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
                executor = Executors.newSingleThreadScheduledExecutor(),
            ) }.get(2, TimeUnit.SECONDS)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val active = client
            caller.submit { active.captureException(IllegalStateException("device held")); active.flush(20.milliseconds) }
                .get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); client?.flush(); client?.close(); caller.shutdownNow() }
    }

    @Test
    fun `background hook drops do not consume session allowance and replacements precede suppression`() {
        val transport = RecordingTransport()
        val client = DebugBundleClient.create(
            config = DebugBundleConfig(projectToken = "token", captureFatalExceptions = false, maxEventsPerSession = 1)
                .withBeforeSend(DebugBundleBeforeSend { event ->
                    if ((event.payload["message"] as? JsonPrimitive)?.content == "drop") null else event
                }),
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
            executor = Executors.newSingleThreadScheduledExecutor(),
        )
        try {
            client.captureMessage("drop", DebugBundleLogLevel.Error)
            client.flush()
            client.captureMessage("keep", DebugBundleLogLevel.Error)
            client.flush()
            assertEquals(listOf("keep"), transport.events.map { (it.payload["message"] as JsonPrimitive).content })
        } finally { client.close() }
    }

    private fun newClient(
        store: DebugBundleQueueStore,
        transport: RecordingTransport = RecordingTransport(),
        hook: DebugBundleBeforeSend? = null,
    ) = DebugBundleClient.create(
        config = DebugBundleConfig(projectToken = "token", captureFatalExceptions = false).withBeforeSend(hook),
        transport = transport,
        queueStore = store,
        remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
        clock = { Instant.parse("2026-09-25T00:00:00Z") },
        random = { 0.0 },
        executor = Executors.newSingleThreadScheduledExecutor(),
    )
}
