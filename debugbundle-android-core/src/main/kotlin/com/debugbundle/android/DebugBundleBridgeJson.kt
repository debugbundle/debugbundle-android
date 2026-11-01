package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleNativeValues
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Caller-side bridge ownership accepts plain native JSON values, never foreign accessors. */
internal object DebugBundleBridgeJson {
    private class Budget(var nodes: Int = 4096, var bytes: Int = 256 * 1024)
    fun snapshot(value: Map<String, Any?>): JsonObject? = runCatching {
        copy(value, Budget(), IdentityHashMap(), 0) as? JsonObject
    }.getOrNull()
    private fun text(value: String, budget: Budget) {
        if (value.length > budget.bytes) throw EvidenceValidationUnavailable()
        budget.bytes -= value.toByteArray(Charsets.UTF_8).size
        if (budget.bytes < 0) throw EvidenceValidationUnavailable()
    }
    private fun copy(value: Any?, budget: Budget, active: IdentityHashMap<Any, Boolean>, depth: Int): JsonElement {
        if (--budget.nodes < 0 || depth > 16) throw EvidenceValidationUnavailable()
        if (value == null) return JsonNull
        when (value) {
            is String -> { text(value, budget); return JsonPrimitive(value) }
            is Boolean -> return JsonPrimitive(value)
            is Number -> { if (!DebugBundleNativeValues.number(value)) throw EvidenceValidationUnavailable(); return JsonPrimitive(value).also { text(it.content, budget) } }
            is JsonPrimitive -> { text(value.content, budget); return value }
        }
        if (active.put(value, true) != null) throw EvidenceValidationUnavailable()
        return try {
            when (value) {
                is Map<*, *> -> {
                    if (!DebugBundleNativeValues.map(value) || value.size > 256) throw EvidenceValidationUnavailable()
                    JsonObject(linkedMapOf<String, JsonElement>().apply {
                        for ((key, item) in value) {
                            if (key !is String) throw EvidenceValidationUnavailable()
                            text(key, budget); put(key, copy(item, budget, active, depth + 1))
                        }
                    })
                }
                is Iterable<*> -> {
                    if (!DebugBundleNativeValues.collection(value)) throw EvidenceValidationUnavailable()
                    val items = ArrayList<JsonElement>()
                    for (item in value) {
                        if (items.size >= 256) throw EvidenceValidationUnavailable()
                        items.add(copy(item, budget, active, depth + 1))
                    }
                    JsonArray(items)
                }
                is Array<*> -> {
                    if (value.size > 256) throw EvidenceValidationUnavailable()
                    JsonArray(value.map { copy(it, budget, active, depth + 1) })
                }
                // JsonObject/JsonArray may delegate to application-owned containers.
                else -> throw EvidenceValidationUnavailable()
            }
        } finally { active.remove(value) }
    }
}
