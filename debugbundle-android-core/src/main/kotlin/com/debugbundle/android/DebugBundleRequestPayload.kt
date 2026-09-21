package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun filterRequestHeaders(headers: Map<String, String>, allowlist: Set<String>): Map<String, String> {
    val filtered = LinkedHashMap<String, String>()
    headers.forEach { (name, value) ->
        val normalizedName = normalizeDebugBundleHeaderName(name)
        if (normalizedName in allowlist) filtered[normalizedName] = value
    }
    return filtered
}

internal fun buildDebugBundleRequestPayload(
    request: DebugBundleRequestInfo,
    response: DebugBundleResponseInfo,
    context: Map<String, Any?>,
    redactor: DebugBundleRedactor,
    headerAllowlist: Set<String>,
): JsonObject = buildJsonObject {
    put("method", JsonPrimitive(request.method))
    put("url", JsonPrimitive(request.url))
    put("headers", redactor.sanitize(filterRequestHeaders(request.headers, headerAllowlist)))
    request.routeTemplate?.let { put("route_template", JsonPrimitive(it)) }
    put("response_status", JsonPrimitive(response.statusCode))
    response.durationMillis?.let { put("duration_ms", JsonPrimitive(it)) }
    put("response_headers", redactor.sanitize(filterRequestHeaders(response.headers, headerAllowlist)))
    put("context", redactor.sanitize(context))
}
