package com.debugbundle.android

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Counts metadata plus pending envelopes; ACK/drop releases bytes, never allowances. */
internal class DebugBundleDiagnosticBudget {
    private data class Window(val start: Long, var failureAt: Long? = null, var failureId: String? = null,
        var ambiguous: Boolean = false, val attempts: MutableSet<String> = hashSetOf(), var observations: Int = 0,
        var pending: Int = 0, var resource: Boolean = false) {
        fun cost(key: String): Int = key.toByteArray().size + 256 + (failureId?.length ?: 0) + attempts.size * 40
        fun snapshot() = copy(attempts = attempts.toMutableSet())
    }
    private data class Receipt(val key: String, val kind: String, val attempt: String, val bytes: Int, var committed: Boolean = false) {
        fun cost(id: String): Int = bytes + key.toByteArray().size + id.length + 96
    }
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val windows = LinkedHashMap<String, Window>()
    private val pending = LinkedHashMap<String, Receipt>()
    private fun text(item: JsonObject, key: String): String = (item[key] as? JsonPrimitive)?.content ?: ""
    private fun key(event: DebugBundleEnvelope, reference: JsonObject = event.payload): String? {
        val scope = reference["scope"] as? JsonObject ?: return null
        if (!DebugBundleEvidenceWire.freshId(text(reference, "failure_ref")) || !DebugBundleEvidenceWire.scope(scope)) return null
        return JsonArray(listOf(JsonPrimitive(text(reference, "failure_ref")), scope, JsonPrimitive(event.service.name),
            JsonPrimitive(event.service.environment), event.correlation?.traceId?.let(::JsonPrimitive) ?: JsonNull,
            event.correlation?.sessionId?.let(::JsonPrimitive) ?: JsonNull)).toString()
    }
    private fun at(event: DebugBundleEnvelope): Long? = runCatching { Instant.parse(event.occurredAt).toEpochMilli() }.getOrNull()
    private fun bytes(event: DebugBundleEnvelope): Int = json.encodeToString(event).toByteArray().size
    private fun size(): Int = windows.entries.sumOf { it.value.cost(it.key) } + pending.entries.sumOf { it.value.cost(it.key) }
    private fun prune(now: Long) { windows.entries.removeAll { it.value.pending == 0 && now >= it.value.start + WINDOW } }

    @Synchronized fun register(event: DebugBundleEnvelope): Boolean {
        val reference = event.context?.get("evidence_reference") as? JsonObject ?: return false
        if (!DebugBundleEvidenceWire.reference(reference)) return false
        val now = at(event) ?: return false; prune(now)
        val key = key(event, reference) ?: return false
        val previous = windows[key]
        if (previous?.failureId != null && previous.failureId != event.eventId) { previous.ambiguous = true; return false }
        if (previous == null && windows.size >= 50) return false
        val window = previous?.snapshot() ?: Window(now)
        window.failureId = event.eventId; if (window.failureAt == null) window.failureAt = now
        if (size() - (previous?.cost(key) ?: 0) + window.cost(key) > MAX_BYTES) return false
        windows[key] = window; return !window.ambiguous
    }
    @Synchronized fun reserve(event: DebugBundleEnvelope): Boolean {
        if (event.eventType != "diagnostic_context" || pending.containsKey(event.eventId)) return false
        val bytes = bytes(event); if (bytes > MAX_BYTES) return false
        val now = at(event) ?: return false; prune(now)
        val key = key(event) ?: return false; val previous = windows[key]
        if (previous == null && windows.size >= 50) return false
        val window = previous?.snapshot() ?: Window(now)
        val failureAt = window.failureAt ?: window.start
        if (window.ambiguous || now < failureAt || now < window.start || now > failureAt + WINDOW || window.observations >= 20) return false
        val kind = text(event.payload, "kind"); val attempt = text(event.payload, "attempt_id")
        when (kind) {
            "recovery" -> {
                if (!DebugBundleEvidenceWire.freshId(attempt) || attempt !in window.attempts && window.attempts.size >= 5) return false
                window.attempts.add(attempt)
            }
            "resource_observation" -> {
                if (window.resource || window.failureId != text(event.payload, "source_event_id") || window.failureAt == null || now > failureAt + 30_000) return false
                window.resource = true
            }
            else -> return false
        }
        window.observations++; window.pending++
        val receipt = Receipt(key, kind, attempt, bytes)
        if (size() - (previous?.cost(key) ?: 0) + window.cost(key) + receipt.cost(event.eventId) > MAX_BYTES) return false
        windows[key] = window; pending[event.eventId] = receipt; return true
    }
    @Synchronized fun replace(originalId: String?, event: DebugBundleEnvelope): Boolean {
        val original = pending[originalId]
        val key = key(event) ?: run { release(originalId); return false }; val kind = text(event.payload, "kind"); val attempt = text(event.payload, "attempt_id")
        if (original != null && original.key == key && original.kind == kind && original.attempt == attempt) {
            val receipt = Receipt(key, kind, attempt, bytes(event), original.committed)
            val window = windows[key]
            if (receipt.bytes > MAX_BYTES || window == null || window.ambiguous || event.eventId != originalId && pending.containsKey(event.eventId) ||
                kind == "resource_observation" && window.failureId != text(event.payload, "source_event_id") ||
                size() - original.cost(requireNotNull(originalId)) + receipt.cost(event.eventId) > MAX_BYTES) {
                release(originalId); return false
            }
            pending.remove(originalId); pending[event.eventId] = receipt; return true
        }
        release(originalId); return reserve(event)
    }
    @Synchronized fun eligible(event: DebugBundleEnvelope): Boolean {
        val receipt = pending[event.eventId] ?: return false; val key = key(event) ?: return false
        val window = windows[key] ?: return false
        return !window.ambiguous && receipt.key == key && receipt.kind == text(event.payload, "kind") && receipt.attempt == text(event.payload, "attempt_id")
    }
    @Synchronized fun release(id: String?) { pending.remove(id)?.let { receipt -> windows[receipt.key]?.let { it.pending = (it.pending - 1).coerceAtLeast(0) } } }
    @Synchronized fun restore(events: List<QueuedDebugBundleEvent>) {
        events.filter { it.envelope.eventType in setOf("backend_exception", "frontend_exception") }.forEach { register(it.envelope) }
        events.filter { it.envelope.eventType == "diagnostic_context" }.forEach { if (!pending.containsKey(it.envelope.eventId)) reserve(it.envelope) }
        synchronize(events)
    }
    @Synchronized fun persisted(prepared: List<DebugBundleEnvelope>, retained: List<QueuedDebugBundleEvent>) {
        val ids = retained.mapTo(hashSetOf()) { it.envelope.eventId }
        prepared.filter { it.eventType == "diagnostic_context" }.forEach { if (it.eventId !in ids) release(it.eventId) }
        synchronize(retained)
    }
    @Synchronized fun synchronize(events: List<QueuedDebugBundleEvent>) {
        val ids = events.mapTo(hashSetOf()) { it.envelope.eventId }
        pending.filter { it.value.committed && it.key !in ids }.keys.toList().forEach(::release)
        ids.forEach { pending[it]?.committed = true }
    }
    @Synchronized fun clear() { pending.clear(); windows.clear() }
    companion object { private const val WINDOW = 300_000L; private const val MAX_BYTES = 65_536 }
}
