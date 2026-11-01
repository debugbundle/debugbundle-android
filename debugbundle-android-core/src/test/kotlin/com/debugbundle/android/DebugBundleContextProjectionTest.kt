package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import com.debugbundle.android.testkit.RecordingTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DebugBundleContextProjectionTest {
    private class ForeignMap(private val release: CountDownLatch) : LinkedHashMap<String, Any?>() {
        @Volatile var visited = false
        override val size: Int get() { visited = true; release.await(); return super.size }
        override fun get(key: String): Any? { visited = true; release.await(); return super.get(key) }
    }
    private class ForeignText(private val release: CountDownLatch) {
        @Volatile var visited = false
        override fun toString(): String { visited = true; release.await(); return "private host formatter" }
    }
    @Test
    fun `capture callers never enumerate foreign context or invoke foreign identity formatters`() {
        val release = CountDownLatch(1); val foreign = ForeignMap(release); val text = ForeignText(release); val caller = Executors.newSingleThreadExecutor(); val transport = RecordingTransport()
        val client = DebugBundleClient.create(config = DebugBundleConfig(projectToken = "fixture"), transport = transport, queueStore = InMemoryDebugBundleQueueStore(), remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed })
        try {
            caller.submit {
                client.captureException(IllegalStateException("outer context fixture"), foreign)
                client.captureException(IllegalStateException("identity fixture"), mapOf("trace_id" to text))
                client.captureLog("nested fixture", DebugBundleLogLevel.Error, mapOf("nested" to foreign, "object" to text, "logger" to text))
            }.get(2, TimeUnit.SECONDS)
            assertFalse(foreign.visited); assertFalse(text.visited)
        } finally { release.countDown(); client.flush(); client.close(); caller.shutdownNow() }
    }
    @Test
    fun `unsupported values use bounded fallback while ordinary shared containers remain native values`() {
        val release = CountDownLatch(0); val foreign = ForeignMap(release); val text = ForeignText(release); val redactor = DebugBundleRedactor(emptySet())
        val output = redactor.sanitize(mapOf("foreign" to foreign, "object" to text, "shared" to listOf(emptyList<Any>(), emptyList<Any>()))) as JsonObject
        assertEquals(JsonPrimitive("[Unsupported value]"), output["foreign"]); assertEquals(JsonPrimitive("[Unsupported value]"), output["object"]); assertFalse(foreign.visited); assertFalse(text.visited)
    }
}
