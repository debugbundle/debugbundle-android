package com.debugbundle.android

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.time.Duration

/** One monotonic budget across connect/headers/body reads; no per-chunk renewal. */
internal class DebugBundleHttpDeadline(timeout: Duration) {
    private val started = System.nanoTime()
    private val span = timeout.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()) * 1000000L
    fun remainingMillis(): Int {
        val remaining = span - (System.nanoTime() - started)
        if (remaining <= 0) throw IOException("request deadline elapsed")
        return ((remaining + 999999) / 1000000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun configure(connection: HttpURLConnection) { connection.connectTimeout = remainingMillis(); connection.readTimeout = remainingMillis() }
}

internal object DebugBundleHttpReply {
    const val MAX_BYTES = 262144
    fun read(connection: HttpURLConnection, statusCode: Int, deadline: DebugBundleHttpDeadline): String {
        val encoding = connection.getHeaderField("Content-Encoding")?.trim()
        if (!encoding.isNullOrEmpty() && !encoding.equals("identity", true)) throw IOException("unsupported response encoding")
        if ((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: 0) > MAX_BYTES) throw IOException("response exceeds byte limit")
        connection.readTimeout = deadline.remainingMillis()
        val stream = (if (statusCode in 200..299) connection.inputStream else connection.errorStream) ?: return ""
        val bytes = ByteArrayOutputStream(); val chunk = ByteArray(8192)
        try {
            while (true) {
                connection.readTimeout = deadline.remainingMillis()
                val count = stream.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - bytes.size()))
                if (count < 0) break
                bytes.write(chunk, 0, count)
                if (bytes.size() > MAX_BYTES) throw IOException("response exceeds byte limit")
            }
        } catch (error: Throwable) { connection.disconnect(); throw error }
        finally { stream.close() }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
