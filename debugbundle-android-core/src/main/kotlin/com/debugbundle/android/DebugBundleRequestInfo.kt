package com.debugbundle.android

data class DebugBundleRequestInfo(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val routeTemplate: String? = null,
    val traceId: String? = null,
)

data class DebugBundleResponseInfo(
    val statusCode: Int,
    val durationMillis: Long? = null,
    val headers: Map<String, String> = emptyMap(),
)
