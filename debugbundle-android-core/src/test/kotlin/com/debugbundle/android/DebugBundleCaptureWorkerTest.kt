package com.debugbundle.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleCaptureWorkerTest {
    @Test
    fun `held initialization bounds concurrent admission and preserves exceptions over ordinary logs`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadScheduledExecutor()
        val callers = Executors.newFixedThreadPool(4)
        val stored = mutableListOf<DebugBundleEnvelope>()
        val worker = worker(executor, initialize = { entered.countDown(); release.await() }, persist = { stored.addAll(it) })
        try {
            worker.request()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val jobs = (1..4).map { caller -> callers.submit {
                repeat(100) { worker.enqueue(event(caller * 1000 + it), true) }
            } }
            jobs.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(256, worker.ownership().first)
            assertFalse(worker.mightAdmit(0))
            assertTrue(worker.mightAdmit(3))
            assertTrue(worker.enqueue(event(9999, DebugBundleEventTypes.FRONTEND_EXCEPTION), true))
            assertEquals(256, worker.ownership().first)
            assertTrue(worker.ownership().second <= DebugBundleCaptureWorker.MAX_BYTES)
            assertEquals(146L, worker.drainPressure()["info"])
            assertTrue(worker.drainPressure().isEmpty())
            release.countDown()
            worker.flushAndWait(3.seconds)
            assertEquals(256, stored.size)
            assertTrue(stored.any { it.eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION })
            assertEquals(0 to 0L, worker.ownership())
        } finally { release.countDown(); worker.close(1.seconds); executor.shutdownNow(); callers.shutdownNow() }
    }

    @Test
    fun `held hook and expanded replacements share staging bytes with remaining batch`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadScheduledExecutor()
        val calls = AtomicInteger()
        val retained = mutableListOf<DebugBundleEnvelope>()
        var ownedAtPersistence = 0L
        lateinit var worker: DebugBundleCaptureWorker
        worker = worker(executor, prepare = { original, _ ->
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await() }
            original.copy(payload = buildJsonObject { put("message", "x".repeat(200_000)); put("level", "error") })
        }, persist = {
            ownedAtPersistence = maxOf(ownedAtPersistence, worker.ownership().second)
            retained.addAll(it)
        })
        try {
            worker.enqueue(event(1), true)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(255) { worker.enqueue(event(it + 2), true) }
            assertEquals(256, worker.ownership().first)
            worker.flushAndWait(20.milliseconds)
            assertEquals(256, worker.ownership().first)
            release.countDown()
            worker.flushAndWait(3.seconds)
            assertTrue(ownedAtPersistence <= DebugBundleCaptureWorker.MAX_BYTES)
            assertTrue(retained.size in 1..12)
            assertEquals(0 to 0L, worker.ownership())
            assertTrue(worker.drainPressure().values.sum() > 0)
        } finally { release.countDown(); worker.close(1.seconds); executor.shutdownNow() }
    }

    @Test
    fun `failed persistence retries final hook output once and keeps ownership until durable`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val hooks = AtomicInteger()
        val writes = AtomicInteger()
        val callbacks = AtomicInteger()
        val failures = AtomicInteger()
        val done = CountDownLatch(1)
        val stored = mutableListOf<DebugBundleEnvelope>()
        val worker = worker(executor, prepare = { original, _ ->
            hooks.incrementAndGet()
            original.copy(eventId = "replacement")
        }, persist = {
            if (writes.incrementAndGet() == 1) error("disk held")
            stored.addAll(it)
            done.countDown()
        }, failure = { failures.incrementAndGet() })
        try {
            assertTrue(worker.enqueue(event(1), true) { callbacks.incrementAndGet() })
            worker.flushAndWait(2.seconds)
            assertEquals(1, worker.ownership().first)
            assertTrue(done.await(3, TimeUnit.SECONDS))
            worker.flushAndWait(2.seconds)
            assertEquals(1, hooks.get())
            assertEquals(listOf("replacement"), stored.map { it.eventId })
            assertEquals(1, callbacks.get())
            assertEquals(1, failures.get())
            assertEquals(0 to 0L, worker.ownership())
        } finally { worker.close(1.seconds); executor.shutdownNow() }
    }

    @Test
    fun `expired queued and active hook entries are released without persistence`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val now = AtomicLong(100)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stored = mutableListOf<DebugBundleEnvelope>()
        val worker = worker(executor, now = now::get, prepare = { original, _ ->
            started.countDown(); release.await(); original
        }, persist = { stored.addAll(it) })
        try {
            worker.enqueue(event(1), true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.enqueue(event(2), true)
            now.set(100_000)
            release.countDown()
            worker.flushAndWait(2.seconds)
            assertTrue(stored.isEmpty())
            assertEquals(0 to 0L, worker.ownership())
        } finally { release.countDown(); worker.close(1.seconds); executor.shutdownNow() }
    }

    @Test
    fun `hook rejection exception oversized output and close cannot strand ownership`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val failures = AtomicInteger()
        val stored = mutableListOf<DebugBundleEnvelope>()
        val worker = worker(executor, prepare = { original, _ -> when (original.eventId) {
            "1" -> null
            "2" -> error("hook failure")
            "3" -> original.copy(payload = buildJsonObject { put("message", "x".repeat(2_100_000)) })
            else -> original
        } }, persist = { stored.addAll(it) }, failure = { failures.incrementAndGet() })
        try {
            for (id in 1..4) worker.enqueue(event(id), true)
            worker.flushAndWait(3.seconds)
            assertEquals(listOf("4"), stored.map { it.eventId })
            assertEquals(1, failures.get())
            assertEquals(0 to 0L, worker.ownership())
            worker.close(1.seconds)
            assertFalse(worker.mightAdmit(3))
            assertFalse(worker.enqueue(event(5), true))
            worker.request(true)
            worker.flushAndWait(1.seconds)
        } finally { worker.close(1.seconds); executor.shutdownNow() }
    }

    @Test
    fun `initialization failures and rejected executor wakeups fail safely`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val starts = AtomicInteger()
        val failures = AtomicInteger()
        val stored = mutableListOf<DebugBundleEnvelope>()
        val done = CountDownLatch(1)
        val worker = worker(executor, initialize = { if (starts.incrementAndGet() == 1) error("disk unavailable") },
            persist = { stored.addAll(it); done.countDown() }, failure = { failures.incrementAndGet() })
        try {
            worker.enqueue(event(1), true)
            assertTrue(done.await(3, TimeUnit.SECONDS))
            worker.flushAndWait(1.seconds)
            assertEquals(1, stored.size)
            assertEquals(1, failures.get())
            executor.shutdownNow()
            worker.request()
            assertTrue(failures.get() >= 2)
        } finally { worker.close(20.milliseconds); executor.shutdownNow() }
    }

    @Test
    fun `post-persistence notification failure does not duplicate a durable event`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val writes = AtomicInteger()
        val failures = AtomicInteger()
        val worker = worker(executor, persist = { writes.incrementAndGet() }, failure = { failures.incrementAndGet() })
        try {
            worker.enqueue(event(1), true) { error("fatal record could not be cleared") }
            worker.flushAndWait(2.seconds)
            worker.flushAndWait(2.seconds)
            assertEquals(1, writes.get())
            assertEquals(1, failures.get())
            assertEquals(0 to 0L, worker.ownership())
        } finally { worker.close(1.seconds); executor.shutdownNow() }
    }

    private fun worker(
        executor: java.util.concurrent.ScheduledExecutorService,
        now: () -> Long = { 1000L },
        initialize: () -> Unit = {},
        prepare: (DebugBundleEnvelope, Boolean) -> DebugBundleEnvelope? = { event, _ -> event },
        persist: (List<DebugBundleEnvelope>) -> Unit = {},
        failure: () -> Unit = {},
    ) = DebugBundleCaptureWorker(executor, now, 10_000, initialize, {}, prepare,
        { events -> persist(events); events.mapTo(hashSetOf()) { it.eventId } }, { false }, {}, failure)

    private fun event(id: Int, type: String = DebugBundleEventTypes.LOG_EVENT) = DebugBundleEnvelope(
        schemaVersion = "2026-03-01", eventId = id.toString(), eventType = type,
        sdkName = "@debugbundle/sdk-android", sdkVersion = "2.0.0", service = DebugBundleServiceDescriptor("test", "test"),
        occurredAt = "2026-09-25T00:00:00Z", payload = buildJsonObject {
            put("message", "event-$id"); put("level", "warning")
        },
    )
}
