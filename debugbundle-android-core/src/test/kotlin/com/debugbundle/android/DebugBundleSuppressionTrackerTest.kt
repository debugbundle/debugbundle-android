package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleSuppressionTracker
import com.debugbundle.android.internal.debugBundleSuppressionSourceId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleSuppressionTrackerTest {
    @Test
    fun `unique bursts retain only bounded digest identities and compatible aggregate fingerprints`() {
        val tracker = DebugBundleSuppressionTracker()
        repeat(1000) { tracker.shouldCapture("source-$it-" + "x".repeat(1000), 1000) }
        assertEquals(500, tracker.retainedKeys().size)
        assertTrue(tracker.retainedKeys().all { it.length == 64 })
        val key = "same"
        repeat(4) { tracker.shouldCapture(key, 1000) }
        val aggregate = tracker.drainAggregates(1000).single()
        assertEquals(debugBundleSuppressionSourceId(key), aggregate.sourceKey)
        assertEquals("cd0c4a3b", aggregate.fingerprint)
        assertEquals(1, aggregate.suppressedCount)
        assertTrue(tracker.drainAggregates(1000).isEmpty())
    }
}
