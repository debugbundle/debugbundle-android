package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import com.debugbundle.android.internal.TelemetryPrivacy
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private const val MAX_PROTOCOL_EVENT_BYTES = 256 * 1024
private const val MAX_SAFE_PROTOCOL_INT = 9_007_199_254_740_991L

private val ALLOWED_HTML_RESOURCE_TAGS = setOf(
    "script", "link", "img", "audio", "video", "source", "iframe", "object", "embed", "input",
)
private val ALLOWED_READY_STATES = setOf("loading", "interactive", "complete")
private val ALLOWED_VISIBILITY_STATES = setOf("visible", "hidden", "prerender", "unloaded")
private val ALLOWED_REJECTION_KINDS = setOf("error", "string", "object", "null", "undefined", "unknown")
private val ALLOWED_LIMIT_KEYS = setOf("version", "exception_evidence", "execution", "resource_observations")
private val ALLOWED_LIMIT_REASONS = setOf("budget_exceeded", "unsafe", "unsupported", "unknown")
private val ALLOWED_RUNTIMES = setOf(
    "node", "browser", "python", "php", "jvm", "go", "ruby", "dotnet", "android", "swift", "react_native", "unknown",
)
private val ALLOWED_TRACE_ORIGINS = setOf("throw", "native_fatal", "capture", "runtime_reported", "unavailable", "unknown")
private val ALLOWED_PARSING_STATUSES = setOf("parsed", "partial", "unparsed", "unavailable", "truncated")
private val ALLOWED_FRAME_CLASSIFICATIONS = setOf("application", "framework", "sdk", "unknown")
private val ALLOWED_RELATIONSHIP_KINDS = setOf("cause", "context", "aggregate", "suppressed")
private val ALLOWED_EXECUTION_KINDS = setOf("request", "command", "job", "unknown")
private val ALLOWED_RECOVERY_STATES = setOf(
    "attempt_started", "attempt_completed", "attempt_failed", "recovery_observed", "failure_persisted",
)
private val ALLOWED_TIMING_AVAILABILITIES = setOf("observed", "unsupported", "no_match", "withheld", "ambiguous", "unknown")
private val ALLOWED_BROWSER_EVENT_KEYS = setOf(
    "kind", "message", "file_name", "line_number", "column_number", "target", "page", "opaque",
)
private val ALLOWED_TARGET_KEYS = setOf("tag_name", "source_url", "attributes")
private val ALLOWED_TARGET_ATTR_STRINGS = listOf("rel", "as", "type", "media", "cross_origin")
private val ALLOWED_TARGET_ATTR_BOOLS = listOf("async", "defer", "integrity_present")
private val ALLOWED_TARGET_ATTR_KEYS = (ALLOWED_TARGET_ATTR_STRINGS + ALLOWED_TARGET_ATTR_BOOLS).toSet()
private val ALLOWED_PAGE_KEYS = setOf("url", "referrer", "ready_state", "visibility_state")
private val ALLOWED_REJECTION_KEYS = setOf("kind", "name", "message", "preview")
private val ALLOWED_GRAPH_KEYS = setOf("version", "runtime", "root_id", "nodes", "truncated", "unavailable_reason")
private val ALLOWED_NODE_KEYS = setOf("id", "name", "message", "stack", "trace_origin", "parsing_status", "frames", "relationships")
private val ALLOWED_FRAME_KEYS = setOf("function", "file", "line", "column", "classification")
private val ALLOWED_EDGE_KEYS = setOf("kind", "target_id")
private val ALLOWED_EXECUTION_KEYS = setOf("version", "kind", "name", "elapsed_ms", "limit_ms")
private val ALLOWED_RESOURCE_OBS_KEYS = setOf("version", "items")
private val ALLOWED_TIMING_KEYS = setOf(
    "kind", "observed_at", "source_url", "availability", "response_status", "duration_ms", "transfer_size_bytes",
)
private val ALLOWED_CSP_KEYS = setOf(
    "kind", "observed_at", "source_url", "effective_directive", "disposition", "document_status_code",
)
private val ALLOWED_RECOVERY_KEYS = setOf(
    "version", "kind", "failure_ref", "scope", "attempt_id", "state", "operation", "status_code", "duration_ms",
)
private val ALLOWED_RESOURCE_DIAG_KEYS = setOf(
    "version", "kind", "failure_ref", "scope", "source_event_id", "observations",
)
private val ALLOWED_DEVICE_STRING_KEYS = setOf(
    "user_agent", "device_type", "language", "connection_type", "color_scheme_preference",
    "app_version", "build_number", "release_channel", "manufacturer", "model", "timezone",
    "os_name", "os_version", "locale", "network_type", "app_state", "orientation",
)
private val ALLOWED_DEVICE_BOOL_KEYS = setOf("touch_capable", "battery_charging", "jailbroken", "is_emulator", "is_low_power_mode")
private val ALLOWED_DEVICE_NUMBER_KEYS = setOf(
    "device_pixel_ratio", "api_level", "battery_level", "free_disk_bytes", "free_memory_bytes",
    "memory_total_mb", "memory_available_mb", "disk_free_mb", "screen_width", "screen_height", "screen_density",
)
private val ALLOWED_DEVICE_NESTED_KEYS = setOf("os", "screen", "viewport")
private val ALLOWED_DEVICE_KEYS = ALLOWED_DEVICE_STRING_KEYS + ALLOWED_DEVICE_BOOL_KEYS + ALLOWED_DEVICE_NUMBER_KEYS + ALLOWED_DEVICE_NESTED_KEYS
private val CSP_DIRECTIVE_PATTERN = Regex("^[a-z-]{1,64}$")

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.exactIntInRange(min: Long, max: Long): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val parsedLong = primitive.content.toLongOrNull()
    if (parsedLong != null) return parsedLong.takeIf { it in min..max }
    val num = primitive.doubleOrNull ?: return null
    if (!num.isFinite() || num % 1.0 != 0.0 || num < min.toDouble() || num > max.toDouble()) return null
    return num.toLong()
}

