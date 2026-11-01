package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Worker-local snapshot. No application Throwable escapes this invocation. */
internal object DebugBundleExceptionEvidence {
    fun capture(error: Throwable, message: String, frames: Array<StackTraceElement>, redactor: DebugBundleRedactor): JsonObject? = runCatching {
        val snapshot = Snapshot(redactor)
        snapshot.add(error, 0, message, frames)
        val graph = JsonObject(linkedMapOf(
            "version" to JsonPrimitive(1), "runtime" to JsonPrimitive("android"), "root_id" to JsonPrimitive(0),
            "nodes" to JsonArray(snapshot.nodes.map(::JsonObject)), "truncated" to JsonPrimitive(snapshot.truncated),
            "unavailable_reason" to JsonNull,
        ))
        (redactor.sanitize(graph) as? JsonObject)?.takeIf { DebugBundleAcceptingJsonSize.bytes(it) <= 31_000 }
    }.getOrNull()

    private class Snapshot(private val redactor: DebugBundleRedactor) {
        val nodes = ArrayList<MutableMap<String, JsonElement>>()
        private val ids = IdentityHashMap<Throwable, Int>()
        private val active = IdentityHashMap<Throwable, Boolean>()
        // Reserve structural bytes for all eight nodes and their bounded edges.
        private var remaining = 22_000
        var truncated = false

        private fun text(value: String, maximum: Int): String {
            val clipped = clip(value, minOf(maximum, remaining))
            if (clipped != value) truncated = true
            remaining -= clipped.toByteArray(Charsets.UTF_8).size
            return (redactor.sanitize(JsonPrimitive(clipped)) as JsonPrimitive).content
        }

        fun add(error: Throwable, depth: Int, rootMessage: String? = null, rootFrames: Array<StackTraceElement>? = null): Int? {
            if (depth > 4 || active.containsKey(error)) { truncated = true; return null }
            ids[error]?.let {
                if (fits(it, depth, hashSetOf())) return it
                truncated = true; return null
            }
            if (nodes.size >= 8) { truncated = true; return null }
            val id = nodes.size
            ids[error] = id; active[error] = true
            val message = rootMessage ?: runCatching { error.message ?: error.javaClass.simpleName }.getOrElse { truncated = true; "Unavailable" }
            val source = rootFrames ?: runCatching { error.stackTrace }.getOrElse { truncated = true; emptyArray() }
            val node = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id),
                "name" to JsonPrimitive(text(error.javaClass.name, 256)), "message" to JsonPrimitive(text(message, 2048)),
                "stack" to JsonNull, "trace_origin" to JsonPrimitive(if (source.isEmpty()) "unavailable" else "throw"),
                "parsing_status" to JsonPrimitive(if (source.isEmpty()) "unavailable" else "parsed"),
                "frames" to JsonArray(emptyList()), "relationships" to JsonArray(emptyList()))
            nodes.add(node)
            val frames = ArrayList<JsonElement>()
            for (index in 0 until minOf(source.size, 32)) {
                val frame = source[index]
                val function = clip(frame.className + "." + frame.methodName, 256)
                val file = frame.fileName?.let { clip(it, 2048) }
                val item = JsonObject(linkedMapOf(
                    "function" to JsonPrimitive(function), "file" to (file?.let(::JsonPrimitive) ?: JsonNull),
                    "line" to (frame.lineNumber.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull), "column" to JsonNull,
                    "classification" to JsonPrimitive(when {
                        function.startsWith("com.debugbundle.") -> "sdk"
                        listOf("java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.", "dalvik.").any(function::startsWith) -> "framework"
                        else -> "unknown"
                    }),
                ))
                val safe = redactor.sanitize(item)
                val bytes = DebugBundleAcceptingJsonSize.bytes(safe)
                if (bytes > remaining) { truncated = true; break }
                remaining -= bytes; frames.add(safe)
            }
            if (source.size > frames.size) { truncated = true; node["parsing_status"] = JsonPrimitive("truncated") }
            node["frames"] = JsonArray(frames)
            val edges = ArrayList<JsonElement>()
            fun edge(child: Throwable?, kind: String) {
                if (child == null) return
                if (edges.size >= 5) { truncated = true; return }
                val target = add(child, depth + 1) ?: return
                val relation = JsonObject(mapOf("kind" to JsonPrimitive(kind), "target_id" to JsonPrimitive(target)))
                if (relation !in edges) edges.add(relation)
            }
            edge(runCatching { error.cause }.getOrElse { truncated = true; null }, "cause")
            val suppressed = runCatching { error.suppressed }.getOrElse { truncated = true; emptyArray() }
            for (index in 0 until minOf(suppressed.size, 5)) edge(suppressed[index], "suppressed")
            if (suppressed.size > 5) truncated = true
            node["relationships"] = JsonArray(edges)
            active.remove(error)
            return id
        }

        private fun fits(id: Int, depth: Int, path: MutableSet<Int>): Boolean {
            if (depth > 4 || !path.add(id)) return false
            for (edge in nodes[id].getValue("relationships") as JsonArray) {
                val target = ((edge as JsonObject).getValue("target_id") as JsonPrimitive).content.toInt()
                if (!fits(target, depth + 1, path)) return false
            }
            path.remove(id); return true
        }
    }

    internal fun clip(value: String, maximum: Int): String {
        var bytes = 0; var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val width = when { point < 128 -> 1; point < 2048 -> 2; point < 65536 -> 3; else -> 4 }
            if (bytes + width > maximum) break
            bytes += width; index += Character.charCount(point)
        }
        return value.substring(0, index)
    }
}
