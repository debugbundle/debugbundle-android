package com.debugbundle.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** One bounded privacy-safe staging queue; callbacks, disk and transport run only on its worker. */
internal class DebugBundleCaptureWorker(
    private val executor: ScheduledExecutorService,
    private val nowMillis: () -> Long,
    private val ttlMillis: Long,
    private val initialize: () -> Unit,
    private val beforeDrain: () -> Unit,
    private val prepare: (DebugBundleEnvelope, Boolean) -> DebugBundleEnvelope?,
    private val persist: (List<DebugBundleEnvelope>) -> Set<String>,
    private val shouldFlush: () -> Boolean,
    private val flush: () -> Unit,
    private val failure: () -> Unit,
    private val onPrepared: (DebugBundleEnvelope) -> Unit = {},
) {
    internal data class Entry(
        val envelope: DebugBundleEnvelope,
        val runHook: Boolean,
        val bytes: Int,
        val priority: Int,
        val queuedAt: Long,
        val persisted: (() -> Unit)? = null,
        val details: DebugBundleDeferredThrowable? = null,
        val prepared: Boolean = false,
    )
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val lock = ReentrantLock()
    private val pending = ArrayDeque<Entry>()
    private val pressure = LongArray(4)
    private var ownedCount = 0
    private var ownedBytes = 0L
    private var scheduled = false
    private var initialized = false
    private var flushRequested = false
    private var closing = false
    private var closed = false
    private var completion = CountDownLatch(0)
    @Volatile private var workerThread: Thread? = null

    fun mightAdmit(priority: Int): Boolean {
        lock.lock()
        return try {
            if (closing || closed) false
            else if (ownedCount < MAX_EVENTS && ownedBytes < MAX_BYTES || pending.any { it.priority < priority }) true
            else { countDrop(priority); false }
        } finally { lock.unlock() }
    }

    fun enqueue(envelope: DebugBundleEnvelope, runHook: Boolean, details: DebugBundleDeferredThrowable? = null, persisted: (() -> Unit)? = null): Boolean {
        val entry = Entry(envelope, runHook, size(envelope), priority(envelope), nowMillis(), persisted, details)
        lock.lock()
        return try {
            if (closing || closed || !reserve(entry)) false
            else {
                pending.addLast(entry)
                requestLocked(false)
                true
            }
        } finally { lock.unlock() }
    }

    fun request(flush: Boolean = false) { lock.withLock { if (!closed) requestLocked(flush) } }

    fun flushAndWait(timeout: Duration) {
        val waitFor = lock.withLock {
            if (closed) return
            requestLocked(true)
            completion
        }
        if (Thread.currentThread() === workerThread) return
        try { waitFor.await(timeout.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    fun close(timeout: Duration) {
        lock.withLock { closing = true }
        flushAndWait(timeout)
        lock.withLock { closed = true; pending.forEach(::release); pending.clear() }
    }

    fun drainPressure(): Map<String, Long> = lock.withLock {
        buildMap {
            pressure.forEachIndexed { index, count ->
                if (count > 0) put(listOf("info", "request", "error", "exception")[index], count)
                pressure[index] = 0
            }
        }
    }

    internal fun ownership(): Pair<Int, Long> = lock.withLock { ownedCount to ownedBytes }

    private fun requestLocked(flush: Boolean) {
        flushRequested = flushRequested || flush
        if (scheduled) return
        scheduled = true
        completion = CountDownLatch(1)
        try { executor.execute(::run) }
        catch (_: Throwable) { scheduled = false; completion.countDown(); failure() }
    }

    private fun run() {
        workerThread = Thread.currentThread()
        var retry = false
        try {
            if (!initialized) { initialize(); initialized = true }
            do {
                val forceFlush = lock.withLock { flushRequested.also { flushRequested = false } }
                beforeDrain()
                val batch = lock.withLock { ArrayDeque(pending).also { pending.clear() } }
                val ready = ArrayList<Entry>()
                try {
                    while (batch.isNotEmpty()) {
                        val entry = batch.removeFirst()
                        if (nowMillis() - entry.queuedAt > ttlMillis) {
                            lock.withLock { release(entry); drop(entry) }
                            continue
                        }
                        val prepared = runCatching {
                            (if (entry.prepared) entry.envelope else prepare(entry.details?.enrich(entry.envelope) ?: entry.envelope, entry.runHook))?.takeIf { nowMillis() - entry.queuedAt <= ttlMillis }
                                ?.let { entry.copy(envelope = it, runHook = false, bytes = size(it), priority = priority(it), prepared = true, details = null) }
                        }.getOrElse { failure(); null }
                        val accepted = lock.withLock {
                            release(entry)
                            if (!closed && prepared != null && reserve(prepared)) { ready.add(prepared); true } else false
                        }
                        if (accepted && !entry.prepared) onPrepared(requireNotNull(prepared).envelope)
                    }
                    if (ready.isNotEmpty()) {
                        val persistedIds = persist(ready.map { it.envelope })
                        ready.forEach {
                            if (it.envelope.eventId in persistedIds) runCatching { it.persisted?.invoke() }.onFailure { failure() }
                        }
                    }
                    lock.withLock { ready.forEach(::release) }
                } catch (error: Throwable) {
                    // Prepared entries remain owned and retain final hook output across disk failures.
                    lock.withLock {
                        if (closed) { batch.forEach(::release); ready.forEach(::release) }
                        else {
                            batch.asReversed().forEach(pending::addFirst)
                            ready.asReversed().forEach(pending::addFirst)
                        }
                    }
                    throw error
                }
                beforeDrain()
                if (forceFlush) lock.withLock { if (pending.isNotEmpty()) flushRequested = true }
                if (lock.withLock { !closed } && (forceFlush || shouldFlush())) flush()
            } while (lock.withLock { !closed && (pending.isNotEmpty() || flushRequested) })
        } catch (_: Throwable) {
            failure()
            retry = true
        } finally {
            workerThread = null
            lock.withLock {
                scheduled = false
                completion.countDown()
                if (!closed && (retry || pending.isNotEmpty() || flushRequested)) {
                    // A single delayed wakeup prevents disk failures from spinning or allocating tasks per event.
                    scheduled = true
                    completion = CountDownLatch(1)
                    try { executor.schedule(::run, if (retry) 1000 else 0, TimeUnit.MILLISECONDS) }
                    catch (_: Throwable) { scheduled = false; completion.countDown() }
                }
            }
        }
    }

    private fun reserve(entry: Entry): Boolean {
        if (entry.bytes > MAX_BYTES) { drop(entry); return false }
        while (ownedCount >= MAX_EVENTS || ownedBytes + entry.bytes > MAX_BYTES) {
            val evicted = pending.firstOrNull { it.priority < entry.priority }
            if (evicted == null) { drop(entry); return false }
            pending.remove(evicted)
            release(evicted)
            drop(evicted)
        }
        ownedCount++
        ownedBytes += entry.bytes
        return true
    }

    private fun release(entry: Entry) { ownedCount--; ownedBytes -= entry.bytes }
    private fun drop(entry: Entry) {
        if (entry.envelope.eventType != DebugBundleEventTypes.ERROR_SUPPRESSED) countDrop(entry.priority)
    }
    private fun countDrop(priority: Int) { if (pressure[priority] < Long.MAX_VALUE) pressure[priority]++ }
    private fun size(envelope: DebugBundleEnvelope) = json.encodeToString(envelope).encodeToByteArray().size

    companion object {
        const val MAX_EVENTS = 256
        const val MAX_BYTES = 2L * 1024 * 1024
        fun priority(envelope: DebugBundleEnvelope): Int = when (envelope.eventType) {
            DebugBundleEventTypes.FRONTEND_EXCEPTION -> 3
            DebugBundleEventTypes.ERROR_SUPPRESSED -> 2
            DebugBundleEventTypes.LOG_EVENT -> if ((envelope.payload["level"] as? JsonPrimitive)?.content in setOf("error", "fatal", "critical")) 2 else 0
            DebugBundleEventTypes.REQUEST_EVENT -> if (((envelope.payload["response_status"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) >= 400) 2 else 1
            else -> 1
        }
    }
}