private fun JsonElement?.nonNegativeFinite(): Boolean {
    val primitive = this as? JsonPrimitive ?: return false
    if (primitive.isString) return false
    val num = primitive.doubleOrNull ?: return false
    return num.isFinite() && num >= 0.0
}

private fun scrubNullable(value: JsonElement?, redactor: DebugBundleRedactor, maxBytes: Int? = null): JsonElement? {
    if (value == null) return null
    if (value == JsonNull) return JsonNull
    val text = value.stringOrNull() ?: return null
    val scrubbed = redactor.sanitizeProtocolString(text) ?: return null
    if (maxBytes != null && scrubbed.toByteArray(StandardCharsets.UTF_8).size > maxBytes) return null
    return JsonPrimitive(scrubbed)
}

private fun isValidEvidenceLimits(value: JsonElement?): Boolean {
    val map = value as? JsonObject ?: return false
    if (map["version"].exactIntInRange(1, 1) != 1L || !ALLOWED_LIMIT_KEYS.containsAll(map.keys)) return false
    return map.entries.all { (key, entry) ->
        key == "version" || entry.stringOrNull() in ALLOWED_LIMIT_REASONS
    }
}

private fun sanitizeDeviceBlock(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val map = raw as? JsonObject ?: return null
    if (!ALLOWED_DEVICE_KEYS.containsAll(map.keys)) return null
    val out = LinkedHashMap<String, JsonElement>()
    for ((key, value) in map) {
        when {
            value == JsonNull -> out[key] = JsonNull
            key in ALLOWED_DEVICE_STRING_KEYS -> out[key] = scrubNullable(value, redactor) ?: return null
            key in ALLOWED_DEVICE_BOOL_KEYS -> {
                val bool = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
                out[key] = JsonPrimitive(bool)
            }
            key in ALLOWED_DEVICE_NUMBER_KEYS -> {
                if (!value.nonNegativeFinite()) return null
                out[key] = value
            }
            key == "os" -> {
                val osMap = value as? JsonObject ?: return null
                if (!setOf("name", "version").containsAll(osMap.keys)) return null
                val osOut = LinkedHashMap<String, JsonElement>()
                for ((osKey, osVal) in osMap) osOut[osKey] = scrubNullable(osVal, redactor) ?: return null
                out[key] = JsonObject(osOut)
            }
            key == "screen" || key == "viewport" -> {
                val dims = value as? JsonObject ?: return null
                if (!setOf("width", "height").containsAll(dims.keys)) return null
                for ((_, dimVal) in dims) if (dimVal.exactIntInRange(0, MAX_SAFE_PROTOCOL_INT) == null) return null
                out[key] = dims
            }
            else -> return null
        }
    }
    return JsonObject(out)
}

