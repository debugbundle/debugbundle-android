package com.debugbundle.android

import kotlinx.coroutines.CoroutineExceptionHandler

interface DebugBundleCoroutineSupport {
    fun coroutineExceptionHandler(context: Map<String, Any?> = emptyMap()): CoroutineExceptionHandler
}
