package com.debugbundle.android.crash

import com.debugbundle.android.internal.DebugBundleRedactor
import java.time.Instant
import kotlinx.serialization.json.*

/** Legacy built-in root access only; custom Throwable accessors are never invoked on the fatal caller. */
internal object DebugBundleFatalSnapshot {
    private val native = setOf(Throwable::class.java, Exception::class.java, RuntimeException::class.java, Error::class.java,
        IllegalStateException::class.java, IllegalArgumentException::class.java, NullPointerException::class.java,
        UnsupportedOperationException::class.java, AssertionError::class.java, SecurityException::class.java,
        NumberFormatException::class.java, IndexOutOfBoundsException::class.java, ArithmeticException::class.java,
        ClassCastException::class.java, ArrayIndexOutOfBoundsException::class.java, InterruptedException::class.java,
        java.io.IOException::class.java, java.io.FileNotFoundException::class.java, java.io.EOFException::class.java,
        java.net.SocketTimeoutException::class.java, java.net.ConnectException::class.java)
    fun capture(thread: Thread, error: Throwable, clock: () -> Instant, redactor: DebugBundleRedactor): DebugBundleFatalCrashRecord {
        val supported = error.javaClass in native
        val rawMessage = if (supported) runCatching { error.message.orEmpty().take(512) }.getOrDefault("Unavailable")
            else "Throwable details unavailable during fatal capture"
        val rawFrames = if (supported) runCatching { error.stackTrace.take(20).map { it.toString().take(256) } }.getOrDefault(emptyList()) else emptyList()
        val protected = redactor.sanitize(buildJsonObject {
            put("type", error.javaClass.name); put("message", rawMessage); put("thread", thread.name.take(128))
            put("frames", JsonArray(rawFrames.map(::JsonPrimitive)))
        }) as JsonObject
        return DebugBundleFatalCrashRecord(occurredAt = clock().toString(), threadName = protected.getValue("thread").jsonPrimitive.content,
            errorType = protected.getValue("type").jsonPrimitive.content, errorMessage = protected.getValue("message").jsonPrimitive.content,
            stackTrace = protected.getValue("frames").jsonArray.map { it.jsonPrimitive.content },
            traceOrigin = if (rawFrames.isEmpty()) "unavailable" else "native_fatal")
    }
}
