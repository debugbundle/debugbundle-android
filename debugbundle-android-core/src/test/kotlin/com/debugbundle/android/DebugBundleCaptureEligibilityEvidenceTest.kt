package com.debugbundle.android

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DebugBundleCaptureEligibilityEvidenceTest {
    @Test fun `disabled exception capture performs no clock projection or telemetry side effects`() {
        val calls = AtomicInteger()
        val client = DebugBundleClient.create(config = DebugBundleConfig(enabled = false), clock = { calls.incrementAndGet(); throw IllegalStateException("clock must not run") })
        try {
            client.captureException(IllegalStateException("fictional")); assertEquals(0, calls.get())
            assertEquals(DebugBundleStatus.Disconnected, client.status)
        } finally { client.close() }
    }
}
