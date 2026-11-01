package com.debugbundle.android

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Existing worker-owned durable delivery; capture callers never perform its disk/transport work. */
internal class DebugBundleDelivery(
    private val config: DebugBundleConfig,
    private val transport: DebugBundleTransport,
    private val queueStore: DebugBundleQueueStore,
    private val clock: () -> Instant,
    private val protectEnvelope: (DebugBundleEnvelope) -> DebugBundleEnvelope?,
    private val buildDeviceContext: () -> JsonObject,
    private val statusRef: AtomicReference<DebugBundleStatus>,
    private val lastEventAtRef: AtomicLong,
    private val applyPiggybackProbeDirectives: (List<DebugBundleRemoteProbeDirective>?) -> Unit,
    private val onAcceptedFrontendException: () -> Unit,
    private val filterForDelivery: (DebugBundleEnvelope) -> DebugBundleEnvelope? = { it },
    private val observeEvidence: (DebugBundleTransportResult, Boolean) -> Boolean = { _, _ -> false },
    private val onQueuePublished: (List<QueuedDebugBundleEvent>) -> Unit = {},
    private val onHydrate: (List<QueuedDebugBundleEvent>) -> Unit = {},
    private val holdForDiscovery: (DebugBundleEnvelope) -> Boolean = { false },
) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val captureConfigured = config.enabled && config.projectToken.isNotBlank()
    private val buffer = ArrayDeque<DebugBundleEnvelope>()
    private val lock = Any()
    private var nextRetryAtMillis = 0L
    private var flushing = false
    private var restored = false
    private var consecutiveFailures = 0
    private val legacyOriginalIds = hashSetOf<String>()
    fun hasEnoughEvents(): Boolean = buffer.size >= config.batchSize
    /** Worker-owned hydration and publication; no application capture lock surrounds disk work. */
    fun syncBufferFromQueue(nowMillis: Long) {
        val pending = queueStore.snapshot(nowMillis, debugBundleQueueLimits(config))
        if (!restored) { onHydrate(pending); restored = true }
        publishQueue(pending)
    }

    fun publishQueue(pending: List<QueuedDebugBundleEvent>) {
        onQueuePublished(pending)
        buffer.clear()
        legacyOriginalIds.retainAll(pending.map { it.envelope.eventId }.toSet())
        val limits = debugBundleQueueLimits(config)
        if (pending.size > limits.maxEvents) return
        val currentDevice = buildDeviceContext()
        var bytes = 0L
        for (queued in pending) {
            val safe = runCatching { protectEnvelope(canonicalizeAndroidEnvelope(queued.envelope, currentDevice)) }.getOrNull()
            // Never shift custom-store ACK indices by publishing only the safe prefix.
            if (safe == null) { buffer.clear(); return }
            bytes += json.encodeToString(DebugBundleEnvelope.serializer(), safe).encodeToByteArray().size
            if (bytes > limits.maxBytes) { buffer.clear(); return }
            buffer.addLast(safe)
        }
    }

    private fun buildRetryDelay(result: DebugBundleTransportResult): Duration {
        result.retryAfter.takeIf { it > Duration.ZERO }?.let { return it.coerceAtMost(5.minutes) }
        return if (result.statusCode == 429) 5.seconds else 1.seconds
    }

    private fun updateFailureStatus() {
        statusRef.set(
            if (consecutiveFailures >= 3) {
                DebugBundleStatus.Disconnected
            } else {
                DebugBundleStatus.Degraded
            },
        )
    }

    private fun resetHealthyStatus() {
        consecutiveFailures = 0
        statusRef.set(if (captureConfigured) DebugBundleStatus.Healthy else DebugBundleStatus.Disconnected)
    }

    fun flushSafely(timeout: Duration) {
        if (!captureConfigured) return
        val acquired = synchronized(lock) {
            if (buffer.isEmpty() || flushing) false
            else { flushing = true; true }
        }
        if (!acquired) return
        val started = System.nanoTime()
        try {
            safely {
                syncBufferFromQueue(clock().toEpochMilli())
                if (clock().toEpochMilli() < nextRetryAtMillis) return@safely
                do {
                    val current = buffer.toList()
                    val prepared = current.mapNotNull { prepareForDelivery(it) }
                    val ids = prepared.mapTo(hashSetOf()) { it.eventId }
                    val dropped = current.filter { it.eventId !in ids && !holdForDiscovery(it) }
                    if (dropped.isNotEmpty()) publishQueue(queueStore.reconcileAcknowledgedEvents(
                        dropped, emptySet(), clock().toEpochMilli(), debugBundleQueueLimits(config),
                    ))
                    if (prepared.isEmpty()) return@safely
                    val native = transport is DebugBundleHttpTransport
                    val batch = if (native) nativePrefix(prepared) else prepared
                    if (batch.isEmpty()) return@safely
                    val remaining = timeout - (System.nanoTime() - started).nanoseconds
                    if (remaining <= Duration.ZERO) return@safely
                    val result = transport.send(DebugBundleTransportRequest(
                        projectToken = config.projectToken, endpoint = config.endpoint,
                        events = batch, timeout = remaining,
                    ))
                    val lostProof = observeEvidence(result, result.isSuccess &&
                        decideDebugBundleAcknowledgement(result, batch) is DebugBundleAcknowledgementDecision.Accounted)
                    val compatible = compatibleRejectedOriginals(result, batch, lostProof)
                    compatible.forEach { legacyOriginalIds.add(batch[it].eventId) }
                    when {
                        result.shouldRetry -> {
                            consecutiveFailures += 1; updateFailureStatus()
                            nextRetryAtMillis = clock().toEpochMilli() + buildRetryDelay(result).inWholeMilliseconds
                            return@safely
                        }
                        result.isSuccess -> {
                            handleSuccessfulTransportResult(result, batch, compatible)
                            if (nextRetryAtMillis > clock().toEpochMilli()) return@safely
                        }
                        else -> {
                            publishQueue(queueStore.reconcileAcknowledgedEvents(batch, emptySet(), clock().toEpochMilli(), debugBundleQueueLimits(config)))
                            nextRetryAtMillis = 0; resetHealthyStatus()
                        }
                    }
                    // Opaque transports retain their existing one-call flush behavior.
                    if (!native || buffer.map { it.eventId } == current.map { it.eventId }) return@safely
                } while (buffer.isNotEmpty())
            }
        } finally { synchronized(lock) { flushing = false } }
    }

    /** Known native serializer bytes, including the actual events wrapper and commas. */
    private fun nativePrefix(events: List<DebugBundleEnvelope>): List<DebugBundleEnvelope> {
        val selected = ArrayList<DebugBundleEnvelope>(); var bytes = 13
        for (event in events.take(minOf(config.batchSize.coerceAtLeast(1), 100))) {
            val size = wireBytes(event) + if (selected.isEmpty()) 0 else 1
            if (bytes + size > 256 * 1024) break
            bytes += size; selected.add(event)
        }
        return selected
    }

    internal fun filterPersisted(event: DebugBundleEnvelope): DebugBundleEnvelope =
        if (event.eventId in legacyOriginalIds) withoutDebugBundleOptionalEvidence(event, false) ?: event else event

    private fun prepareForDelivery(event: DebugBundleEnvelope): DebugBundleEnvelope? {
        val filtered = filterForDelivery(filterPersisted(event)) ?: return null
        if (transport !is DebugBundleHttpTransport) return filtered
        val safe = protectEnvelope(filtered) ?: return null
        if (wireBytes(safe) <= 256 * 1024 - 13) return safe
        for (mark in listOf(true, false)) {
            val reduced = withoutDebugBundleOptionalEvidence(safe, mark) ?: continue
            val protected = protectEnvelope(reduced) ?: continue
            if (wireBytes(protected) <= 256 * 1024 - 13) return protected
        }
        // The latest valid hook primary cannot fit: never restore pre-hook data.
        return null
    }

    private fun wireBytes(event: DebugBundleEnvelope): Int =
        json.encodeToString(DebugBundleEnvelope.serializer(), event).encodeToByteArray().size

    private fun compatibleRejectedOriginals(result: DebugBundleTransportResult, batch: List<DebugBundleEnvelope>, lostProof: Boolean): Set<Int> {
        if (!lostProof || !result.isSuccess || decideDebugBundleAcknowledgement(result, batch) !is DebugBundleAcknowledgementDecision.Accounted) return emptySet()
        return result.acknowledgement!!.errors.filter { error ->
            error.reason == "invalid_event" && batch[error.index].eventType in setOf("backend_exception", DebugBundleEventTypes.FRONTEND_EXCEPTION) &&
                filterForDelivery(batch[error.index])?.let { json.encodeToString(DebugBundleEnvelope.serializer(), it) != json.encodeToString(DebugBundleEnvelope.serializer(), batch[error.index]) } == true
        }.mapTo(linkedSetOf()) { it.index }
    }

    private fun handleSuccessfulTransportResult(
        result: DebugBundleTransportResult,
        batch: List<DebugBundleEnvelope>,
        compatible: Set<Int>,
    ) {
        when (val decision = decideDebugBundleAcknowledgement(result, batch)) {
            DebugBundleAcknowledgementDecision.ProtocolFailure -> {
                consecutiveFailures += 1
                updateFailureStatus()
                nextRetryAtMillis = clock().toEpochMilli() + buildRetryDelay(result).inWholeMilliseconds
            }

            DebugBundleAcknowledgementDecision.LegacyTransportSuccess -> {
                applyPiggybackProbeDirectives(result.probeDirectives)
                if (batch.any { isDebugBundleException(it.eventType) }) {
                    onAcceptedFrontendException()
                }
                publishQueue(queueStore.reconcileAcknowledgedEvents(batch, emptySet(), clock().toEpochMilli(), debugBundleQueueLimits(config)))
                nextRetryAtMillis = 0
                resetHealthyStatus()
                lastEventAtRef.set(clock().toEpochMilli())
            }

            is DebugBundleAcknowledgementDecision.Accounted -> {
                applyPiggybackProbeDirectives(result.probeDirectives)
                if (decision.acceptedFrontendException) {
                    onAcceptedFrontendException()
                }
                val retained = decision.retryableIndices + compatible
                publishQueue(queueStore.reconcileAcknowledgedEvents(
                    batch, retained, clock().toEpochMilli(), debugBundleQueueLimits(config),
                ))

                if (decision.accepted > 0) {
                    lastEventAtRef.set(clock().toEpochMilli())
                }
                if (retained.isNotEmpty()) {
                    consecutiveFailures += 1
                    updateFailureStatus()
                    nextRetryAtMillis = clock().toEpochMilli() + buildRetryDelay(result).inWholeMilliseconds
                } else {
                    nextRetryAtMillis = 0
                    consecutiveFailures = 0
                    statusRef.set(
                        if (decision.accepted > 0) {
                            DebugBundleStatus.Healthy
                        } else {
                            DebugBundleStatus.Disconnected
                        },
                    )
                }
            }
        }
    }

    private fun safely(block: () -> Unit) {
        try { block() } catch (_: Throwable) { statusRef.set(DebugBundleStatus.Degraded) }
    }
}
