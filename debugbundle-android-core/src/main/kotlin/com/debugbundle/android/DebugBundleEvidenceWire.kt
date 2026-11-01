package com.debugbundle.android

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Semantic refinements after closed schema checking, shared by native and bridge events. */
internal object DebugBundleEvidenceWire {
    private val fresh = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-4[a-fA-F0-9]{3}-[89aAbB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}")
    private val whitespace = Regex("[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]*")
    fun accepts(value: JsonElement): Boolean = try { DebugBundleCanonicalEventSchema.acceptsEvent(value) && semantic(value as JsonObject) } catch (error: EvidenceValidationUnavailable) { throw error } catch (_: RuntimeException) { false }
    fun freshId(value: String?): Boolean = value != null && value.length == 36 && fresh.matches(value)
    private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    private fun objectValue(value: JsonElement?): JsonObject = value as? JsonObject ?: JsonObject(emptyMap())
    private fun boundedText(value: JsonElement?, maximum: Int): Boolean = value == JsonNull || text(value)?.let { DebugBundleAcceptingJsonSize.utf8Within(it, maximum) } == true
    fun scope(value: JsonElement?): Boolean {
        val item = objectValue(value); val key = when (text(item["kind"])) { "page" -> "page_id"; "operation" -> "operation_id"; else -> return false }
        return item.keys == setOf("kind", key) && freshId(text(item[key]))
    }
    fun reference(value: JsonElement?): Boolean {
        val item = objectValue(value)
        return item.keys == setOf("version", "failure_ref", "scope") && number(item["version"]) == 1.0 && freshId(text(item["failure_ref"])) && scope(item["scope"])
    }
    private fun semantic(event: JsonObject): Boolean {
        val payload = event.getValue("payload") as JsonObject; val type = text(event["event_type"])
        if (type == "frontend_exception") {
            val mobile = text(event["sdk_name"]) in setOf("@debugbundle/sdk-android", "@debugbundle/sdk-swift", "@debugbundle/sdk-react-native")
            if (payload[if (mobile) "device" else "browser"] !is JsonObject) return false
        }
        if (type == "diagnostic_context") {
            if (!freshId(text(payload["failure_ref"])) || !scope(payload["scope"])) return false
            if (text(payload["kind"]) == "recovery") {
                if (!freshId(text(payload["attempt_id"])) || payload.containsKey("operation") && !boundedText(payload["operation"], 64)) return false
            } else if (!resources(payload.getValue("observations"))) return false
            if (text(objectValue(payload["scope"])["kind"]) == "page") {
                val session = text(objectValue(event["correlation"])["session_id"]) ?: return false
                if (whitespace.matches(session)) return false
            }
            return true
        }
        val additions = linkedMapOf<String, JsonElement>()
        for (field in listOf("exception_evidence", "execution", "resource_observations")) {
            val item = payload[field] ?: continue
            if (!(type == "backend_exception" && field != "resource_observations" || type == "frontend_exception" && field != "execution")) return false
            if (field == "exception_evidence" && !graph(item) || field == "execution" && !boundedText(objectValue(item)["name"], 128) || field == "resource_observations" && !resources(item)) return false
            additions[field] = item
        }
        if (additions.isEmpty()) return true
        objectValue(event["context"])["evidence_reference"]?.takeIf(::reference)?.let { additions["evidence_reference"] = it }
        return DebugBundleAcceptingJsonSize.bytes(JsonObject(additions)) <= 32768
    }
    private fun graph(value: JsonElement): Boolean {
        val graph = value as JsonObject; val nodes = graph.getValue("nodes") as JsonArray; val adjacency = HashMap<Double, MutableList<Double>>()
        for (item in nodes) {
            val node = item as JsonObject; val id = number(node["id"])!!
            if (adjacency.containsKey(id) || !boundedText(node["name"], 256) || !boundedText(node["message"], 2048) || !boundedText(node["stack"], 16384)) return false
            for (frame in node.getValue("frames") as JsonArray) {
                val location = frame as JsonObject
                if (!boundedText(location["function"], 256) || !boundedText(location["file"], 2048)) return false
            }
            adjacency[id] = mutableListOf()
        }
        for (item in nodes) {
            val node = item as JsonObject; val edges = HashSet<String>(); var causal = 0
            for (edge in node.getValue("relationships") as JsonArray) {
                val relation = edge as JsonObject; val target = number(relation["target_id"])!!; val kind = text(relation["kind"])
                if (!adjacency.containsKey(target) || !edges.add("$kind:$target")) return false
                if (kind in setOf("cause", "context")) causal++
                adjacency.getValue(number(node["id"])!!).add(target)
            }
            if (causal > 1) return false
        }
        val reached = HashSet<Double>(); val active = HashSet<Double>()
        fun visit(id: Double, depth: Int): Boolean {
            if (depth > 4 || !adjacency.containsKey(id) || !active.add(id)) return false
            reached.add(id)
            for (child in adjacency.getValue(id)) if (!visit(child, depth + 1)) return false
            active.remove(id); return true
        }
        return visit(number(graph["root_id"])!!, 0) && reached.size == nodes.size && DebugBundleAcceptingJsonSize.bytes(value) <= 32768
    }
    private fun resources(value: JsonElement): Boolean {
        val objectValue = value as JsonObject
        for (item in objectValue.getValue("items") as JsonArray) {
            val observation = item as JsonObject
            if (text(observation["kind"]) == "csp" && text(observation["effective_directive"])?.matches(Regex("[a-z-]{1,64}")) != true) return false
            val source = observation.getValue("source_url")
            if (source != JsonNull && !url(text(source) ?: return false)) return false
        }
        return DebugBundleAcceptingJsonSize.bytes(value) <= 32768
    }
    private fun url(value: String): Boolean {
        if (!DebugBundleAcceptingJsonSize.utf8Within(value, 2048) || value.isEmpty() || value.startsWith("//") || value.any { it.code <= 32 || it.code == 127 || it in "\\?#" }) return false
        if (value.startsWith('/')) return true
        val start = when { value.startsWith("https://", true) -> 8; value.startsWith("http://", true) -> 7; else -> return false }
        val slash = value.indexOf('/', start).let { if (it < 0) value.length else it }
        return runCatching {
            val authority = URI(value.substring(0, slash) + "/").parseServerAuthority()
            !authority.host.isNullOrEmpty() && authority.port <= 65535 && authority.rawUserInfo in setOf(null, "", ":")
        }.getOrDefault(false)
    }
}
