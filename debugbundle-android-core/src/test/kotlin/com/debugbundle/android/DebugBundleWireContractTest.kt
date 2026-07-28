package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleWireContractTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `frontend exception serializes as the canonical mobile envelope`() {
        val transport = RecordingTransport()
        val client = newClient(transport)

        client.recordScreen("Checkout")
        client.captureException(
            IllegalStateException("checkout failed"),
            context = mapOf("screen" to "Checkout"),
        )
        client.flush()

        val event = Json.parseToJsonElement(Json.encodeToString(transport.events.single())).jsonObject
        assertEquals("2026-03-01", event["schema_version"]?.toString()?.trim('"'))
        assertNotNull(event["event_id"])
        assertFalse("device" in event)
        assertEquals("Checkout", event["context"]?.jsonObject?.get("screen")?.toString()?.trim('"'))

        val payload = event["payload"]!!.jsonObject
        assertEquals(
            setOf("name", "message", "stack", "breadcrumbs", "probe_data", "device"),
            payload.keys,
        )
        assertFalse("error" in payload)
        assertFalse("context" in payload)

        val device = payload["device"]!!.jsonObject
        assertEquals("Android", device["os"]?.jsonObject?.get("name")?.toString()?.trim('"'))
        assertEquals("mobile", device["device_type"]?.toString()?.trim('"'))
        assertEquals("1.2.3", device["app_version"]?.toString()?.trim('"'))
        client.close()
    }

    @Test
    fun `log request breadcrumb probe and suppression payloads stay closed and carry device`() {
        val activationId = "11111111-1111-4111-8111-111111111111"
        val transport = RecordingTransport()
        val client = newClient(
            transport,
            remoteConfigClient = DebugBundleRemoteConfigClient {
                DebugBundleRemoteConfigResult.Loaded(
                    DebugBundleRemoteConfigResponse(
                        probesEnabled = true,
                        remoteProbesEnabled = true,
                        activeProbes = listOf(
                            DebugBundleRemoteProbeDirective(
                                activationId = activationId,
                                labelPattern = "checkout.*",
                                service = "checkout-android",
                                environment = "production",
                                expiresAt = "2036-05-28T10:15:30Z",
                            ),
                        ),
                        capturePolicy = DebugBundleRemoteCapturePolicy(
                            preset = "investigative",
                            captureLogs = "info",
                            captureRequestEvents = "all",
                            captureBreadcrumbs = "standalone",
                            captureProbeEvents = "standalone_when_activated",
                        ),
                    ),
                )
            },
        )

        client.captureLog("log", DebugBundleLogLevel.Error, mapOf("logger" to "checkout"))
        client.captureRequest(
            DebugBundleRequestInfo(method = "GET", url = "https://shop.example/checkout?cart=42"),
            DebugBundleResponseInfo(statusCode = 503, durationMillis = 25),
        )
        client.recordScreen("Checkout")
        client.probe("checkout.cart", listOf("cart", 42))
        repeat(4) {
            client.captureException(IllegalStateException("same failure"))
        }
        client.flush()

        val events = transport.events.associateBy { it.eventType }
        assertClosedPayload(events.getValue(DebugBundleEventTypes.LOG_EVENT), setOf("level", "message", "attributes", "device"))
        assertClosedPayload(
            events.getValue(DebugBundleEventTypes.REQUEST_EVENT),
            setOf(
                "method",
                "path",
                "query",
                "headers",
                "response_status",
                "duration_ms",
                "response_headers",
                "device",
            ),
        )
        assertClosedPayload(
            events.getValue(DebugBundleEventTypes.FRONTEND_BREADCRUMB),
            setOf("breadcrumb_type", "route", "data", "device"),
        )
        assertClosedPayload(
            events.getValue(DebugBundleEventTypes.PROBE_EVENT),
            setOf("label", "data", "activation_id", "probe_label_pattern", "device"),
        )
        assertClosedPayload(
            events.getValue(DebugBundleEventTypes.ERROR_SUPPRESSED),
            setOf("fingerprint", "suppressed_count", "window_seconds", "first_seen", "last_seen", "device"),
        )

        val requestPayload = events.getValue(DebugBundleEventTypes.REQUEST_EVENT).payload
        assertEquals("/checkout", requestPayload["path"]?.toString()?.trim('"'))
        assertFalse("url" in requestPayload)
        assertTrue(requestPayload["query"] is JsonObject)
        assertTrue(events.values.all { it.payload["device"] is JsonObject })
        client.close()
    }

    private fun assertClosedPayload(event: DebugBundleEnvelope, expectedKeys: Set<String>) {
        assertEquals(expectedKeys, event.payload.keys)
        assertFalse("context" in event.payload)
    }

    private fun newClient(
        transport: RecordingTransport,
        remoteConfigClient: DebugBundleRemoteConfigClient = DebugBundleRemoteConfigClient {
            DebugBundleRemoteConfigResult.Loaded(
                DebugBundleRemoteConfigResponse(
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
    ): DebugBundleClient {
        val deviceProvider = DebugBundleDeviceContextProvider {
            DebugBundleDeviceContext(
                appVersion = "1.2.3",
                buildNumber = "42",
                releaseChannel = "production",
                osName = "Android",
                osVersion = "16",
                apiLevel = 36,
                manufacturer = "Google",
                model = "Pixel",
                deviceType = "mobile",
                screenWidth = 1080,
                screenHeight = 2400,
                locale = "en-US",
                timezone = "UTC",
                connectionType = "wifi",
                batteryLevel = 80.0,
                batteryCharging = false,
                freeDiskBytes = 10_000,
                freeMemoryBytes = 20_000,
                rooted = false,
            )
        }
        return DebugBundleClient.create(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = tempDir.resolve("wire-queue.json"),
            ),
            transport = transport,
            remoteConfigClient = remoteConfigClient,
            deviceContextProvider = deviceProvider,
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }
    }
}
