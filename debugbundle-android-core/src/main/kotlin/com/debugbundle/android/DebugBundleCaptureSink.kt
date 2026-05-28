package com.debugbundle.android

interface DebugBundleCaptureSink {
    fun recordRequest(
        request: DebugBundleRequestInfo,
        response: DebugBundleResponseInfo,
        context: Map<String, Any?>,
        recordBreadcrumb: Boolean,
    )

    fun recordBreadcrumb(
        breadcrumbType: String,
        route: String?,
        data: Map<String, Any?>,
    )

    fun activateProbeTriggerToken(token: String): Boolean = false
}
