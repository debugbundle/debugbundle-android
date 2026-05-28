package com.debugbundle.android.runtime

import com.debugbundle.android.DebugBundleClient
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleRemoteConfigClient
import com.debugbundle.android.DebugBundleRemoteConfigResult
import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBundleApplicationExitInfoReporterTest {
    @Test
    fun `new anr exit info is captured once`() {
        val tempDir = Files.createTempDirectory("debugbundle-anr-test")
        val application = TestApplication(tempDir.toFile())
        val transport = RecordingTransport()
        val configStore = DebugBundleAndroidConfigStore(application)
        val client = DebugBundleClient.createWithoutAndroidBootstrap(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = tempDir.resolve("anr-test-events.json"),
            ),
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
            clock = { Instant.parse("2026-05-28T12:00:00Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        )
        val reporter = DebugBundleApplicationExitInfoReporter(
            application = application,
            configStore = configStore,
            exitInfoSource = DebugBundleExitInfoSource {
                listOf(
                    DebugBundleExitInfo(
                        reason = android.app.ApplicationExitInfo.REASON_ANR,
                        timestampMillis = Instant.parse("2026-05-28T11:55:00Z").toEpochMilli(),
                        importance = 100,
                        processStateSummary = "state",
                        description = "main thread blocked",
                        pssKb = 123L,
                        rssKb = 456L,
                        traceExcerpt = "blocked here",
                    ),
                )
            },
        )

        assertTrue(reporter.capturePendingExitInfo(client))
        assertEquals(false, reporter.capturePendingExitInfo(client))
        client.flush()

        val payload = transport.events.single().payload
        val context = payload["context"] as JsonObject
        assertEquals("anr", (context["exit_reason"] as JsonPrimitive).content)
        client.close()
    }
}
