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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugBundleActivityLifecycleCallbacksTest {
    @Test
    fun `resumed activity records screen breadcrumb`() {
        val tempDir = Files.createTempDirectory("debugbundle-activity-test")
        val transport = RecordingTransport()
        val client = DebugBundleClient.createWithoutAndroidBootstrap(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = tempDir.resolve("activity-test-events.json"),
            ),
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
            clock = { Instant.parse("2026-05-28T12:00:00Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        )
        val callbacks = DebugBundleActivityLifecycleCallbacks(client.lifecycle)
        val activity = TestActivity()
        callbacks.onActivityCreated(activity, null)
        callbacks.onActivityStarted(activity)
        callbacks.onActivityResumed(activity)
        callbacks.onActivityPaused(activity)
        callbacks.onActivityStopped(activity)
        callbacks.onActivitySaveInstanceState(activity, android.os.Bundle())
        callbacks.onActivityDestroyed(activity)

        client.captureException(IllegalStateException("boom"))
        client.flush()

        val breadcrumbs = transport.events.single().payload["breadcrumbs"] as JsonArray
        assertEquals(1, breadcrumbs.size)
        assertNotNull(activityScreenName(activity))
        client.close()
    }
}
