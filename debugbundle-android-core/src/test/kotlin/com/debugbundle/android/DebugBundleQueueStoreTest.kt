package com.debugbundle.android

import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleQueueStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `memory store retains rejected batch indices in original order`() {
        val store = InMemoryDebugBundleQueueStore()
        val limits = DebugBundleQueueLimits(maxEvents = 10, maxBytes = 1_000_000, ttlMillis = 10_000)
        store.append(listOf(envelope(1), envelope(2), envelope(3), envelope(4)), 1_000, limits)

        val remaining = store.retainLeadingIndices(
            count = 3,
            retainedIndices = setOf(0, 2),
            nowMillis = 1_001,
            limits = limits,
        )

        assertEquals(listOf("event-1", "event-3", "event-4"), remaining.map(::message))
    }

    @Test
    fun `memory store applies ttl event and byte limits`() {
        val store = InMemoryDebugBundleQueueStore()
        val broad = DebugBundleQueueLimits(maxEvents = 10, maxBytes = 1_000_000, ttlMillis = 100)
        store.append(listOf(envelope(1), envelope(2)), 1_000, broad)

        assertTrue(store.snapshot(1_101, broad).isEmpty())

        val oneEvent = DebugBundleQueueLimits(maxEvents = 1, maxBytes = 1_000_000, ttlMillis = 10_000)
        assertEquals(
            listOf("event-4"),
            store.append(listOf(envelope(3), envelope(4)), 2_000, oneEvent).map(::message),
        )

        val tiny = DebugBundleQueueLimits(maxEvents = 10, maxBytes = 1, ttlMillis = 10_000)
        assertTrue(store.snapshot(2_000, tiny).isEmpty())
    }

    @Test
    fun `file store retains indices prunes limits and recovers corrupt state`() {
        val queuePath = tempDir.resolve("queue.json")
        val store = FileDebugBundleQueueStore(queuePath)
        val limits = DebugBundleQueueLimits(maxEvents = 10, maxBytes = 1_000_000, ttlMillis = 10_000)
        store.append(listOf(envelope(1), envelope(2), envelope(3), envelope(4)), 1_000, limits)

        val remaining = store.retainLeadingIndices(
            count = 3,
            retainedIndices = setOf(1),
            nowMillis = 1_001,
            limits = limits,
        )
        assertEquals(listOf("event-2", "event-4"), remaining.map(::message))

        val oneEvent = DebugBundleQueueLimits(maxEvents = 1, maxBytes = 1_000_000, ttlMillis = 10_000)
        assertEquals(listOf("event-4"), store.snapshot(1_001, oneEvent).map(::message))

        queuePath.toFile().writeText("not-json")
        assertTrue(store.snapshot(1_002, limits).isEmpty())
    }

    private fun message(queued: QueuedDebugBundleEvent): String {
        return queued.envelope.payload["message"].toString().trim('"')
    }

    private fun envelope(index: Int): DebugBundleEnvelope {
        return DebugBundleEnvelope(
            schemaVersion = "2026-03-01",
            eventId = "22222222-2222-4222-8222-${index.toString().padStart(12, '0')}",
            eventType = DebugBundleEventTypes.LOG_EVENT,
            sdkName = "@debugbundle/sdk-android",
            sdkVersion = "1.0.0",
            service = DebugBundleServiceDescriptor("checkout", "production"),
            occurredAt = "2026-05-28T10:15:30Z",
            payload = buildJsonObject {
                put("level", "error")
                put("message", "event-$index")
                put("attributes", buildJsonObject {})
            },
        )
    }
}
