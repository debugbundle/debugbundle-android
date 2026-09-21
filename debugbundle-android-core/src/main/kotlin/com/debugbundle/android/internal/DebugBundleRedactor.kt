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
            return JsonPrimitive(value)
        }
        if (value is Boolean) {
            return JsonPrimitive(value)
        }
        if (value is Throwable) {
            return JsonObject(
                mapOf(
                    "type" to JsonPrimitive(value::class.qualifiedName ?: "Throwable"),
                    "message" to JsonPrimitive(value.message?.let { if (it.toByteArray().size > maxStringLength) "[REDACTED]" else it } ?: ""),
                    "stack_trace" to JsonArray(
                        value.stackTrace
                            .take(maxCollectionEntries)
                            .map { JsonPrimitive(it.toString().let { text -> if (text.toByteArray().size > maxStringLength) "[REDACTED]" else text }) },
                    ),
                ),
            )
        }
        if (visited.put(value, true) != null) {
            return JsonPrimitive("[Circular]")
        }

        return when (value) {
            is Map<*, *> -> sanitizeMap(value, depth, visited)
            is Iterable<*> -> {
                val limited = value.take(maxCollectionEntries + 1)
                if (limited.size > maxCollectionEntries) JsonPrimitive("[REDACTED]")
                else JsonArray(limited.map { sanitizeValue(it, depth + 1, visited) })
            }
            is Array<*> -> {
                if (value.size > maxCollectionEntries) JsonPrimitive("[REDACTED]")
                else JsonArray(value.map { sanitizeValue(it, depth + 1, visited) })
            }
            else -> JsonPrimitive(value.toString().let { if (it.toByteArray().size > maxStringLength) "[REDACTED]" else it })
        }
    }

    private fun sanitizeMap(
        value: Map<*, *>,
        depth: Int,
        visited: IdentityHashMap<Any, Boolean>,
    ): JsonObject {
        val content = LinkedHashMap<String, JsonElement>()
        if (value.size > maxCollectionEntries) return JsonObject(mapOf("_redacted" to JsonPrimitive("[REDACTED]")))
        value.entries.forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString() ?: "null"
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
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    private fun normalizeKey(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
