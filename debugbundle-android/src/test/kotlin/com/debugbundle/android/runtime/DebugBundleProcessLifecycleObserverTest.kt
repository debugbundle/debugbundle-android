package com.debugbundle.android.runtime

import com.debugbundle.android.DebugBundleClient
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleRemoteConfigClient
import com.debugbundle.android.DebugBundleRemoteConfigResult
import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBundleProcessLifecycleObserverTest {
    @Test
    fun `start and stop record app lifecycle breadcrumbs and schedule flush`() {
        val transport = RecordingTransport()
        val scheduler = RecordingScheduler()
        val client = DebugBundleClient.createWithoutAndroidBootstrap(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = Files.createTempDirectory("debugbundle-process").resolve("events.json"),
            ),
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
            clock = { Instant.parse("2026-05-28T12:00:00Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        )
        val observer = DebugBundleProcessLifecycleObserver(client.lifecycle, scheduler)
        val owner = TestLifecycleOwner()

        observer.onStart(owner)
        observer.onStop(owner)
        client.captureException(IllegalStateException("boom"))
        client.flush()

        val breadcrumbs = transport.events.single().payload["breadcrumbs"] as JsonArray
        assertEquals(2, breadcrumbs.size)
        assertTrue(scheduler.immediateCount > 0)
        client.close()
    }

    private class RecordingScheduler : DebugBundleBackgroundFlushScheduler {
        var immediateCount: Int = 0

        override fun ensureScheduled() = Unit

        override fun scheduleImmediate() {
            immediateCount += 1
        }
    }

    private class TestLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this)

        override val lifecycle: androidx.lifecycle.Lifecycle
            get() = registry
    }
}
