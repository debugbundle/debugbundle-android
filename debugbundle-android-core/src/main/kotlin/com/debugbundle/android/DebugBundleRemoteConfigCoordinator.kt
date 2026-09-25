package com.debugbundle.android

import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Coalesced refresh work shares the capture executor; network never owns the metadata lock. */
internal class DebugBundleRemoteConfigCoordinator(
    private val config: DebugBundleConfig,
    private val client: DebugBundleRemoteConfigClient,
    private val clock: () -> Instant,
    private val executor: ScheduledExecutorService,
    private val policy: AtomicReference<DebugBundleCapturePolicy>,
    private val probes: DebugBundleRemoteProbeState,
    private val failure: () -> Unit,
) {
    private val lock = Any()
    private val queued = AtomicBoolean()
    private var eTag: String? = null
    private var refreshedAt: Long? = null
    private var inFlight = false

    fun request() {
        if (!queued.compareAndSet(false, true)) return
        try {
            executor.execute { try { refresh(false) } finally { queued.set(false) } }
        } catch (_: Throwable) { queued.set(false); failure() }
    }

    fun refresh(force: Boolean) {
        if (!config.enabled || config.projectToken.isBlank()) return
        val previousTag = synchronized(lock) {
            if (inFlight || (!force && refreshedAt?.let {
                    clock().toEpochMilli() - it < DEBUG_BUNDLE_MIN_REMOTE_CONFIG_REFRESH_INTERVAL_MILLIS
                } == true)) return
            inFlight = true
            eTag
        }
        try {
            when (val result = client.fetch(DebugBundleRemoteConfigRequest(
                config.projectToken, config.endpoint, config.requestTimeout, previousTag,
            ))) {
                is DebugBundleRemoteConfigResult.Loaded -> {
                    policy.set(DebugBundleCapturePolicy.fromRemotePolicy(result.config.capturePolicy))
                    probes.applyConfig(result.config.probesEnabled, result.config.remoteProbesEnabled,
                        result.config.activeProbes, result.config.triggerTokenKey, clock())
                    synchronized(lock) { eTag = result.eTag ?: eTag }
                }
                is DebugBundleRemoteConfigResult.NotModified -> synchronized(lock) { eTag = result.eTag ?: eTag }
                DebugBundleRemoteConfigResult.Failed -> policy.set(DebugBundleCapturePolicy.defaultWhenConfigFetchFails())
            }
            synchronized(lock) { refreshedAt = clock().toEpochMilli() }
        } catch (_: Throwable) { failure() }
        finally { synchronized(lock) { inFlight = false } }
    }
}
