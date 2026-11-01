package com.debugbundle.android

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** Opaque scope identifiers are per operation or page, never resource or user identifiers. */
data class DebugBundleEvidenceScope @JvmOverloads constructor(val kind: String = "operation", val operationId: String? = null, val pageId: String? = null) {
    internal fun wire(): kotlinx.serialization.json.JsonObject? {
        val id = when (kind) { "operation" -> operationId; "page" -> pageId; else -> return null }
        if (!DebugBundleEvidenceWire.freshId(id)) return null
        return kotlinx.serialization.json.JsonObject(mapOf("kind" to kotlinx.serialization.json.JsonPrimitive(kind),
            (if (kind == "page") "page_id" else "operation_id") to kotlinx.serialization.json.JsonPrimitive(id)))
    }
}

class DebugBundleOperationScope internal constructor(internal val lease: DebugBundleOperationLease?) : AutoCloseable {
    val operationId: String? get() = lease?.id
    val evidenceScope: DebugBundleEvidenceScope? get() = operationId?.let { DebugBundleEvidenceScope(operationId = it) }
    override fun close() { lease?.close() }
}

internal class DebugBundleOperationLease(val owner: DebugBundleOperations, val previous: DebugBundleOperationLease?) {
    val id: String = UUID.randomUUID().toString()
    val ended = AtomicBoolean()
    fun active(): Boolean {
        if (owner.closed.get()) return false
        var current: DebugBundleOperationLease? = this; var depth = 0
        while (current != null) { if (++depth > 32 || current.ended.get()) return false; current = current.previous }
        return true
    }
    fun close() {
        ended.set(true)
        if (owner.storage.get() === this) owner.storage.set(previous?.takeIf { it.active() })
    }
}

internal class DebugBundleOperations {
    val closed = AtomicBoolean()
    val storage = ThreadLocal<DebugBundleOperationLease?>()
    fun current(): DebugBundleEvidenceScope? = storage.get()?.takeIf { it.active() }?.let { DebugBundleEvidenceScope(operationId = it.id) }
    fun begin(): DebugBundleOperationScope {
        if (closed.get()) return DebugBundleOperationScope(null)
        val parent = storage.get()?.takeIf { it.active() }
        var previous = parent; var depth = 0
        while (previous != null) { if (++depth >= 32) return DebugBundleOperationScope(null); previous = previous.previous }
        val lease = DebugBundleOperationLease(this, parent); storage.set(lease); return DebugBundleOperationScope(lease)
    }
    fun <T> run(callback: () -> T): T { val scope = begin(); try { return callback() } finally { scope.close() } }
    suspend fun <T> runSuspending(callback: suspend () -> T): T {
        val scope = begin()
        try { return withContext(storage.asContextElement(scope.lease)) { callback() } } finally { scope.close() }
    }
    fun close() { closed.set(true); storage.remove() }
}
