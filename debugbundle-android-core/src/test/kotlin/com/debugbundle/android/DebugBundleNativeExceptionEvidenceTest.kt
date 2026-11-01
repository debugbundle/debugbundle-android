package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.lang.ref.Reference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleNativeExceptionEvidenceTest {
    private fun capture(error: Throwable, enabled: Boolean = true): DebugBundleEnvelope {
        val transport = RecordingTransport()
        val client = DebugBundleClient.create(
            config = DebugBundleConfig(projectToken = "fixture", captureFatalExceptions = false, flushInterval = 1.hours)
                .withEvidenceOptions(DebugBundleEvidenceOptions(captureExceptionEvidence = enabled,
                    evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1")))),
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) },
        )
        try { client.captureException(error); client.flush(); Reference.reachabilityFence(error); return transport.events.single() }
        finally { client.close() }
    }
    @Test
    fun `native cause and suppressed graph preserves original primary and structured locations`() {
        val cause = IllegalArgumentException("password=hidden cause")
        val error = IllegalStateException("outer", cause)
        error.stackTrace = arrayOf(StackTraceElement("example.Checkout", "submit", "Checkout.kt", 42))
        error.addSuppressed(IllegalStateException("related"))
        val event = capture(error)
        assertEquals("outer", (event.payload["message"] as JsonPrimitive).content)
        val graph = event.payload["exception_evidence"] as JsonObject
        val nodes = graph["nodes"] as JsonArray
        assertEquals(3, nodes.size); assertEquals("android", (graph["runtime"] as JsonPrimitive).content)
        val root = nodes[0] as JsonObject
        assertEquals(setOf("cause", "suppressed"), (root["relationships"] as JsonArray).map { ((it as JsonObject)["kind"] as JsonPrimitive).content }.toSet())
        val frame = ((root["frames"] as JsonArray).first() as JsonObject)
        assertEquals("example.Checkout.submit", (frame["function"] as JsonPrimitive).content)
        assertEquals("42", (frame["line"] as JsonPrimitive).content)
        assertEquals("throw", (root["trace_origin"] as JsonPrimitive).content)
        assertFalse(graph.toString().contains("hidden")); assertTrue(event.isValidBeforeSendEvent())
    }
    @Test
    fun `native graph bounds width depth cycles and multi byte additions`() {
        val error = IllegalStateException("outer")
        repeat(8) { error.addSuppressed(IllegalStateException("界".repeat(10000))) }
        val graph = capture(error).payload["exception_evidence"] as JsonObject
        assertTrue((graph["nodes"] as JsonArray).size <= 8)
        assertEquals("true", (graph["truncated"] as JsonPrimitive).content)
        assertTrue(DebugBundleAcceptingJsonSize.bytes(graph) <= 32768)
        val first = IllegalStateException("first"); val second = IllegalStateException("second", first); first.initCause(second)
        assertTrue(capture(first).isValidBeforeSendEvent())
    }
    @Test
    fun `disabled structured capture does not inspect additional throwable relationships`() {
        val calls = AtomicInteger()
        val error = object : RuntimeException("outer") { override val cause: Throwable? get() { calls.incrementAndGet(); return null } }
        assertFalse(capture(error, enabled = false).payload.containsKey("exception_evidence"))
        assertEquals(0, calls.get())
    }
}
