package com.debugbundle.android

import com.debugbundle.android.crash.DebugBundleFatalCrashRecord
import com.debugbundle.android.internal.DebugBundleRedactor
import kotlinx.serialization.json.*

/** Parse only retained native strings on the startup worker; no Throwable or source lookup. */
internal object DebugBundleFatalEvidence {
    private val frame = Regex("^(?:at\\s+)?([^\\s(]{1,512})\\(([^)]{0,2048})\\)$")
    fun capture(record: DebugBundleFatalCrashRecord, redactor: DebugBundleRedactor): JsonObject? = runCatching {
        val frames = ArrayList<JsonElement>(); var partial = false
        for (raw in record.stackTrace.take(8)) {
            val match = frame.matchEntire(raw.take(2304))
            if (match == null) { partial = true; continue }
            val function = DebugBundleExceptionEvidence.clip(match.groupValues[1], 256); var file: String? = match.groupValues[2]
            val colon = file!!.lastIndexOf(':'); var line: Int? = null
            if (colon >= 0) { line = file.substring(colon + 1).toIntOrNull()?.takeIf { it > 0 }; file = file.substring(0, colon) }
            if (file in setOf("Native Method", "Unknown Source", "")) file = null
            val owner = function.substringAfterLast('/')
            frames.add(JsonObject(mapOf("function" to JsonPrimitive(function), "file" to (file?.let(::JsonPrimitive) ?: JsonNull),
                "line" to (line?.let(::JsonPrimitive) ?: JsonNull), "column" to JsonNull,
                "classification" to JsonPrimitive(when {
                    owner.startsWith("com.debugbundle.") -> "sdk"
                    listOf("java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.", "dalvik.").any(owner::startsWith) -> "framework"
                    else -> "unknown"
                }))))
        }
        var truncated = record.stackTrace.size > 8
        fun graph(): JsonObject = JsonObject(mapOf("version" to JsonPrimitive(1), "runtime" to JsonPrimitive("android"), "root_id" to JsonPrimitive(0),
            "nodes" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(0), "name" to JsonPrimitive(DebugBundleExceptionEvidence.clip(record.errorType, 256)),
                "message" to JsonPrimitive(DebugBundleExceptionEvidence.clip(record.errorMessage, 2048)), "stack" to JsonNull,
                "trace_origin" to JsonPrimitive(record.traceOrigin.takeIf { it in setOf("native_fatal", "runtime_reported", "throw", "capture", "unavailable", "unknown") } ?: "unknown"),
                "parsing_status" to JsonPrimitive(if (truncated) "truncated" else if (frames.isEmpty()) "unavailable" else if (partial) "partial" else "parsed"),
                "frames" to JsonArray(frames.toList()), "relationships" to JsonArray(emptyList()))))),
            "truncated" to JsonPrimitive(truncated), "unavailable_reason" to JsonPrimitive("unsafe")))
        var result = redactor.sanitize(graph()) as JsonObject
        while (DebugBundleAcceptingJsonSize.bytes(result) > 4096 && frames.isNotEmpty()) {
            frames.removeAt(frames.lastIndex); truncated = true; result = redactor.sanitize(graph()) as JsonObject
        }
        result.takeIf { DebugBundleAcceptingJsonSize.bytes(it) <= 4096 }
    }.getOrNull()
}

internal fun buildDebugBundleFatalReplayPayload(record: DebugBundleFatalCrashRecord, redactor: DebugBundleRedactor,
    evidence: JsonObject?, probeData: JsonObject?): JsonObject = buildJsonObject {
    put("error", buildJsonObject {
        put("type", record.errorType); put("message", record.errorMessage)
        put("stack_trace", JsonArray(record.stackTrace.map(::JsonPrimitive)))
    })
    put("context", redactor.sanitize(mapOf("fatal" to true, "handled" to false,
        "mechanism" to "uncaught_exception_handler", "crash_delivery" to "next_launch", "thread_name" to record.threadName)))
    put("breadcrumbs", JsonArray(emptyList()))
    evidence?.let { put("exception_evidence", it) }
    probeData?.let { put("probe_data", it) }
}
