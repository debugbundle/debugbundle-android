package com.debugbundle.android.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DebugBundleProbeBuffer(
    private val maxLabels: Int,
    private val maxEntriesPerLabel: Int,
) {
    private data class Entry(
        val label: String,
        val data: JsonObject,
        val timestamp: String,
        val activationId: String?,
    )

    private val entries = LinkedHashMap<String, ArrayDeque<Entry>>()

    @Synchronized
    fun add(label: String, value: JsonObject, timestamp: String, activationId: String? = null) {
        if (!entries.containsKey(label) && entries.size >= maxLabels) {
            return
        }
        val bucket = entries.getOrPut(label) { ArrayDeque() }
        bucket.addLast(Entry(label = label, data = value, timestamp = timestamp, activationId = activationId))
        while (bucket.size > maxEntriesPerLabel) {
            bucket.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): JsonObject {
        val items = entries.values.flatten().map { entry ->
            buildJsonObject {
                put("label", entry.label)
                put("data", entry.data)
                put("timestamp", entry.timestamp)
                put("activation_id", entry.activationId?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        return JsonObject(mapOf("version" to JsonPrimitive(1), "items" to JsonArray(items)))
    }
}
