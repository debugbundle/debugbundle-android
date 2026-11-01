package com.debugbundle.android.logging

import android.util.Log
import com.debugbundle.android.DebugBundle
import com.debugbundle.android.DebugBundleLogLevel
import timber.log.Timber

open class DebugBundleTimberTree(
    private val captureLog: (String, DebugBundleLogLevel, Map<String, Any?>) -> Unit = { message, level, context ->
        DebugBundle.captureLog(message, level, context)
    },
) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        try { forward(priority, tag, message, t) } catch (_: Throwable) { /* Telemetry cannot break application logging. */ }
    }

    private fun forward(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = priority.toDebugBundleLogLevel() ?: return
        val throwableMessage = if (message.isBlank() && t != null &&
            runCatching { t.javaClass.getMethod("getMessage").declaringClass === Throwable::class.java }.getOrDefault(false)) t.message else null
        val resolvedMessage = when {
            message.isNotBlank() -> message
            !throwableMessage.isNullOrBlank() -> throwableMessage.orEmpty()
            t != null -> t::class.java.simpleName.ifBlank { "Throwable" }
            else -> return
        }
        captureLog(
            resolvedMessage,
            level,
            buildMap {
                put("logger", "timber")
                tag?.let { put("tag", it) }
                t?.let { put("throwable", it) }
            },
        )
    }
}

internal fun Int.toDebugBundleLogLevel(): DebugBundleLogLevel? {
    return when (this) {
        Log.VERBOSE, Log.DEBUG -> DebugBundleLogLevel.Debug
        Log.INFO -> DebugBundleLogLevel.Info
        Log.WARN -> DebugBundleLogLevel.Warning
        Log.ERROR -> DebugBundleLogLevel.Error
        Log.ASSERT -> DebugBundleLogLevel.Critical
        else -> null
    }
}
