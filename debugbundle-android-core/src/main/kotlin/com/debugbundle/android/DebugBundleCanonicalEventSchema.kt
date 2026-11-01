package com.debugbundle.android

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Closed vendored Draft 7. Unknown schema keywords and unavailable validation fail closed. */
internal class DebugBundleCanonicalEventSchema(private val root: JsonElement) {
    private val references = HashMap<String, JsonElement>()
    private val patterns = HashMap<String, Regex>()
    init { compile(root) }
    private class Budget(var steps: Int = 100000)
    fun accepts(value: JsonElement): Boolean = check(root, value, Budget(), 0)
    private fun compile(schema: JsonElement) {
        if (schema is JsonPrimitive && !schema.isString && schema.content in setOf("true", "false")) return
        val node = schema as? JsonObject ?: throw EvidenceValidationUnavailable()
        require(node.keys.all(KEYWORDS::contains))
        require(alternatives(node["type"]).all { text(it) in TYPES })
        require(node["format"] == null || text(node["format"]) in setOf("uuid", "date-time"))
        node["pattern"]?.let { patterns[text(it)!!] = Regex(text(it)!!) }
        node["\$ref"]?.let { val ref = text(it) ?: throw EvidenceValidationUnavailable(); if (!references.containsKey(ref)) { val target = resolve(ref); references[ref] = target; compile(target) } }
        (node["properties"] as? JsonObject)?.values?.forEach(::compile)
        (node["anyOf"] as? JsonArray)?.forEach(::compile)
        listOf("items", "additionalProperties").forEach { node[it]?.let(::compile) }
    }
    private fun resolve(ref: String): JsonElement {
        references[ref]?.let { return it }; require(ref.startsWith("#/"))
        var target = root
        for (part in ref.drop(2).split('/')) {
            val key = part.replace("~1", "/").replace("~0", "~")
            target = when (val current = target) { is JsonObject -> current[key]; is JsonArray -> key.toIntOrNull()?.let(current::getOrNull); else -> null } ?: throw EvidenceValidationUnavailable()
        }
        return target
    }
    private fun check(schema: JsonElement, value: JsonElement, budget: Budget, depth: Int): Boolean {
        if (--budget.steps < 0 || depth > 64) throw EvidenceValidationUnavailable()
        if (schema is JsonPrimitive) return schema.content == "true"
        val node = schema as? JsonObject ?: return false
        node["\$ref"]?.let { return check(resolve(text(it)!!), value, budget, depth + 1) }
        (node["anyOf"] as? JsonArray)?.let { if (it.none { child -> check(child, value, budget, depth + 1) }) return false }
        if (node.containsKey("type") && alternatives(node["type"]).none { type(value, text(it)) }) return false
        if (node.containsKey("const") && !same(value, node.getValue("const"))) return false
        (node["enum"] as? JsonArray)?.let { if (it.none { choice -> same(value, choice) }) return false }
        if (value is JsonObject) {
            (node["required"] as? JsonArray)?.let { if (it.any { field -> text(field) !in value }) return false }
            val fields = node["properties"] as? JsonObject
            for ((key, child) in value) if (!check(fields?.get(key) ?: node["additionalProperties"] ?: JsonPrimitive(true), child, budget, depth + 1)) return false
        }
        if (value is JsonArray) {
            if (numeric(node["minItems"])?.let { value.size < it } == true || numeric(node["maxItems"])?.let { value.size > it } == true) return false
            node["items"]?.let { child -> if (value.any { !check(child, it, budget, depth + 1) }) return false }
        }
        if (value is JsonPrimitive && value.isString) {
            val string = value.content
            if (numeric(node["minLength"])?.let { string.length < it } == true || numeric(node["maxLength"])?.let { string.length > it } == true) return false
            node["pattern"]?.let { if (!patterns.getValue(text(it)!!).containsMatchIn(string)) return false }
            if (text(node["format"]) == "uuid" && !UUID.matches(string) || text(node["format"]) == "date-time" && !timestamp(string)) return false
        }
        numeric(value)?.let { number ->
            if (numeric(node["minimum"])?.let { number < it } == true || numeric(node["maximum"])?.let { number > it } == true || numeric(node["exclusiveMinimum"])?.let { number <= it } == true) return false
        }
        return true
    }
    private fun type(value: JsonElement, kind: String?): Boolean = when (kind) {
        "null" -> value == JsonNull
        "boolean" -> value is JsonPrimitive && !value.isString && value.content in setOf("true", "false")
        "string" -> value is JsonPrimitive && value.isString
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "number" -> numeric(value)?.isFinite() == true
        "integer" -> numeric(value)?.let { it.isFinite() && it == kotlin.math.floor(it) } == true
        else -> false
    }
    private fun same(left: JsonElement, right: JsonElement): Boolean = if (numeric(left) != null && numeric(right) != null) numeric(left) == numeric(right) else left == right
    private fun alternatives(value: JsonElement?): List<JsonElement> = when (value) { null -> emptyList(); is JsonArray -> value; else -> listOf(value) }
    companion object {
        private val KEYWORDS = setOf("\$schema", "\$id", "\$comment", "\$ref", "type", "anyOf", "const", "enum", "properties", "required", "additionalProperties", "items", "minimum", "maximum", "exclusiveMinimum", "minLength", "maxLength", "minItems", "maxItems", "format", "pattern")
        private val TYPES = setOf("null", "boolean", "number", "integer", "string", "array", "object")
        private val UUID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        private val TIME = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?Z")
        private val engine by lazy { runCatching { DebugBundleCanonicalEventSchema(Json.parseToJsonElement(requireNotNull(DebugBundleCanonicalEventSchema::class.java.getResourceAsStream("/com/debugbundle/android/event-envelope.schema.json")).bufferedReader().use { it.readText() })) }.getOrNull() }
        fun acceptsEvent(value: JsonElement): Boolean = (engine ?: throw EvidenceValidationUnavailable()).accepts(value)
        private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun numeric(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        private fun timestamp(value: String): Boolean = TIME.matches(value) && runCatching {
            LocalDate.of(value.substring(0, 4).toInt(), value.substring(5, 7).toInt(), value.substring(8, 10).toInt())
            value.substring(11, 13).toInt() < 24 && value.substring(14, 16).toInt() < 60 && value.substring(17, 19).toInt() < 60
        }.getOrDefault(false)
    }
}
