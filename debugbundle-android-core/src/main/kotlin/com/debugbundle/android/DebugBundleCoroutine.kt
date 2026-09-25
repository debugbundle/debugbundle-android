package com.debugbundle.android

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineExceptionHandler

interface DebugBundleCoroutineSupport {
    fun coroutineExceptionHandler(context: Map<String, Any?> = emptyMap()): CoroutineExceptionHandler
}

internal fun debugBundleCoroutineExceptionHandler(
    context: Map<String, Any?>,
    capture: (Throwable, Map<String, Any?>) -> Unit,
): CoroutineExceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
    val metadata = LinkedHashMap<String, Any?>(context).apply {
        put("mechanism", "coroutine_exception_handler")
        put("handled", false)
        coroutineContext[CoroutineName]?.name?.let { put("coroutine_name", it) }
    }
    capture(throwable, metadata)
}
