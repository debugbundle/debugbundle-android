package com.debugbundle.smoke

import com.debugbundle.android.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises only installed public Maven APIs, with no source overlays or internal helpers. */
@RunWith(RobolectricTestRunner::class)
class CaptureSafetySmokeTest {
    @Test
    fun installedCaptureReturnsWhileStoreAndHookAreHeldAndPrivacyPrecedesPersistence() {
        val storeEntered = CountDownLatch(1)
        val storeRelease = CountDownLatch(1)
        val hookEntered = CountDownLatch(1)
        val hookRelease = CountDownLatch(1)
        val backing = InMemoryDebugBundleQueueStore()
        val store = object : DebugBundleQueueStore by backing {
            override fun snapshot(nowMillis: Long, limits: DebugBundleQueueLimits): List<QueuedDebugBundleEvent> {
                storeEntered.countDown(); storeRelease.await(); return backing.snapshot(nowMillis, limits)
            }
        }
        val sent = CopyOnWriteArrayList<DebugBundleEnvelope>()
        val callers = Executors.newSingleThreadExecutor()
        var client: DebugBundleClient? = null
        try {
            client = callers.submit<DebugBundleClient> { create(store, sent, DebugBundleBeforeSend { event ->
                assertFalse(event.toString().contains("PRIVATE_VALUE"))
                hookEntered.countDown(); hookRelease.await(); event
            }) }.get(2, TimeUnit.SECONDS)
            assertTrue(storeEntered.await(2, TimeUnit.SECONDS))
            val active = client
            callers.submit { active.captureMessage("failure token=PRIVATE_VALUE", DebugBundleLogLevel.Error) }
                .get(2, TimeUnit.SECONDS)
            storeRelease.countDown()
            assertTrue(hookEntered.await(3, TimeUnit.SECONDS))
            callers.submit {
                active.captureException(IllegalStateException("while hook held"))
                active.flush(25.milliseconds)
            }.get(2, TimeUnit.SECONDS)
            assertTrue(sent.isEmpty())
            hookRelease.countDown()
            active.flush()
            assertTrue(sent.isNotEmpty())
            assertFalse(sent.toString().contains("PRIVATE_VALUE"))
        } finally { storeRelease.countDown(); hookRelease.countDown(); client?.close(); callers.shutdownNow() }
    }

    @Test
    fun installedExceptionCaptureDoesNotWaitForThrowableGetterOrMonitor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val getter = object : RuntimeException() {
            override val message: String get() { entered.countDown(); release.await(); return "getter returned" }
        }
        val monitorError = IllegalStateException("monitor held")
        val monitorHeld = CountDownLatch(1)
        val holder = Thread { synchronized(monitorError) { monitorHeld.countDown(); release.await() } }.apply { start() }
        val callers = Executors.newSingleThreadExecutor()
        val sent = CopyOnWriteArrayList<DebugBundleEnvelope>()
        val client = create(InMemoryDebugBundleQueueStore(), sent)
        try {
            assertTrue(monitorHeld.await(2, TimeUnit.SECONDS))
            callers.submit { client.captureException(getter) }.get(2, TimeUnit.SECONDS)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            callers.submit { client.captureException(monitorError) }.get(2, TimeUnit.SECONDS)
            assertTrue(sent.isEmpty())
            release.countDown()
            client.flush()
            assertEquals(2, sent.size)
        } finally { release.countDown(); holder.join(2000); client.close(); callers.shutdownNow() }
    }

    private fun create(
        store: DebugBundleQueueStore,
        sent: MutableList<DebugBundleEnvelope>,
        hook: DebugBundleBeforeSend? = null,
    ) = DebugBundleClient.create(
        config = DebugBundleConfig(projectToken = "installed-safety-token", captureFatalExceptions = false).withBeforeSend(hook),
        queueStore = store,
        transport = DebugBundleTransport { request -> sent.addAll(request.events); DebugBundleTransportResult(statusCode = 202) },
        remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
        executor = Executors.newSingleThreadScheduledExecutor(),
    )
}
