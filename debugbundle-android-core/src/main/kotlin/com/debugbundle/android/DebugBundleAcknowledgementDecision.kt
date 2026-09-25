package com.debugbundle.android

internal sealed interface DebugBundleAcknowledgementDecision {
    data object LegacyTransportSuccess : DebugBundleAcknowledgementDecision

    data object ProtocolFailure : DebugBundleAcknowledgementDecision

    data class Accounted(
        val accepted: Int,
        val retryableIndices: Set<Int>,
        val acceptedFrontendException: Boolean,
    ) : DebugBundleAcknowledgementDecision
}

internal fun decideDebugBundleAcknowledgement(
    result: DebugBundleTransportResult,
    batch: List<DebugBundleEnvelope>,
): DebugBundleAcknowledgementDecision {
    val acknowledgement = result.acknowledgement
        ?: return if (result.acknowledgementRequired) {
            DebugBundleAcknowledgementDecision.ProtocolFailure
        } else {
            DebugBundleAcknowledgementDecision.LegacyTransportSuccess
        }

    val rejectedIndices = acknowledgement.errors.map(DebugBundleIngestionError::index)
    val isConsistent = acknowledgement.accepted >= 0 &&
        acknowledgement.rejected >= 0 &&
        acknowledgement.accepted + acknowledgement.rejected == batch.size &&
        acknowledgement.errors.size == acknowledgement.rejected &&
        rejectedIndices.distinct().size == rejectedIndices.size &&
        rejectedIndices.all { it in batch.indices } &&
        acknowledgement.errors.all { it.reason.isNotBlank() }
    if (!isConsistent) {
        return DebugBundleAcknowledgementDecision.ProtocolFailure
    }

    val rejectedIndexSet = rejectedIndices.toSet()
    val acceptedFrontendException = batch.indices.any { index ->
        index !in rejectedIndexSet &&
            batch[index].eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION
    }
    return DebugBundleAcknowledgementDecision.Accounted(
        accepted = acknowledgement.accepted,
        retryableIndices = acknowledgement.errors
            .filter { it.reason in RETRYABLE_INGESTION_REJECTION_REASONS }
            .mapTo(linkedSetOf(), DebugBundleIngestionError::index),
        acceptedFrontendException = acceptedFrontendException,
    )
}

private val RETRYABLE_INGESTION_REJECTION_REASONS = setOf(
    "rate_limited",
    "monthly_quota_exceeded",
    "analytics_quota_exceeded",
)

/** Worker-serialized acknowledgement uses original identities, including externally mutated custom stores. */
internal fun DebugBundleQueueStore.reconcileAcknowledgedEvents(
    sent: List<DebugBundleEnvelope>,
    retryableIndices: Set<Int>,
    nowMillis: Long,
    limits: DebugBundleQueueLimits,
): List<QueuedDebugBundleEvent> {
    val retryIds = sent.filterIndexed { index, _ -> index in retryableIndices }.map { it.eventId }.toSet()
    val completedIds = sent.map { it.eventId }.toSet() - retryIds
    val current = snapshot(nowMillis, limits)
    if (this is DebugBundleIndexedAcknowledgementQueueStore) {
        val retained = current.indices.filter { current[it].envelope.eventId !in completedIds }.toSet()
        return retainLeadingIndices(current.size, retained, nowMillis, limits)
    } else if (retryableIndices.isEmpty()) {
        // Preserve the legacy store's all-or-retry partial-ACK behavior. Its public
        // interface can safely remove only a currently leading, acknowledged run.
        val completedPrefix = current.takeWhile { it.envelope.eventId in completedIds }.size
        if (completedPrefix > 0) return removeLeading(completedPrefix, nowMillis, limits)
    }
    return current
}
