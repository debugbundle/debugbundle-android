package com.debugbundle.android.internal

import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class DebugBundleRedactor(
    redactFields: Set<String>,
    private val maxDepth: Int = 6,
    private val maxStringLength: Int = 2048,
    private val maxCollectionEntries: Int = 50,
) {
    private val sensitiveSegments = redactFields.map(::normalizeKey).toSet()

    fun sanitize(value: Any?): JsonElement {
        return sanitizeValue(value, depth = 0, visited = IdentityHashMap())
    }

    private fun sanitizeValue(value: Any?, depth: Int, visited: IdentityHashMap<Any, Boolean>): JsonElement {
        if (value == null) {
            return JsonNull
        }
        if (depth >= maxDepth) {
            return JsonPrimitive("[Truncated]")
        }
        if (value is String) {
            return JsonPrimitive(value.take(maxStringLength))
        }
        if (value is Number) {
            return JsonPrimitive(value)
        }
        if (value is Boolean) {
            return JsonPrimitive(value)
        }
        if (value is Throwable) {
            return JsonObject(
                mapOf(
                    "type" to JsonPrimitive(value::class.qualifiedName ?: "Throwable"),
                    "message" to JsonPrimitive(value.message?.take(maxStringLength) ?: ""),
                    "stack_trace" to JsonArray(
                        value.stackTrace
                            .take(maxCollectionEntries)
                            .map { JsonPrimitive(it.toString().take(maxStringLength)) },
                    ),
                ),
            )
        }
        if (visited.put(value, true) != null) {
            return JsonPrimitive("[Circular]")
        }

        return when (value) {
            is Map<*, *> -> sanitizeMap(value, depth, visited)
            is Iterable<*> -> JsonArray(
                value.take(maxCollectionEntries).map { sanitizeValue(it, depth + 1, visited) },
            )
            is Array<*> -> JsonArray(
                value.take(maxCollectionEntries).map { sanitizeValue(it, depth + 1, visited) },
            )
            else -> JsonPrimitive(value.toString().take(maxStringLength))
        }
    }

    private fun sanitizeMap(
        value: Map<*, *>,
        depth: Int,
        visited: IdentityHashMap<Any, Boolean>,
    ): JsonObject {
        val content = LinkedHashMap<String, JsonElement>()
        value.entries.take(maxCollectionEntries).forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString() ?: "null"
            content[key] = if (shouldRedact(key)) {
                JsonPrimitive("[REDACTED]")
            } else {
                sanitizeValue(rawValue, depth + 1, visited)
            }
        }
        return JsonObject(content)
    }

    private fun shouldRedact(key: String): Boolean {
        val normalizedSegments = splitIntoSegments(key).map(::normalizeKey)
        return normalizedSegments.any(sensitiveSegments::contains)
    }

    private fun splitIntoSegments(key: String): List<String> {
        return key
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    private fun normalizeKey(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
