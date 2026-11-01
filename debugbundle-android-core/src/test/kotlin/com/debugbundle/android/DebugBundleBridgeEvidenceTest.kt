package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleBridgeEvidenceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun event(): JsonObject {
        val corpus = json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject
        val original = (corpus.getValue("events") as JsonArray).first().jsonObject.getValue("event").jsonObject
        val payload = JsonObject(original.getValue("payload").jsonObject.filterKeys { it != "browser" } + ("device" to canonicalizeAndroidDevice(JsonObject(emptyMap()))))
        return JsonObject(original + mapOf("sdk_name" to JsonPrimitive(REACT_NATIVE_SDK_NAME), "payload" to payload))
    }
    private fun native(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { native(it.value) }.toMap(LinkedHashMap())
        is JsonArray -> value.map(::native).toCollection(ArrayList())
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull
    }
    @Suppress("UNCHECKED_CAST") private fun input(event: JsonObject): Map<String, Any?> = native(event) as Map<String, Any?>
    private fun client(transport: RecordingTransport): DebugBundleClient {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val client = DebugBundleClient.create(config = DebugBundleConfig(projectToken = "fixture", captureFatalExceptions = false, flushInterval = 1.hours)
            .withEvidenceOptions(DebugBundleEvidenceOptions(captureRecoveryObservations = true,
                evidenceCapabilities = DebugBundleEvidenceCapabilities(features = DebugBundleEvidenceCapabilities.KNOWN))),
            transport = transport, executor = executor, clock = { Instant.parse("2026-10-26T10:00:00Z") },
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
        executor.submit {}.get(5, TimeUnit.SECONDS); return client
    }
    @Test
    fun `bridge preserves canonical graph and structured frame through native queue`() {
        val transport = RecordingTransport(); val client = client(transport); val original = event()
        try {
            assertTrue(client.captureExternalEvent(input(original))); client.flush()
            assertEquals(original["event_id"], JsonPrimitive(transport.events.single().eventId))
            assertEquals(original.getValue("payload").jsonObject["exception_evidence"], transport.events.single().payload["exception_evidence"])
            assertTrue(transport.events.single().isValidBeforeSendEvent())
        } finally { client.close() }
    }
    @Test
    fun `bridge rejects malformed new graph instead of silently discarding it`() {
        val transport = RecordingTransport(); val client = client(transport); val original = event(); val payload = original.getValue("payload").jsonObject
        val graph = payload.getValue("exception_evidence").jsonObject
        try {
            val malformed = JsonObject(original + ("payload" to JsonObject(payload + ("exception_evidence" to JsonObject(graph + ("unexpected" to JsonPrimitive(true)))))))
            assertFalse(client.captureExternalEvent(input(malformed)))
            assertFalse(client.captureExternalEvent(input(JsonObject(original + ("correlation" to JsonObject(mapOf("unexpected" to JsonPrimitive(true))))))))
            client.flush(); assertTrue(transport.events.isEmpty())
        } finally { client.close() }
    }
    @Test
    fun `bridge independently preserves a valid authored recovery event`() {
        val transport = RecordingTransport(); val client = client(transport)
        val payload = JsonObject(mapOf("version" to JsonPrimitive(1), "kind" to JsonPrimitive("recovery"),
            "failure_ref" to JsonPrimitive(UUID.randomUUID().toString()), "attempt_id" to JsonPrimitive(UUID.randomUUID().toString()),
            "state" to JsonPrimitive("recovery_observed"), "scope" to JsonObject(mapOf("kind" to JsonPrimitive("operation"), "operation_id" to JsonPrimitive(UUID.randomUUID().toString())))))
        val original = JsonObject(event() + mapOf("event_type" to JsonPrimitive("diagnostic_context"), "payload" to payload))
        try { assertTrue(client.captureExternalEvent(input(original))); client.flush(); assertEquals(payload, transport.events.single().payload) }
        finally { client.close() }
    }
    @Test
    fun `foreign bridge containers and json delegates never execute caller accessors`() {
        val transport = RecordingTransport(); val client = client(transport); val calls = AtomicInteger()
        val foreign = object : AbstractMap<String, Any?>() { override val entries: Set<Map.Entry<String, Any?>> get() { calls.incrementAndGet(); throw IllegalStateException("foreign") } }
        try { assertFalse(client.captureExternalEvent(foreign)); assertEquals(0, calls.get()) }
        finally { client.close() }
    }
}
