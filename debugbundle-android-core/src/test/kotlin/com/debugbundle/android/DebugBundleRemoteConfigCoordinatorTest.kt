package com.debugbundle.android

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleRemoteConfigCoordinatorTest {
    @Test
    fun `opportunistic refresh coalesces while held and preserves etag minimum interval and explicit refresh`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = mutableListOf<DebugBundleRemoteConfigRequest>()
        val client = DebugBundleRemoteConfigClient { request ->
            synchronized(requests) { requests.add(request) }
            if (requests.size == 1) { entered.countDown(); release.await() }
            when (requests.size) {
                1 -> DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse(), "tag-1")
                2 -> DebugBundleRemoteConfigResult.NotModified("tag-2")
                else -> DebugBundleRemoteConfigResult.Failed
            }
        }
        val coordinator = coordinator(executor, client)
        try {
            coordinator.request()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(1000) { coordinator.request(); coordinator.refresh(true) }
            assertEquals(1, requests.size)
            release.countDown()
            executor.submit {}.get(2, TimeUnit.SECONDS)
            coordinator.refresh(false)
            assertEquals(1, requests.size)
            coordinator.refresh(true)
            coordinator.refresh(true)
            assertEquals(listOf(null, "tag-1", "tag-2"), requests.map { it.eTag })
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `failed and rejected refresh reset ownership and disabled client never fetches`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val failures = AtomicInteger()
        val client = DebugBundleRemoteConfigClient { error("unavailable") }
        val coordinator = coordinator(executor, client) { failures.incrementAndGet() }
        try {
            coordinator.refresh(true)
            coordinator.refresh(true)
            assertEquals(2, failures.get())
            executor.shutdownNow()
            coordinator.request()
            coordinator.request()
            assertEquals(4, failures.get())
            DebugBundleRemoteConfigCoordinator(DebugBundleConfig(projectToken = ""), client,
                { Instant.EPOCH }, executor, AtomicReference(DebugBundleCapturePolicy.defaultWhenConfigFetchFails()),
                DebugBundleRemoteProbeState(), { fail("disabled fetch") }).refresh(true)
        } finally { executor.shutdownNow() }
    }

    private fun coordinator(
        executor: java.util.concurrent.ScheduledExecutorService,
        client: DebugBundleRemoteConfigClient,
        failure: () -> Unit = {},
    ) = DebugBundleRemoteConfigCoordinator(DebugBundleConfig(projectToken = "token"), client,
        { Instant.parse("2026-09-25T00:00:00Z") }, executor,
        AtomicReference(DebugBundleCapturePolicy.defaultWhenConfigFetchFails()), DebugBundleRemoteProbeState(), failure)
}
