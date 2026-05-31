package com.debugbundle.android.runtime

import androidx.work.ListenableWorker.Result
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleEnvelope
import com.debugbundle.android.DebugBundleDeviceContext
import com.debugbundle.android.DebugBundleDeviceContextProvider
import com.debugbundle.android.DebugBundleRemoteConfigClient
import com.debugbundle.android.DebugBundleRemoteConfigResult
import com.debugbundle.android.DebugBundleServiceDescriptor
import com.debugbundle.android.FileDebugBundleQueueStore
import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBundleFlushWorkerTest {
    @After
    fun tearDown() {
        DebugBundleAndroidRuntimeOverrides.transportFactory = null
        DebugBundleAndroidRuntimeOverrides.remoteConfigClientFactory = null
        DebugBundleAndroidRuntimeOverrides.deviceContextProviderFactory = null
    }

    @Test
    fun `worker flushes persisted queue with stored config`() = runBlocking {
        val tempDir = Files.createTempDirectory("debugbundle-worker-test")
        val application = TestApplication(tempDir.toFile())
        val queuedAtMillis = System.currentTimeMillis()
        val config = DebugBundleAndroidDefaults.resolveConfig(
            application,
            DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                requestTimeout = 1.seconds,
            ),
        )
        val queueStore = FileDebugBundleQueueStore(config.offlineQueuePath!!)
        val limits = DebugBundleAndroidDefaults.queueLimits(config)
        queueStore.append(
            events = listOf(
                DebugBundleEnvelope(
                    schemaVersion = "2026-01-01",
                    eventId = "event-1",
                    eventType = "log_event",
                    sdkName = "@debugbundle/sdk-android",
                    sdkVersion = config.sdkVersion,
                    service = DebugBundleServiceDescriptor(
                        name = config.service,
                        environment = config.environment,
                    ),
                    occurredAt = "2026-05-28T12:00:00Z",
                    payload = JsonObject(mapOf("message" to JsonPrimitive("queued"))),
                ),
            ),
            nowMillis = queuedAtMillis,
            limits = limits,
        )
        DebugBundleAndroidConfigStore(application).save(config)

        val flushTransport = RecordingTransport()
        DebugBundleAndroidRuntimeOverrides.transportFactory = { flushTransport }
        DebugBundleAndroidRuntimeOverrides.remoteConfigClientFactory =
            { DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed } }
        DebugBundleAndroidRuntimeOverrides.deviceContextProviderFactory =
            { _, _ -> DebugBundleDeviceContextProvider { DebugBundleDeviceContext(osName = "Android") } }

        val result = DebugBundleFlushWorkerRunner(
            application = application,
            dispatcher = Dispatchers.IO,
        ).run()

        assertEquals(Result.success(), result)
        assertTrue(flushTransport.events.isNotEmpty())
        assertEquals(0, queueStore.snapshot(System.currentTimeMillis(), limits).size)
    }
}
