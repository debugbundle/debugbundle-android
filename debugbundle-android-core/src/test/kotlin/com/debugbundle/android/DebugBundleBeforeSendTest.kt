package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleBeforeSendTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `context is copied and protected before retention and service fields are scrubbed`() {
        val transport = RecordingTransport()
        val client = newClient(transport, DebugBundleBeforeSend { event ->
            event.copy(service = event.service.copy(runtime = "dbundle_proj_RUNTIME_SECRET", framework = "token=FRAMEWORK_SECRET"))
        })
        val original = mutableMapOf("route" to "/checkout", "password" to "CONTEXT_SECRET")
        client.setContext("checkout", original)
        original["route"] = "/changed-after-retention"
        client.captureMessage("safe", DebugBundleLogLevel.Error)
        client.flush()
        val event = transport.events.single()
        assertEquals("[REDACTED]", event.service.runtime)
        assertEquals("token=[REDACTED]", event.service.framework)
        val checkout = (event.payload["attributes"] as JsonObject)["checkout"] as JsonObject
        assertEquals(JsonPrimitive("/checkout"), checkout["route"])
        assertEquals(JsonPrimitive("[REDACTED]"), checkout["password"])
        client.close()
    }

    @Test
    fun `beforeSend cannot reintroduce credentials through protocol metadata`() {
        val transport = RecordingTransport()
        val client = newClient(transport, DebugBundleBeforeSend { event ->
            event.copy(sdkVersion = "dbundle_proj_SYNTHETIC_SECRET")
        })
        client.captureMessage("safe", DebugBundleLogLevel.Error)
        client.flush()
        assertTrue(transport.events.isEmpty())
        client.close()
    }

    @Test
    fun `beforeSend runs after redaction and mutates before queueing`() {
        val transport = RecordingTransport()
        val observed = mutableListOf<String>()
        val client = newClient(
            transport,
            DebugBundleBeforeSend { event ->
                val attributes = event.payload["attributes"] as JsonObject
                observed += (attributes["password"] as JsonPrimitive).content
                event.copy(
                    payload = JsonObject(event.payload + ("message" to JsonPrimitive("mutated"))),
                )
            },
        )

        client.captureMessage("original", DebugBundleLogLevel.Error, mapOf("password" to "secret"))
        client.flush()

        assertEquals(listOf("[REDACTED]"), observed)
        assertEquals("mutated", (transport.events.single().payload["message"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `beforeSend cannot reintroduce credentials into the queue or transport`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport,
            DebugBundleBeforeSend { event ->
                event.copy(payload = JsonObject(event.payload + (
                    "message" to JsonPrimitive("Failure token=hook-secret")
                )))
            },
        )

        client.captureMessage("original", DebugBundleLogLevel.Error)
        client.flush()

        val message = (transport.events.single().payload["message"] as JsonPrimitive).content
        assertEquals("Failure token=[REDACTED]", message)
        client.close()
    }

    @Test
    fun `beforeSend drop invalid failure and early policy ordering are safe`() {
        val dropTransport = RecordingTransport()
        val dropCalls = AtomicInteger()
        val droppingClient = newClient(
            dropTransport,
            DebugBundleBeforeSend {
                dropCalls.incrementAndGet()
                null
            },
        )
        droppingClient.captureMessage("drop", DebugBundleLogLevel.Error)
        droppingClient.flush()
        assertEquals(1, dropCalls.get())
        assertTrue(dropTransport.events.isEmpty())
        droppingClient.close()

        val invalidTransport = RecordingTransport()
        val invalidClient = newClient(
            invalidTransport,
            DebugBundleBeforeSend { event -> event.copy(eventId = "invalid") },
        )
        invalidClient.captureMessage("preserve invalid", DebugBundleLogLevel.Error)
        invalidClient.flush()
        assertEquals(
            "preserve invalid",
            (invalidTransport.events.single().payload["message"] as JsonPrimitive).content,
        )
        invalidClient.close()

        val failureTransport = RecordingTransport()
        val failureClient = newClient(
            failureTransport,
            DebugBundleBeforeSend { throw IllegalStateException("hook failed") },
        )
        failureClient.captureMessage("preserve failure", DebugBundleLogLevel.Error)
        failureClient.flush()
        assertEquals(
            "preserve failure",
            (failureTransport.events.single().payload["message"] as JsonPrimitive).content,
        )
        failureClient.close()

        val policyCalls = AtomicInteger()
        val policyTransport = RecordingTransport()
        val policyClient = newClient(
            policyTransport,
            DebugBundleBeforeSend { event ->
                policyCalls.incrementAndGet()
                event
            },
        )
        policyClient.captureMessage("policy drop", DebugBundleLogLevel.Info)
        policyClient.flush()
        assertEquals(0, policyCalls.get())
        assertTrue(policyTransport.events.isEmpty())
        policyClient.close()
    }

    @Test
    fun `beforeSend validates every supported payload shape`() {
        val payloads = mapOf(
            DebugBundleEventTypes.FRONTEND_EXCEPTION to JsonObject(
                mapOf(
                    "name" to JsonPrimitive("TypeError"),
                    "message" to JsonPrimitive("failed"),
                    "stack" to JsonPrimitive("stack"),
                    "breadcrumbs" to JsonArray(emptyList()),
                    "probe_data" to JsonObject(emptyMap()),
                ),
            ),
            DebugBundleEventTypes.FRONTEND_BREADCRUMB to JsonObject(
                mapOf(
                    "breadcrumb_type" to JsonPrimitive("navigation"),
                    "data" to JsonObject(emptyMap()),
                ),
            ),
            DebugBundleEventTypes.LOG_EVENT to JsonObject(
                mapOf(
                    "level" to JsonPrimitive("error"),
                    "message" to JsonPrimitive("failed"),
                    "attributes" to JsonObject(emptyMap()),
                ),
            ),
            DebugBundleEventTypes.REQUEST_EVENT to JsonObject(
                mapOf(
                    "method" to JsonPrimitive("GET"),
                    "path" to JsonPrimitive("/checkout"),
                    "query" to JsonObject(emptyMap()),
                    "headers" to JsonObject(emptyMap()),
                    "response_status" to JsonPrimitive(503),
                    "duration_ms" to JsonPrimitive(25.5),
                    "response_headers" to JsonObject(emptyMap()),
                ),
            ),
            DebugBundleEventTypes.ERROR_SUPPRESSED to JsonObject(
                mapOf(
                    "fingerprint" to JsonPrimitive("fingerprint"),
                    "suppressed_count" to JsonPrimitive(2),
                    "window_seconds" to JsonPrimitive(60),
                    "first_seen" to JsonPrimitive("2026-05-28T10:14:30Z"),
                    "last_seen" to JsonPrimitive("2026-05-28T10:15:30Z"),
                ),
            ),
            DebugBundleEventTypes.PROBE_EVENT to JsonObject(
                mapOf(
                    "label" to JsonPrimitive("checkout.total"),
                    "data" to JsonObject(mapOf("value" to JsonPrimitive(42))),
                    "activation_id" to JsonNull,
                    "probe_label_pattern" to JsonPrimitive("checkout.*"),
                ),
            ),
        )

        payloads.forEach { (eventType, payload) ->
            val original = envelope(eventType, payload)
            val result = applyDebugBundleBeforeSend(
                original,
                DebugBundleBeforeSend { event ->
                    event.copy(context = JsonObject(mapOf("validated" to JsonPrimitive(true))))
                },
            )
            assertEquals(JsonPrimitive(true), result?.context?.get("validated"))
        }
    }

    @Test
    fun `beforeSend rejects invalid identity payload fields and probe activation`() {
        val original = envelope(
            DebugBundleEventTypes.PROBE_EVENT,
            JsonObject(
                mapOf(
                    "label" to JsonPrimitive("checkout.total"),
                    "data" to JsonObject(emptyMap()),
                    "activation_id" to JsonPrimitive("not-a-uuid"),
                    "probe_label_pattern" to JsonPrimitive("checkout.*"),
                ),
            ),
        )
        val invalidResults = listOf(
            original.copy(schemaVersion = ""),
            original.copy(eventId = "invalid"),
            original.copy(occurredAt = "invalid"),
            original.copy(eventType = "unknown"),
            original.copy(payload = JsonObject(original.payload + ("unexpected" to JsonPrimitive(true)))),
            original,
        )

        invalidResults.forEach { invalid ->
            assertEquals(
                original,
                applyDebugBundleBeforeSend(original, DebugBundleBeforeSend { invalid }),
            )
        }

        val validActivation = original.copy(
            payload = JsonObject(
                original.payload + (
                    "activation_id" to
                        JsonPrimitive("11111111-1111-4111-8111-111111111111")
                    ),
            ),
        )
        assertEquals(
            JsonPrimitive(true),
            applyDebugBundleBeforeSend(
                original,
                DebugBundleBeforeSend {
                    validActivation.copy(context = JsonObject(mapOf("validated" to JsonPrimitive(true))))
                },
            )?.context?.get("validated"),
        )
    }

    private fun envelope(
        eventType: String,
        payload: JsonObject,
    ): DebugBundleEnvelope {
        return DebugBundleEnvelope(
            schemaVersion = "2026-03-01",
            eventId = "22222222-2222-4222-8222-222222222222",
            eventType = eventType,
            sdkName = "@debugbundle/sdk-android",
            sdkVersion = "1.0.0",
            service = DebugBundleServiceDescriptor("checkout", "production"),
            occurredAt = "2026-05-28T10:15:30Z",
            payload = payload,
        )
    }

    private fun newClient(
        transport: RecordingTransport,
        beforeSend: DebugBundleBeforeSend,
    ): DebugBundleClient {
        val client = DebugBundleClient.create(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = tempDir.resolve("${System.nanoTime()}-queue.json"),
            ).withBeforeSend(beforeSend),
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient {
                DebugBundleRemoteConfigResult.Loaded(
                    config = DebugBundleRemoteConfigResponse(
                        capturePolicy = DebugBundleRemoteCapturePolicy(
                            preset = "balanced",
                            captureLogs = "warning",
                            captureRequestEvents = "failures_only",
                            captureBreadcrumbs = "exception_only",
                            captureProbeEvents = "buffer_only",
                        ),
                    ),
                )
            },
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.5 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        )
        client.refreshRemoteConfig()
        return client
    }
}