private fun sanitizeBrowserEventBlock(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val map = raw as? JsonObject ?: return null
    if (!ALLOWED_BROWSER_EVENT_KEYS.containsAll(map.keys)) return null
    val kind = map["kind"].stringOrNull()?.takeIf { it == "window_error" || it == "resource_error" } ?: return null
    val opaque = (map["opaque"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
    val out = LinkedHashMap<String, JsonElement>()
    out["kind"] = JsonPrimitive(kind)
    if (map.containsKey("message")) out["message"] = scrubNullable(map["message"], redactor) ?: return null
    if (map.containsKey("file_name")) out["file_name"] = scrubNullable(map["file_name"], redactor) ?: return null
    for (coord in listOf("line_number", "column_number")) {
        if (map.containsKey(coord)) {
            val value = map[coord]
            if (value != JsonNull && value.exactIntInRange(0, MAX_SAFE_PROTOCOL_INT) == null) return null
            out[coord] = value ?: JsonNull
        }
    }
    if (map.containsKey("target")) {
        val rawTarget = map["target"]
        out["target"] = if (rawTarget == JsonNull) JsonNull else sanitizeBrowserTarget(rawTarget, redactor) ?: return null
    }
    out["opaque"] = JsonPrimitive(opaque)
    if (map.containsKey("page")) {
        out["page"] = sanitizeBrowserPage(map["page"], redactor) ?: return null
    }
    return JsonObject(out)
}

private fun sanitizeBrowserTarget(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val map = raw as? JsonObject ?: return null
    if (!ALLOWED_TARGET_KEYS.containsAll(map.keys)) return null
    val out = LinkedHashMap<String, JsonElement>()
    if (map.containsKey("tag_name")) {
        val rawTag = map["tag_name"]
        out["tag_name"] = when {
            rawTag == JsonNull -> JsonNull
            rawTag.stringOrNull() != null -> {
                val tag = rawTag.stringOrNull()!!
                if (TelemetryPrivacy.asciiLowercase(tag) in ALLOWED_HTML_RESOURCE_TAGS) {
                    JsonPrimitive(tag)
                } else {
                    val sanitized = runCatching { redactor.protectJson(JsonObject(mapOf("tag_name" to JsonPrimitive(tag)))) }.getOrNull() as? JsonObject
                    sanitized?.get("tag_name") ?: return null
                }
            }
            else -> return null
        }
    }
    if (map.containsKey("source_url")) out["source_url"] = scrubNullable(map["source_url"], redactor) ?: return null
    if (map.containsKey("attributes")) {
        val attrs = map["attributes"] as? JsonObject ?: return null
        if (!ALLOWED_TARGET_ATTR_KEYS.containsAll(attrs.keys)) return null
        val attrsOut = LinkedHashMap<String, JsonElement>()
        for (key in ALLOWED_TARGET_ATTR_STRINGS) {
            if (attrs.containsKey(key)) {
                val text = attrs[key].stringOrNull() ?: return null
                attrsOut[key] = JsonPrimitive(redactor.sanitizeProtocolString(text) ?: return null)
            }
        }
        for (key in ALLOWED_TARGET_ATTR_BOOLS) {
            if (attrs.containsKey(key)) {
                val bool = (attrs[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
                attrsOut[key] = JsonPrimitive(bool)
            }
        }
        out["attributes"] = JsonObject(attrsOut)
    }
    return JsonObject(out)
}

private fun sanitizeBrowserPage(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val map = raw as? JsonObject ?: return null
    if (!ALLOWED_PAGE_KEYS.containsAll(map.keys)) return null
    val out = LinkedHashMap<String, JsonElement>()
    for (field in listOf("url", "referrer")) {
        if (map.containsKey(field)) out[field] = scrubNullable(map[field], redactor) ?: return null
    }
    if (map.containsKey("ready_state")) {
        val value = map["ready_state"]
        if (value != JsonNull && value.stringOrNull() !in ALLOWED_READY_STATES) return null
        out["ready_state"] = value ?: JsonNull
    }
    if (map.containsKey("visibility_state")) {
        val value = map["visibility_state"]
        if (value != JsonNull && value.stringOrNull() !in ALLOWED_VISIBILITY_STATES) return null
        out["visibility_state"] = value ?: JsonNull
    }
    return JsonObject(out)
}

private fun sanitizeRejectionReasonBlock(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val map = raw as? JsonObject ?: return null
    if (!ALLOWED_REJECTION_KEYS.containsAll(map.keys)) return null
    val kind = map["kind"].stringOrNull()?.takeIf { it in ALLOWED_REJECTION_KINDS } ?: return null
    val out = LinkedHashMap<String, JsonElement>()
    out["kind"] = JsonPrimitive(kind)
    for (field in listOf("name", "message", "preview")) {
        if (map.containsKey(field)) {
            val text = map[field].stringOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            out[field] = JsonPrimitive(redactor.sanitizeProtocolString(text) ?: return null)
        }
    }
    return JsonObject(out)
}

private fun sanitizeExceptionEvidenceBlock(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val graph = raw as? JsonObject ?: return null
    if (graph.keys != ALLOWED_GRAPH_KEYS || graph["version"].exactIntInRange(1, 1) != 1L) return null
    val runtime = graph["runtime"].stringOrNull()?.takeIf { it in ALLOWED_RUNTIMES } ?: return null
    if (graph["root_id"].exactIntInRange(0, 7) == null) return null
    val truncated = (graph["truncated"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
    val unavailable = graph["unavailable_reason"]
    if (unavailable != JsonNull && unavailable.stringOrNull() !in ALLOWED_LIMIT_REASONS) return null
    val rawNodes = graph["nodes"] as? JsonArray ?: return null
    if (rawNodes.isEmpty() || rawNodes.size > 8) return null
    val nodes = ArrayList<JsonElement>(rawNodes.size)
    for (rawNode in rawNodes) {
        val node = rawNode as? JsonObject ?: return null
        if (node.keys != ALLOWED_NODE_KEYS || node["id"].exactIntInRange(0, 7) == null) return null
        val name = scrubNullable(node["name"], redactor, 256)?.takeIf { it != JsonNull } ?: return null
        val message = scrubNullable(node["message"], redactor, 2048)?.takeIf { it != JsonNull } ?: return null
        val stack = scrubNullable(node["stack"], redactor, 16_384) ?: return null
        val traceOrigin = node["trace_origin"].stringOrNull()?.takeIf { it in ALLOWED_TRACE_ORIGINS } ?: return null
        val parsingStatus = node["parsing_status"].stringOrNull()?.takeIf { it in ALLOWED_PARSING_STATUSES } ?: return null
        val rawFrames = node["frames"] as? JsonArray ?: return null
        val rawEdges = node["relationships"] as? JsonArray ?: return null
        if (rawFrames.size > 32 || rawEdges.size > 5) return null
        val frames = ArrayList<JsonElement>(rawFrames.size)
        for (rawFrame in rawFrames) {
            val frame = rawFrame as? JsonObject ?: return null
            if (frame.keys != ALLOWED_FRAME_KEYS) return null
            val fn = scrubNullable(frame["function"], redactor, 256) ?: return null
            val file = scrubNullable(frame["file"], redactor, 2048) ?: return null
            val line = frame["line"]
            val column = frame["column"]
            if ((line != JsonNull && line.exactIntInRange(1, MAX_SAFE_PROTOCOL_INT) == null) ||
                (column != JsonNull && column.exactIntInRange(0, MAX_SAFE_PROTOCOL_INT) == null)) return null
            val classification = frame["classification"].stringOrNull()?.takeIf { it in ALLOWED_FRAME_CLASSIFICATIONS } ?: return null
            frames += JsonObject(mapOf(
                "function" to fn, "file" to file, "line" to (line ?: JsonNull), "column" to (column ?: JsonNull),
                "classification" to JsonPrimitive(classification),
            ))
        }
        val edges = ArrayList<JsonElement>(rawEdges.size)
        for (rawEdge in rawEdges) {
            val edge = rawEdge as? JsonObject ?: return null
            if (edge.keys != ALLOWED_EDGE_KEYS) return null
            val edgeKind = edge["kind"].stringOrNull()?.takeIf { it in ALLOWED_RELATIONSHIP_KINDS } ?: return null
            if (edge["target_id"].exactIntInRange(0, 7) == null) return null
            edges += JsonObject(mapOf("kind" to JsonPrimitive(edgeKind), "target_id" to edge.getValue("target_id")))
        }
        nodes += JsonObject(mapOf(
            "id" to node.getValue("id"), "name" to name, "message" to message, "stack" to stack,
            "trace_origin" to JsonPrimitive(traceOrigin), "parsing_status" to JsonPrimitive(parsingStatus),
            "frames" to JsonArray(frames), "relationships" to JsonArray(edges),
        ))
    }
    return JsonObject(mapOf(
        "version" to JsonPrimitive(1), "runtime" to JsonPrimitive(runtime), "root_id" to graph.getValue("root_id"),
        "nodes" to JsonArray(nodes), "truncated" to JsonPrimitive(truncated), "unavailable_reason" to (unavailable ?: JsonNull),
    ))
}

private fun sanitizeExecutionBlock(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val map = raw as? JsonObject ?: return null
    if (map.keys != ALLOWED_EXECUTION_KEYS || map["version"].exactIntInRange(1, 1) != 1L) return null
    val kind = map["kind"].stringOrNull()?.takeIf { it in ALLOWED_EXECUTION_KINDS } ?: return null
    val name = scrubNullable(map["name"], redactor, 128) ?: return null
    val elapsed = map["elapsed_ms"]
    val limit = map["limit_ms"]
    if ((elapsed != JsonNull && !elapsed.nonNegativeFinite()) || (limit != JsonNull && !limit.nonNegativeFinite())) return null
    return JsonObject(mapOf(
        "version" to JsonPrimitive(1), "kind" to JsonPrimitive(kind), "name" to name,
        "elapsed_ms" to (elapsed ?: JsonNull), "limit_ms" to (limit ?: JsonNull),
    ))
}

private fun sanitizeResourceObservationsBlock(raw: JsonElement?, redactor: DebugBundleRedactor): JsonObject? {
    val map = raw as? JsonObject ?: return null
    if (map.keys != ALLOWED_RESOURCE_OBS_KEYS || map["version"].exactIntInRange(1, 1) != 1L) return null
    val rawItems = map["items"] as? JsonArray ?: return null
    if (rawItems.isEmpty() || rawItems.size > 5) return null
    val items = ArrayList<JsonElement>(rawItems.size)
    for (rawItem in rawItems) {
        val item = rawItem as? JsonObject ?: return null
        val kind = item["kind"].stringOrNull() ?: return null
        val observedAt = item["observed_at"].stringOrNull()?.takeIf(DebugBundleEvidenceWire::isTimestamp) ?: return null
        val sourceUrl = scrubNullable(item["source_url"], redactor, 2048) ?: return null
        when (kind) {
            "timing" -> {
                if (item.keys != ALLOWED_TIMING_KEYS) return null
                val availability = item["availability"].stringOrNull()?.takeIf { it in ALLOWED_TIMING_AVAILABILITIES } ?: return null
                val status = item["response_status"]
                val duration = item["duration_ms"]
                val transfer = item["transfer_size_bytes"]
                if ((status != JsonNull && status.exactIntInRange(100, 599) == null) ||
                    (duration != JsonNull && !duration.nonNegativeFinite()) ||
                    (transfer != JsonNull && transfer.exactIntInRange(0, MAX_SAFE_PROTOCOL_INT) == null)) return null
                items += JsonObject(mapOf(
                    "kind" to JsonPrimitive("timing"), "observed_at" to JsonPrimitive(observedAt), "source_url" to sourceUrl,
                    "availability" to JsonPrimitive(availability), "response_status" to (status ?: JsonNull),
                    "duration_ms" to (duration ?: JsonNull), "transfer_size_bytes" to (transfer ?: JsonNull),
                ))
            }
            "csp" -> {
                if (item.keys != ALLOWED_CSP_KEYS) return null
                val directive = item["effective_directive"].stringOrNull()?.takeIf(CSP_DIRECTIVE_PATTERN::matches) ?: return null
                val disposition = item["disposition"].stringOrNull()?.takeIf { it == "enforce" || it == "report" } ?: return null
                val status = item["document_status_code"]
                if (status != JsonNull && status.exactIntInRange(100, 599) == null) return null
                items += JsonObject(mapOf(
                    "kind" to JsonPrimitive("csp"), "observed_at" to JsonPrimitive(observedAt), "source_url" to sourceUrl,
                    "effective_directive" to JsonPrimitive(directive), "disposition" to JsonPrimitive(disposition),
                    "document_status_code" to (status ?: JsonNull),
                ))
            }
            else -> return null
        }
    }
    return JsonObject(mapOf("version" to JsonPrimitive(1), "items" to JsonArray(items)))
}

private fun sanitizeDiagnosticContextPayload(payload: JsonObject, redactor: DebugBundleRedactor): JsonObject? {
    if (payload["version"].exactIntInRange(1, 1) != 1L ||
        !DebugBundleEvidenceWire.freshId(payload["failure_ref"].stringOrNull()) ||
        !DebugBundleEvidenceWire.scope(payload["scope"])) return null
    return when (payload["kind"].stringOrNull()) {
        "recovery" -> {
            if (!ALLOWED_RECOVERY_KEYS.containsAll(payload.keys)) return null
            if (!DebugBundleEvidenceWire.freshId(payload["attempt_id"].stringOrNull())) return null
            val state = payload["state"].stringOrNull()?.takeIf { it in ALLOWED_RECOVERY_STATES } ?: return null
            val out = LinkedHashMap<String, JsonElement>()
            out["version"] = JsonPrimitive(1)
            out["kind"] = JsonPrimitive("recovery")
            out["failure_ref"] = payload.getValue("failure_ref")
            out["scope"] = payload.getValue("scope")
            out["attempt_id"] = payload.getValue("attempt_id")
            out["state"] = JsonPrimitive(state)
            if (payload.containsKey("operation")) out["operation"] = scrubNullable(payload["operation"], redactor, 64) ?: return null
            if (payload.containsKey("status_code")) {
                val status = payload["status_code"]
                if (status != JsonNull && status.exactIntInRange(100, 599) == null) return null
                out["status_code"] = status ?: JsonNull
            }
            if (payload.containsKey("duration_ms")) {
                val duration = payload["duration_ms"]
                if (duration != JsonNull && !duration.nonNegativeFinite()) return null
                out["duration_ms"] = duration ?: JsonNull
            }
            JsonObject(out)
        }
        "resource_observation" -> {
            if (payload.keys != ALLOWED_RESOURCE_DIAG_KEYS) return null
            if (!DebugBundleEvidenceWire.isUuid(payload["source_event_id"].stringOrNull())) return null
            val observations = sanitizeResourceObservationsBlock(payload["observations"], redactor) ?: return null
            JsonObject(mapOf(
                "version" to JsonPrimitive(1), "kind" to JsonPrimitive("resource_observation"),
                "failure_ref" to payload.getValue("failure_ref"), "scope" to payload.getValue("scope"),
                "source_event_id" to payload.getValue("source_event_id"), "observations" to observations,
            ))
        }
        else -> null
    }
}

internal fun protectDebugBundleEnvelope(event: DebugBundleEnvelope, redactor: DebugBundleRedactor): DebugBundleEnvelope? {
    redactor.requireValidConfig()
    val identities = listOfNotNull(event.schemaVersion, event.sdkName, event.sdkVersion,
        event.correlation?.requestId, event.correlation?.traceId,
        event.correlation?.sessionId, event.correlation?.userIdHash)
    if (identities.any { (redactor.sanitize(it) as? JsonPrimitive)?.content != it }) return null

    val preservedPayload = LinkedHashMap<String, JsonElement>()
    val rawPayload = event.payload.toMutableMap()
    val diagnosticPayload: JsonObject? = if (event.eventType == "diagnostic_context") {
        sanitizeDiagnosticContextPayload(event.payload, redactor) ?: return null
    } else {
        if (event.eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION) {
            rawPayload["browser_event"]?.let { sanitizeBrowserEventBlock(it, redactor) }?.let {
                preservedPayload["browser_event"] = it; rawPayload.remove("browser_event")
            }
            rawPayload["rejection_reason"]?.let { sanitizeRejectionReasonBlock(it, redactor) }?.let {
                preservedPayload["rejection_reason"] = it; rawPayload.remove("rejection_reason")
            }
            rawPayload["resource_observations"]?.let { sanitizeResourceObservationsBlock(it, redactor) }?.let {
                preservedPayload["resource_observations"] = it; rawPayload.remove("resource_observations")
            }
            rawPayload["device"]?.let { sanitizeDeviceBlock(it, redactor) }?.let {
                preservedPayload["device"] = it; rawPayload.remove("device")
            }
        }
        if (event.eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION || event.eventType == "backend_exception") {
            rawPayload["exception_evidence"]?.let { sanitizeExceptionEvidenceBlock(it, redactor) }?.let {
                preservedPayload["exception_evidence"] = it; rawPayload.remove("exception_evidence")
            }
        }
        if (event.eventType == "backend_exception") {
            rawPayload["execution"]?.let { sanitizeExecutionBlock(it, redactor) }?.let {
                preservedPayload["execution"] = it; rawPayload.remove("execution")
            }
        }
        if (event.eventType == DebugBundleEventTypes.ERROR_SUPPRESSED && rawPayload["reason"].stringOrNull() == "queue_pressure") {
            preservedPayload["reason"] = JsonPrimitive("queue_pressure"); rawPayload.remove("reason")
        }
        null
    }

    val preservedContext = LinkedHashMap<String, JsonElement>()
    val rawContext = event.context?.toMutableMap()
    if (rawContext != null) {
        rawContext["evidence_reference"]?.takeIf(DebugBundleEvidenceWire::reference)?.let {
            preservedContext["evidence_reference"] = it; rawContext.remove("evidence_reference")
        }
        rawContext["evidence_limits"]?.takeIf(::isValidEvidenceLimits)?.let {
            preservedContext["evidence_limits"] = it; rawContext.remove("evidence_limits")
        }
    }

    val payload = if (diagnosticPayload != null) {
        diagnosticPayload
    } else {
        val sanitized = redactor.sanitize(JsonObject(rawPayload)) as? JsonObject ?: return null
        JsonObject(sanitized + preservedPayload)
    }
    val context = rawContext?.let {
        val sanitized = redactor.sanitize(JsonObject(it)) as? JsonObject ?: return null
        JsonObject(sanitized + preservedContext)
    }
    val device = event.device?.let { sanitizeDeviceBlock(it, redactor) ?: (redactor.sanitize(it) as? JsonObject ?: return null) }
    val assembledBytes = payload.toString().toByteArray(StandardCharsets.UTF_8).size +
        (context?.toString()?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) +
        (device?.toString()?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0)
    if (assembledBytes > MAX_PROTOCOL_EVENT_BYTES) return null

    fun safeText(value: String): String = (redactor.sanitize(value) as JsonPrimitive).content
    return event.copy(
        payload = payload,
        context = context,
        device = device,
        service = event.service.copy(
            name = safeText(event.service.name),
            environment = safeText(event.service.environment),
            runtime = safeText(event.service.runtime),
            framework = event.service.framework?.let(::safeText),
        ).also { it.runtimeIsAbsent = event.service.runtimeIsAbsent },
        correlation = event.correlation?.copy(
            requestId = event.correlation.requestId?.let(::safeText),
            traceId = event.correlation.traceId?.let(::safeText),
            sessionId = event.correlation.sessionId?.let(::safeText),
            userIdHash = event.correlation.userIdHash?.let(::safeText),
        ),
    )
}

/** Revalidate privacy and reduce optional data on the latest accepted primary only. */
internal fun protectDebugBundleEvidenceEnvelope(event: DebugBundleEnvelope, redactor: DebugBundleRedactor): DebugBundleEnvelope? {
    fun protect(candidate: DebugBundleEnvelope): DebugBundleEnvelope? = runCatching {
        protectDebugBundleEnvelope(candidate, redactor)?.takeIf(DebugBundleEnvelope::isValidBeforeSendEvent)
    }.getOrNull()
    protect(event)?.let { return it }
    for (mark in listOf(true, false)) withoutDebugBundleOptionalEvidence(event, mark)?.let { reduced -> protect(reduced)?.let { return it } }
    return null
}

internal fun withoutDebugBundleOptionalEvidence(event: DebugBundleEnvelope, mark: Boolean): DebugBundleEnvelope? {
    if (event.eventType !in setOf("backend_exception", DebugBundleEventTypes.FRONTEND_EXCEPTION)) return null
    val payload = event.payload.toMutableMap(); val limits = linkedMapOf<String, JsonElement>("version" to JsonPrimitive(1))
    for (field in listOf("exception_evidence", "execution", "resource_observations")) if (payload.remove(field) != null) limits[field] = JsonPrimitive("budget_exceeded")
    if (limits.size == 1) return null
    val context = event.context?.toMutableMap() ?: linkedMapOf()
    if (DebugBundleEvidenceWire.reference(context["evidence_reference"])) context.remove("evidence_reference")
    if (mark) context["evidence_limits"] = JsonObject(limits)
    return event.copy(payload = JsonObject(payload), context = JsonObject(context))
}
