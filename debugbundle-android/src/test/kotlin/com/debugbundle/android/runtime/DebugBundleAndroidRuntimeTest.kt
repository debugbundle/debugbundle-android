package com.debugbundle.android.runtime

import android.app.Application
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleClient
import com.debugbundle.android.DebugBundleDeviceContext
import com.debugbundle.android.DebugBundleDeviceContextProvider
import com.debugbundle.android.DebugBundleRemoteConfigClient
import com.debugbundle.android.DebugBundleRemoteConfigResult
import com.debugbundle.android.InMemoryDebugBundleQueueStore
import com.debugbundle.android.testkit.RecordingTransport
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugBundleAndroidRuntimeTest {
    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    @Suppress("DEPRECATION")
    fun `device context snapshots Android app battery memory display and release metadata`() {
        application.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 73)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING),
        )
        val context = AndroidDebugBundleDeviceContextProvider(
            application,
            DebugBundleConfig(
                appVersion = "1.2.3",
                buildNumber = "42",
                releaseChannel = "beta",
            ),
        ).snapshot()

        assertEquals("1.2.3", context.appVersion)
        assertEquals("42", context.buildNumber)
        assertEquals("beta", context.releaseChannel)
        assertEquals("Android", context.osName)
        assertEquals(35, context.apiLevel)
        assertEquals(73.0, context.batteryLevel)
        assertEquals(true, context.batteryCharging)
        assertTrue(context.locale?.isNotBlank() == true)
        assertTrue(context.timezone?.isNotBlank() == true)
        assertNotNull(context.deviceType)
        assertNotNull(context.freeDiskBytes)
        assertNotNull(context.freeMemoryBytes)
        assertNotNull(context.rooted)
    }

    @Test
    fun `Android bootstrap resolves platform defaults and keeps caller supplied integrations`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val transport = RecordingTransport()
        val queue = InMemoryDebugBundleQueueStore()
        val provider = DebugBundleDeviceContextProvider { DebugBundleDeviceContext(osName = "Test Android") }
        try {
            val client = DebugBundleAndroidBootstrap.createClient(
                application = application,
                config = DebugBundleConfig(
                    projectToken = "token",
                    service = "checkout",
                    captureFatalExceptions = false,
                    flushInterval = 10.seconds,
                ),
                transport = transport,
                remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
                queueStore = queue,
                deviceContextProvider = provider,
                runtimeRegistration = null,
                clock = { Instant.parse("2026-05-28T10:15:30Z") },
                random = { 0.0 },
                executor = executor,
            )

            client.captureException(IllegalStateException("bootstrapped"))
            client.flush()

            assertEquals(
                "\"Test Android\"",
                transport.events.single().payload["device"]
                    ?.let { it as kotlinx.serialization.json.JsonObject }
                    ?.get("os")
                    ?.let { it as kotlinx.serialization.json.JsonObject }
                    ?.get("name")
                    ?.toString(),
            )
            assertTrue(DebugBundleAndroidDefaults.defaultQueuePath(application).isAbsolute)
            assertTrue(DebugBundleAndroidDefaults.defaultFatalCrashPath(application).isAbsolute)
            assertTrue(DebugBundleAndroidConfigStore(application).load()?.projectToken == "token")
            client.close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `core client discovers Android bootstrap through the optional reflection boundary`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val transport = RecordingTransport()
        try {
            val client = DebugBundleClient.create(
                application = application,
                config = DebugBundleConfig(
                    projectToken = "token",
                    service = "checkout",
                    captureFatalExceptions = false,
                ),
                transport = transport,
                remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
                queueStore = InMemoryDebugBundleQueueStore(),
                deviceContextProvider = DebugBundleDeviceContextProvider {
                    DebugBundleDeviceContext(osName = "Reflected Android")
                },
                clock = { Instant.parse("2026-05-28T10:15:30Z") },
                random = { 0.0 },
                executor = executor,
            )

            client.captureException(IllegalStateException("reflected"))
            client.flush()

            assertEquals(1, transport.events.size)
            client.close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Android bootstrap falls back safely when passed a non Android application object`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val client = DebugBundleAndroidBootstrap.createClient(
                application = Any(),
                config = DebugBundleConfig(enabled = false),
                transport = RecordingTransport(),
                remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
                queueStore = null,
                deviceContextProvider = null,
                runtimeRegistration = null,
                clock = { Instant.EPOCH },
                random = { 0.0 },
                executor = executor,
            )

            assertEquals(com.debugbundle.android.DebugBundleStatus.Disconnected, client.status)
            client.close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `work manager scheduler installs unique periodic and immediate work`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
        val scheduler = DebugBundleWorkManagerScheduler(application)

        scheduler.ensureScheduled()
        scheduler.scheduleImmediate()

        val workManager = WorkManager.getInstance(application)
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(
                DebugBundleWorkManagerScheduler.PERIODIC_WORK_NAME,
            ).get().size,
        )
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(
                DebugBundleWorkManagerScheduler.IMMEDIATE_WORK_NAME,
            ).get().size,
        )
        assertFalse(
            workManager.getWorkInfosByTag(
                DebugBundleWorkManagerScheduler.WORK_TAG,
            ).get().isEmpty(),
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Android exit source safely returns platform history or an empty list`() {
        val activityManager = application.getSystemService(Application.ACTIVITY_SERVICE) as ActivityManager
        Shadows.shadowOf(activityManager).addApplicationExitInfo(
            application.packageName,
            0,
            ApplicationExitInfo.REASON_ANR,
            0,
        )
        val exits = AndroidDebugBundleExitInfoSource(application).load()

        assertEquals(ApplicationExitInfo.REASON_ANR, exits.single().reason)
    }

    @Test
    @Config(sdk = [27])
    fun `legacy Android paths preserve locale package version and skip unavailable exit history`() {
        val resolved = DebugBundleAndroidDefaults.resolveConfig(
            application,
            DebugBundleConfig(appVersion = null, buildNumber = null),
        )
        val context = AndroidDebugBundleDeviceContextProvider(application, resolved).snapshot()

        assertTrue(context.locale?.isNotBlank() == true)
        assertTrue(resolved.offlineQueuePath?.isAbsolute == true)
        assertTrue(resolved.fatalCrashPath?.isAbsolute == true)
        assertTrue(AndroidDebugBundleExitInfoSource(application).load().isEmpty())
    }
}
