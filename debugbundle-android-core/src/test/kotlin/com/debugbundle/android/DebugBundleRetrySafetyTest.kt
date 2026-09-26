package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleRetrySafetyTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `custom retry hints cannot overflow or suppress recovery beyond five minutes`() {
        listOf(429, 503, 202, 203).forEach { outcome ->
            listOf(24.hours, Duration.INFINITE).forEachIndexed { index, hint ->
                var now = Instant.parse("2026-05-28T10:15:30Z")
                val calls = AtomicInteger()
                val transport = RecordingTransport {
                    if (calls.incrementAndGet() > 1) DebugBundleTransportResult(statusCode = 202)
                    else DebugBundleTransportResult(statusCode = outcome, retryAfter = hint,
                        acknowledgementRequired = outcome == 202,
                        acknowledgement = if (outcome == 203) DebugBundleIngestionAcknowledgement(
                            accepted = 0, rejected = 1,
                            errors = listOf(DebugBundleIngestionError(index = 0, reason = "rate_limited"))) else null)
                }
                val client = DebugBundleClient.create(
                    config = DebugBundleConfig(projectToken = "token", service = "retry-safety",
                        offlineQueuePath = tempDir.resolve("queue-$outcome-$index.json"), flushInterval = 1.hours),
                    transport = transport,
                    remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.NotModified() },
                    clock = { now },
                    random = { 0.0 },
                )
                try {
                    client.captureMessage("retained", level = DebugBundleLogLevel.Error)
                    client.flush()
                    assertNull(client.lastEventAt)
                    client.flush()
                    assertEquals(1, calls.get(), "hint $hint must not wrap the deadline")
                    now = now.plusSeconds(299)
                    client.flush()
                    assertEquals(1, calls.get(), "outcome $outcome must honor the full retry delay")
                    now = now.plusSeconds(2)
                    client.flush()
                    assertEquals(2, calls.get(), "hint $hint must be capped at five minutes")
                    assertNotNull(client.lastEventAt)
                } finally {
                    client.close()
                }
            }
        }
    }
}
