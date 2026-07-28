package com.debugbundle.android.internal

private const val DUPLICATE_WINDOW_MS = 30_000L
private const val LOOP_WINDOW_MS = 2_000L
private const val LOOP_THRESHOLD = 10
private const val LOOP_RESET_AFTER_MS = 60_000L
private const val LOOP_CHECKPOINT_MS = 30_000L
private const val MAX_NORMAL_EVENTS_PER_WINDOW = 3

internal data class DebugBundleSuppressionAggregate(
    val sourceKey: String,
    val fingerprint: String,
    val suppressedCount: Int,
    val firstSeenIso: String,
    val lastSeenIso: String,
    val windowSeconds: Int,
)

internal class DebugBundleSuppressionTracker {
    private val states = LinkedHashMap<String, SuppressionState>()

    @Synchronized
    fun shouldCapture(key: String, nowMillis: Long): Boolean {
        val state = states.getOrPut(key) { SuppressionState(nowMillis) }

        if (state.suppressionMode && nowMillis - state.lastSeenAtMillis >= LOOP_RESET_AFTER_MS) {
            states[key] = SuppressionState(nowMillis)
            return shouldCapture(key, nowMillis)
        }

        if (nowMillis - state.windowStartedAtMillis >= DUPLICATE_WINDOW_MS) {
            state.windowStartedAtMillis = nowMillis
            state.emittedCount = 0
        }

        if (nowMillis - state.loopWindowStartedAtMillis >= LOOP_WINDOW_MS) {
            state.loopWindowStartedAtMillis = nowMillis
            state.loopHitCount = 0
        }

        state.loopHitCount += 1
        state.lastSeenAtMillis = nowMillis

        if (state.loopHitCount > LOOP_THRESHOLD) {
            state.suppressionMode = true
        }

        if (state.suppressionMode) {
            markSuppressed(state, nowMillis)
            return false
        }

        if (state.emittedCount < MAX_NORMAL_EVENTS_PER_WINDOW) {
            state.emittedCount += 1
            return true
        }

        markSuppressed(state, nowMillis)
        return false
    }

    @Synchronized
    fun drainAggregates(nowMillis: Long): List<DebugBundleSuppressionAggregate> {
        val aggregates = mutableListOf<DebugBundleSuppressionAggregate>()
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val (key, state) = iterator.next()
            if (
                state.pendingSuppressedCount == 0 ||
                state.pendingFirstSeenAtMillis == null ||
                state.pendingLastSeenAtMillis == null
            ) {
                continue
            }

            if (
                state.suppressionMode &&
                state.lastAggregateEmittedAtMillis?.let { nowMillis - it < LOOP_CHECKPOINT_MS } == true
            ) {
                continue
            }

            val firstSeenAtMillis = state.pendingFirstSeenAtMillis ?: continue
            val lastSeenAtMillis = state.pendingLastSeenAtMillis ?: continue

            aggregates += DebugBundleSuppressionAggregate(
                sourceKey = key,
                fingerprint = fnv1aFingerprint(key),
                suppressedCount = state.pendingSuppressedCount,
                firstSeenIso = java.time.Instant.ofEpochMilli(firstSeenAtMillis).toString(),
                lastSeenIso = java.time.Instant.ofEpochMilli(lastSeenAtMillis).toString(),
                windowSeconds = (DUPLICATE_WINDOW_MS / 1_000L).toInt(),
            )

            state.pendingSuppressedCount = 0
            state.pendingFirstSeenAtMillis = null
            state.pendingLastSeenAtMillis = null
            state.lastAggregateEmittedAtMillis = nowMillis

            if (!state.suppressionMode && nowMillis - state.lastSeenAtMillis >= LOOP_RESET_AFTER_MS) {
                iterator.remove()
            }
        }
        return aggregates
    }

    private fun markSuppressed(state: SuppressionState, nowMillis: Long) {
        if (state.pendingSuppressedCount == 0) {
            state.pendingFirstSeenAtMillis = state.windowStartedAtMillis
        }
        state.pendingSuppressedCount += 1
        state.pendingLastSeenAtMillis = nowMillis
    }

    private fun fnv1aFingerprint(value: String): String {
        var hash = 0x811c9dc5.toInt()
        value.forEach { character ->
            hash = hash xor character.code
            hash *= 0x01000193
        }
        return (hash.toUInt().toString(16)).padStart(8, '0')
    }

    private class SuppressionState(nowMillis: Long) {
        var windowStartedAtMillis: Long = nowMillis
        var emittedCount: Int = 0
        var pendingSuppressedCount: Int = 0
        var pendingFirstSeenAtMillis: Long? = null
        var pendingLastSeenAtMillis: Long? = null
        var lastAggregateEmittedAtMillis: Long? = null
        var loopWindowStartedAtMillis: Long = nowMillis
        var loopHitCount: Int = 0
        var suppressionMode: Boolean = false
        var lastSeenAtMillis: Long = nowMillis
    }
}
