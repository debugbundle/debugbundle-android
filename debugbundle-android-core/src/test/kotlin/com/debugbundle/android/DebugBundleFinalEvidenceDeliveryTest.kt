package com.debugbundle.android

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugBundleFinalEvidenceDeliveryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private fun primary(): DebugBundleEnvelope {
        val corpus = json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject
        return json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), (corpus.getValue("events") as JsonArray).first().jsonObject.getValue("event"))
    }
    @Test
    fun `legacy hydration preserves an already canonical authored primary byte for byte`() {
        val event = primary()
        assertEquals(json.encodeToString(event), json.encodeToString(canonicalizeAndroidEnvelope(event, JsonObject(emptyMap()))))
    }
    @Test
    fun `explicitly rejected enriched original retries once after proof loss with stable latest hook identity`() {
        val received = Collections.synchronizedList(mutableListOf<JsonObject>()); val hooks = AtomicInteger(); val clock = AtomicReference(Instant.parse("2026-10-28T00:00:00Z")); val fixture = primary()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }; val item = (json.parseToJsonElement(body).jsonObject.getValue("events") as JsonArray).single().jsonObject; received.add(item)
            val response = (if (received.size == 1) """{"accepted":0,"rejected":1,"errors":[{"index":0,"reason":"invalid_event"}]}""" else """{"accepted":1,"rejected":0,"errors":[]}""").toByteArray()
            exchange.sendResponseHeaders(202, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }; server.start()
        val config = DebugBundleConfig(projectToken = "fixture", endpoint = "http://127.0.0.1:${server.address.port}/v1/events", flushInterval = 1.hours)
            .withEvidenceOptions(DebugBundleEvidenceOptions(evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1"))))
            .withBeforeSend(DebugBundleBeforeSend { candidate -> hooks.incrementAndGet(); candidate.copy(eventId = UUID.randomUUID().toString(), sdkName = fixture.sdkName, eventType = fixture.eventType, payload = fixture.payload) })
        val client = DebugBundleClient.create(config = config, queueStore = InMemoryDebugBundleQueueStore(), clock = { clock.get() }, remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
        try {
            client.captureMessage("fixture", DebugBundleLogLevel.Error); client.flush(); assertEquals(1, received.size)
            clock.set(clock.get().plusSeconds(2)); client.flush(); assertEquals(2, received.size); assertEquals(1, hooks.get())
            assertEquals(received[0]["event_id"], received[1]["event_id"]); assertTrue(received[0].getValue("payload").jsonObject.containsKey("exception_evidence")); assertFalse(received[1].getValue("payload").jsonObject.containsKey("exception_evidence"))
        } finally { client.close(); server.stop(0) }
    }
    @Test
    fun `native final wrapper partitions accepted hook outputs without rerunning hooks or losing tails`() {
        val received = Collections.synchronizedList(mutableListOf<JsonObject>())
        val sizes = Collections.synchronizedList(mutableListOf<Int>()); val hooks = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }; sizes.add(body.size)
            val events = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject.getValue("events") as JsonArray
            received.addAll(events.map { it.jsonObject })
            val response = """{"accepted":${events.size},"rejected":0,"errors":[]}""".toByteArray()
            exchange.sendResponseHeaders(202, response.size.toLong()); exchange.responseBody.use { it.write(response) }
        }; server.start()
        val config = DebugBundleConfig(projectToken = "fixture", captureFatalExceptions = false,
            endpoint = "http://127.0.0.1:${server.address.port}/v1/events", batchSize = 10, flushInterval = 1.hours)
            .withBeforeSend(DebugBundleBeforeSend { candidate -> hooks.incrementAndGet(); candidate.copy(payload = JsonObject(candidate.payload +
                ("attributes" to JsonObject((0 until 38).associate { "detail_$it" to kotlinx.serialization.json.JsonPrimitive("界".repeat(600)) })))) })
        val store = InMemoryDebugBundleQueueStore()
        val client = DebugBundleClient.create(config = config, queueStore = store,
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
        try {
            repeat(6) { client.captureMessage("message $it", DebugBundleLogLevel.Error) }; client.flush()
            assertEquals(6, received.size); assertEquals(6, hooks.get()); assertTrue(sizes.size >= 2)
            assertTrue(sizes.all { it <= 256 * 1024 }); assertEquals(6, received.map { it["event_id"] }.distinct().size)
            assertTrue(store.snapshot(System.currentTimeMillis(), debugBundleQueueLimits(config)).isEmpty())
        } finally { client.close(); server.stop(0) }
    }

}
