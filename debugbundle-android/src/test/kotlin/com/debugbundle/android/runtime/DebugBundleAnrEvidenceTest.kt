package com.debugbundle.android.runtime

import android.app.ApplicationExitInfo
import com.debugbundle.android.*
import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugBundleAnrEvidenceTest {
    @Test fun `native ANR uses report time and supplied frames and marks receipt only after persistence`() = verify("\"main\" tid=1\n at example.Checkout.submit(Checkout.kt:42)\n password=hidden", "runtime_reported")
    @Test fun `constructed ANR without native frames is explicitly capture provenance`() = verify(null, "capture")
    private fun verify(trace: String?, expectedOrigin: String) {
        val now = Instant.parse("2026-10-28T00:00:00Z"); val happened = now.minusSeconds(60)
        val application = TestApplication(Files.createTempDirectory("debugbundle-anr-evidence").toFile())
        val store = DebugBundleAndroidConfigStore(application); val transport = RecordingTransport(); val hooks = AtomicInteger()
        val executor = Executors.newSingleThreadScheduledExecutor(); val held = CountDownLatch(1)
        val client = DebugBundleClient.create(config = DebugBundleConfig(projectToken = "fixture", captureFatalExceptions = false, flushInterval = 1.hours)
            .withEvidenceOptions(DebugBundleEvidenceOptions(evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1"))))
            .withBeforeSend(DebugBundleBeforeSend { hooks.incrementAndGet(); it }), transport = transport, executor = executor, clock = { now },
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
        try {
            executor.submit {}.get(5, TimeUnit.SECONDS); executor.execute { held.await(5, TimeUnit.SECONDS) }
            val reporter = DebugBundleApplicationExitInfoReporter(application, store, DebugBundleExitInfoSource { listOf(DebugBundleExitInfo(
                reason = ApplicationExitInfo.REASON_ANR, timestampMillis = happened.toEpochMilli(), importance = 100,
                processStateSummary = null, description = "Application did not respond", pssKb = 1, rssKb = 2, traceExcerpt = trace)) })
            assertTrue(reporter.capturePendingExitInfo(client)); assertEquals(0L, store.lastReportedExitTimestamp())
            held.countDown(); client.flush(); val event = transport.events.single()
            assertEquals(happened.toString(), event.occurredAt); assertEquals(0, hooks.get())
            val graph = event.payload.getValue("exception_evidence").jsonObject; val node = graph.getValue("nodes").jsonArray.first().jsonObject
            assertEquals(expectedOrigin, node.getValue("trace_origin").jsonPrimitive.content)
            if (trace != null) assertEquals("example.Checkout.submit", node.getValue("frames").jsonArray.first().jsonObject.getValue("function").jsonPrimitive.content)
            assertFalse(event.payload.toString().contains("hidden")); assertFalse(event.context.toString().contains("hidden"))
            assertEquals(happened.toEpochMilli(), store.lastReportedExitTimestamp()); assertFalse(reporter.capturePendingExitInfo(client))
        } finally { held.countDown(); client.close() }
    }
}
