package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugBundleExternalEventTest {
    @Test
    fun `react native exception preserves identity error and context through native queue`() {
        val transport = RecordingTransport()
        val client = newClient(transport)

        val captured = client.captureExternalEvent(
            externalEvent(
                eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION,
                payload = mapOf(
                    "name" to "TypeError",
                    "message" to "checkout failed",
                    "stack" to "TypeError: checkout failed\n at Checkout.tsx:42",
                    "breadcrumbs" to emptyList<Any>(),
                    "probe_data" to mapOf("version" to 1, "items" to emptyList<Any>()),
                ),
                context = mapOf("authorization" to "Bearer secret", "screen" to "Checkout"),
            ),
        )
        client.flush()

        assertTrue(captured)
        val event = transport.events.single()
        assertEquals("@debugbundle/sdk-react-native", event.sdkName)
        assertEquals("react-native", event.service.runtime)
        assertEquals("TypeError", (event.payload["name"] as JsonPrimitive).content)
        assertEquals("checkout failed", (event.payload["message"] as JsonPrimitive).content)
        assertEquals(
            "[REDACTED]",
            ((event.context as JsonObject)["authorization"] as JsonPrimitive).content,
        )
        client.close()
    }

    @Test
    fun `external event validation is closed and native request policy remains authoritative`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport,
            capturePolicy = DebugBundleRemoteCapturePolicy(
                preset = "balanced",
                captureLogs = "warning",
                captureRequestEvents = "off",
                captureBreadcrumbs = "exception_only",
                captureProbeEvents = "buffer_only",
            ),
        )

        assertFalse(
            client.captureExternalEvent(
                externalEvent(
                    eventType = DebugBundleEventTypes.LOG_EVENT,
                    payload = mapOf("level" to "error", "message" to "invalid", "attributes" to emptyMap<String, Any>()),
                ) + ("unexpected" to true),
            ),
        )
        assertFalse(
            client.captureExternalEvent(
                externalEvent(
                    eventType = DebugBundleEventTypes.LOG_EVENT,
                    payload = mapOf("level" to "error", "message" to "invalid", "attributes" to emptyMap<String, Any>()),
                ) + ("schema_version" to "unsupported"),
            ),
        )
        assertFalse(
            client.captureExternalEvent(
                externalEvent(
                    eventType = DebugBundleEventTypes.REQUEST_EVENT,
                    payload = requestPayload(status = 200),
                ),
            ),
        )
        assertTrue(
            client.captureExternalEvent(
                externalEvent(
                    eventType = DebugBundleEventTypes.REQUEST_EVENT,
                    payload = requestPayload(status = 503),
                ),
            ),
        )
        client.flush()

        assertEquals(listOf(DebugBundleEventTypes.REQUEST_EVENT), transport.events.map { it.eventType })
        client.close()
    }

    @Test
    fun `external probe uses native activation and preserves react native identity`() {
        val transport = RecordingTransport()
        val directive = DebugBundleRemoteProbeDirective(
            activationId = "11111111-1111-4111-8111-111111111111",
            labelPattern = "checkout.*",
            service = "checkout-rn",
            environment = "production",
            expiresAt = "2036-05-28T10:15:30Z",
        )
        val client = newClient(
            transport,
            capturePolicy = DebugBundleRemoteCapturePolicy(
                preset = "investigative",
                captureLogs = "info",
                captureRequestEvents = "all",
                captureBreadcrumbs = "standalone",
                captureProbeEvents = "standalone_when_activated",
            ),
            directives = listOf(directive),
        )

        assertTrue(client.isExternalProbeActive("checkout.cart"))
        assertTrue(
            client.captureExternalProbe(
                sdkVersion = "1.1.0",
                service = "checkout-rn",
                environment = "production",
                label = "checkout.cart",
                data = listOf("cart", 42),
                occurredAt = "2026-05-28T10:15:30Z",
            ),
        )
        client.flush()

        val event = transport.events.single()
        assertEquals("@debugbundle/sdk-react-native", event.sdkName)
        val probeData = event.payload["data"] as JsonObject
        val value = probeData["value"] as JsonArray
        assertEquals("cart", (value[0] as JsonPrimitive).content)
        assertEquals(42, (value[1] as JsonPrimitive).content.toInt())
        client.close()
    }

    @Test
    fun `external suppression aggregate preserves react native identity`() {
        val transport = RecordingTransport()
        val client = newClient(transport)
        repeat(4) { index ->
            val captured = client.captureExternalEvent(
                externalEvent(
                    eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION,
                    payload = mapOf(
                        "name" to "TypeError",
                        "message" to "duplicate",
                        "stack" to "TypeError: duplicate",
                    ),
                    eventId = "22222222-2222-4222-8222-${(index + 1).toString().padStart(12, '0')}",
                ),
            )
            assertEquals(index < 3, captured)
        }
        client.flush()

        assertEquals(
            listOf(
                DebugBundleEventTypes.FRONTEND_EXCEPTION,
                DebugBundleEventTypes.FRONTEND_EXCEPTION,
                DebugBundleEventTypes.FRONTEND_EXCEPTION,
                DebugBundleEventTypes.ERROR_SUPPRESSED,
            ),
            transport.events.map { it.eventType },
        )
        assertTrue(transport.events.all { it.sdkName == "@debugbundle/sdk-react-native" })
        client.close()
    }

    private fun newClient(
        transport: RecordingTransport,
        capturePolicy: DebugBundleRemoteCapturePolicy = DebugBundleRemoteCapturePolicy(
            preset = "balanced",
            captureLogs = "warning",
            captureRequestEvents = "failures_only",
            captureBreadcrumbs = "exception_only",
            captureProbeEvents = "buffer_only",
        ),
        directives: List<DebugBundleRemoteProbeDirective> = emptyList(),
    ): DebugBundleClient {
        val client = DebugBundleClient.create(
            config = DebugBundleConfig(projectToken = "token", service = "checkout-rn"),
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient {
                DebugBundleRemoteConfigResult.Loaded(
                    DebugBundleRemoteConfigResponse(
                        probesEnabled = true,
                        remoteProbesEnabled = true,
                        activeProbes = directives,
                        capturePolicy = capturePolicy,
                    ),
                )
            },
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        )
        client.refreshRemoteConfig()
        return client
    }

    private fun externalEvent(
        eventType: String,
        payload: Map<String, Any?>,
        context: Map<String, Any?>? = null,
        eventId: String = "22222222-2222-4222-8222-222222222222",
    ): Map<String, Any?> {
        return buildMap {
            put("schema_version", "2026-03-01")
            put("event_id", eventId)
            put("event_type", eventType)
            put("sdk_name", "@debugbundle/sdk-react-native")
            put("sdk_version", "1.1.0")
            put(
                "service",
                mapOf(
                    "name" to "checkout-rn",
                    "environment" to "production",
                    "runtime" to "react-native",
                    "framework" to "react-native",
                ),
            )
            put("occurred_at", "2026-05-28T10:15:30Z")
            put("correlation", mapOf("trace_id" to "trace-rn"))
            context?.let { put("context", it) }
            put("payload", payload)
            put(
                "device",
                mapOf(
                    "app_version" to "1.2.3",
                    "build_number" to "42",
                    "release_channel" to "production",
                ),
            )
        }
    }

    private fun requestPayload(status: Int): Map<String, Any?> {
        return mapOf(
            "method" to "GET",
            "path" to "/checkout",
            "query" to emptyMap<String, Any>(),
            "headers" to emptyMap<String, Any>(),
            "response_status" to status,
            "duration_ms" to 25,
            "response_headers" to emptyMap<String, Any>(),
        )
    }
}
