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
    private val mandatoryPrivacy = TelemetryPrivacy(redactFields)

    fun sanitize(value: Any?): JsonElement {
        return mandatoryPrivacy.protect(sanitizeValue(value, depth = 0, visited = IdentityHashMap()))
    }

    private fun sanitizeValue(value: Any?, depth: Int, visited: IdentityHashMap<Any, Boolean>): JsonElement {
        if (value == null) {
            return JsonNull
        }
        if (depth >= maxDepth) {
            return JsonPrimitive("[REDACTED]")
        }
        if (value is JsonElement) return value
        if (value is String) {
            return JsonPrimitive(if (value.length > maxStringLength || value.toByteArray().size > maxStringLength) "[REDACTED]" else value)
        }
        if (value is Number) {
            return if (DebugBundleNativeValues.number(value)) JsonPrimitive(value) else JsonPrimitive("[Unsupported value]")
        }
        if (value is Boolean) {
            return JsonPrimitive(value)
        }
        if (value is Throwable) {
            // Caller-side projection never invokes Throwable getters, toString, or synchronized accessors.
            return JsonObject(mapOf(
                "type" to JsonPrimitive(value.javaClass.name),
                "message" to JsonPrimitive("Throwable details pending background inspection"),
                "stack_trace" to JsonArray(emptyList()),
            ))
        }
        if (visited.put(value, true) != null) {
            return JsonPrimitive("[Circular]")
        }

        // Cycle membership follows the active path; repeated empty singletons
        // and shared ordinary containers are values, not recursive references.
        return try { when (value) {
            is Map<*, *> -> if (DebugBundleNativeValues.map(value)) sanitizeMap(value, depth, visited) else JsonPrimitive("[Unsupported value]")
            is Iterable<*> -> {
                if (!DebugBundleNativeValues.collection(value)) return JsonPrimitive("[Unsupported value]")
                val limited = value.take(maxCollectionEntries + 1)
                if (limited.size > maxCollectionEntries) JsonPrimitive("[REDACTED]")
                else JsonArray(limited.map { sanitizeValue(it, depth + 1, visited) })
            }
            is Array<*> -> {
                if (value.size > maxCollectionEntries) JsonPrimitive("[REDACTED]")
                else JsonArray(value.map { sanitizeValue(it, depth + 1, visited) })
            }
            else -> JsonPrimitive("[Unsupported value]")
        } } finally { visited.remove(value) }
    }

    private fun sanitizeMap(
        value: Map<*, *>,
        depth: Int,
        visited: IdentityHashMap<Any, Boolean>,
    ): JsonObject {
        val content = LinkedHashMap<String, JsonElement>()
        if (value.size > maxCollectionEntries) return JsonObject(mapOf("_redacted" to JsonPrimitive("[REDACTED]")))
        value.entries.forEach { (rawKey, rawValue) ->
            val key = rawKey as? String ?: return@forEach
            if (key.length > 128) return@forEach
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
            .replace(CAMEL_BOUNDARY, "$1_$2")
            .split(KEY_SEPARATOR)
            .filter { it.isNotBlank() }
    }

    private fun normalizeKey(value: String): String {
        return value.lowercase().replace(NON_CANONICAL, "")
    }

    private companion object {
        // Fixed immutable patterns are shared; caller-owned field sets remain per client.
        val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        val KEY_SEPARATOR = Regex("[^A-Za-z0-9]+")
        val NON_CANONICAL = Regex("[^a-z0-9]")
    }
}
