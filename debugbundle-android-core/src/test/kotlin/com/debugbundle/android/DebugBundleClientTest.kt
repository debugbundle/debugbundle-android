package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.util.Base64
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleClientTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `captureException redacts sensitive fields before transport`() {
        val transport = RecordingTransport()
        val client = newClient(transport = transport)

        client.captureException(
            IllegalStateException("boom"),
            context = mapOf(
                "authorization" to "Bearer secret",
                "nested" to mapOf("password" to "super-secret"),
            ),
        )
        client.flush()

        val event = transport.events.single()
        val payload = event.payload
        val context = event.context as JsonObject
        assertEquals("[REDACTED]", (context["authorization"] as JsonPrimitive).content)
        val nested = context["nested"] as JsonObject
        assertEquals("[REDACTED]", (nested["password"] as JsonPrimitive).content)
        val breadcrumbs = payload["breadcrumbs"] as JsonArray
        assertTrue(breadcrumbs.isEmpty())
        client.close()
    }

    @Test
    fun `screen and lifecycle breadcrumbs attach to exception payload and clear after success`() {
        val transport = RecordingTransport()
        val client = newClient(transport = transport)

        client.recordScreen("Checkout", source = "activity")
        client.recordAppForeground()
        client.captureException(IllegalStateException("boom"))
        client.flush()

        val firstPayload = transport.events.single().payload
        val breadcrumbs = firstPayload["breadcrumbs"] as JsonArray
        assertEquals(2, breadcrumbs.size)

        client.captureException(IllegalStateException("second"))
        client.flush()

        val secondPayload = transport.events.last().payload
        val secondBreadcrumbs = secondPayload["breadcrumbs"] as JsonArray
        assertEquals(0, secondBreadcrumbs.size)
        client.close()
    }

    @Test
    fun `device context provider data is attached to events`() {
        val transport = RecordingTransport()
        val deviceContextProvider = DebugBundleDeviceContextProvider {
            DebugBundleDeviceContext(
                appVersion = "1.2.3",
                buildNumber = "42",
                releaseChannel = "beta",
                osName = "Android",
                osVersion = "16",
                manufacturer = "Google",
                model = "Pixel",
                deviceType = "mobile",
                locale = "en-US",
                timezone = "UTC",
                freeMemoryBytes = 1234L,
            )
        }
        val client = DebugBundleClient.create(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = tempDir.resolve("device-queue.json"),
            ),
            transport = transport,
            remoteConfigClient = balancedRemoteConfigClient(),
            deviceContextProvider = deviceContextProvider,
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }

        client.captureMessage("hello")
        client.flush()

        val device = transport.events.single().device as JsonObject
        assertEquals("Android", (device["os_name"] as JsonPrimitive).content)
        assertEquals("Pixel", (device["model"] as JsonPrimitive).content)
        assertEquals("beta", (device["release_channel"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `request capture records network breadcrumb for later exception context`() {
        val transport = RecordingTransport()
        val client = newClient(transport = transport)

        client.recordScreen("Checkout")
        client.captureRequest(
            request = DebugBundleRequestInfo(method = "POST", url = "/checkout", routeTemplate = "/checkout"),
            response = DebugBundleResponseInfo(statusCode = 503, durationMillis = 150),
        )
        client.captureException(IllegalStateException("boom"))
        client.flush()

        val payload = transport.events.last().payload
        val breadcrumbs = payload["breadcrumbs"] as JsonArray
        assertEquals(2, breadcrumbs.size)
        client.close()
    }

    @Test
    fun `request capture keeps only allowlisted headers by default`() {
        val transport = RecordingTransport()
        val client = newClient(transport = transport)

        client.captureRequest(
            request = DebugBundleRequestInfo(
                method = "POST",
                url = "/checkout",
                headers = mapOf(
                    "Authorization" to "Bearer secret",
                    "Accept" to "application/json",
                    "User-Agent" to "debugbundle-test",
                ),
            ),
            response = DebugBundleResponseInfo(
                statusCode = 503,
                headers = mapOf(
                    "Set-Cookie" to "session=secret",
                    "Traceparent" to "00-abc-123-01",
                ),
            ),
        )
        client.flush()

        val payload = transport.events.single().payload
        val requestHeaders = payload["headers"] as JsonObject
        assertEquals(setOf("accept", "user-agent"), requestHeaders.keys)
        val responseHeaders = payload["response_headers"] as JsonObject
        assertEquals(setOf("traceparent"), responseHeaders.keys)
        client.close()
    }

    @Test
    fun `custom header allowlist can preserve additional headers`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = tempDir.resolve("custom-headers-queue.json"),
                headerAllowlist = DebugBundleConfig.DEFAULT_HEADER_ALLOWLIST + "x-tenant-id",
            ),
        )

        client.captureRequest(
            request = DebugBundleRequestInfo(
                method = "GET",
                url = "/checkout",
                headers = mapOf("X-Tenant-Id" to "tenant-42"),
            ),
            response = DebugBundleResponseInfo(statusCode = 500),
        )
        client.flush()

        val payload = transport.events.single().payload
        val requestHeaders = payload["headers"] as JsonObject
        assertEquals("tenant-42", (requestHeaders["x-tenant-id"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `remote capture policy suppresses warning logs when server requires errors only`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteConfigClient(
                capturePolicy = DebugBundleRemoteCapturePolicy(
                    preset = "minimal",
                    captureLogs = "error",
                    captureRequestEvents = "failures_only",
                    captureBreadcrumbs = "local_only",
                    captureProbeEvents = "buffer_only",
                ),
            ),
        )

        client.refreshRemoteConfig()
        client.captureLog("warning suppressed", DebugBundleLogLevel.Warning)
        client.captureLog("error kept", DebugBundleLogLevel.Error)
        client.flush()

        assertEquals(listOf("error kept"), transport.events.map { (it.payload["message"] as JsonPrimitive).content })
        client.close()
    }

    @Test
    fun `capture request policy off suppresses standalone request event but keeps breadcrumb for exception`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteConfigClient(
                capturePolicy = DebugBundleRemoteCapturePolicy(
                    preset = "minimal",
                    captureLogs = "error",
                    captureRequestEvents = "off",
                    captureBreadcrumbs = "exception_only",
                    captureProbeEvents = "buffer_only",
                ),
            ),
        )

        client.refreshRemoteConfig()
        client.captureRequest(
            request = DebugBundleRequestInfo(method = "GET", url = "/checkout"),
            response = DebugBundleResponseInfo(statusCode = 200, durationMillis = 42),
        )
        client.captureException(IllegalStateException("boom"))
        client.flush()

        assertEquals(listOf(DebugBundleEventTypes.FRONTEND_EXCEPTION), transport.events.map { it.eventType })
        val breadcrumbs = transport.events.single().payload["breadcrumbs"] as JsonArray
        assertEquals(1, breadcrumbs.size)
        client.close()
    }

    @Test
    fun `immediate client error statuses promote standalone request event even when request capture is off`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteConfigClient(
                capturePolicy = DebugBundleRemoteCapturePolicy(
                    preset = "minimal",
                    captureLogs = "error",
                    captureRequestEvents = "off",
                    captureBreadcrumbs = "exception_only",
                    captureProbeEvents = "buffer_only",
                    immediateClientErrorStatuses = listOf(403),
                ),
            ),
        )

        client.refreshRemoteConfig()
        client.captureRequest(
            request = DebugBundleRequestInfo(method = "GET", url = "/checkout"),
            response = DebugBundleResponseInfo(statusCode = 403, durationMillis = 42),
        )
        client.flush()

        assertEquals(listOf(DebugBundleEventTypes.REQUEST_EVENT), transport.events.map { it.eventType })
        client.close()
    }

    @Test
    fun `immediate client error path rules promote only valid configured methods`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteConfigClient(
                capturePolicy = DebugBundleRemoteCapturePolicy(
                    preset = "minimal",
                    captureLogs = "error",
                    captureRequestEvents = "off",
                    captureBreadcrumbs = "exception_only",
                    captureProbeEvents = "buffer_only",
                    immediateClientErrorPathRules = listOf(
                        DebugBundleImmediateClientErrorPathRule(
                            statusCode = 404,
                            pathPattern = "/checkout/*",
                            methods = listOf("POST"),
                        ),
                        DebugBundleImmediateClientErrorPathRule(
                            statusCode = 404,
                            pathPattern = "/admin/*",
                            methods = listOf("TRACE"),
                        ),
                    ),
                ),
            ),
        )

        client.refreshRemoteConfig()
        client.captureRequest(
            request = DebugBundleRequestInfo(method = "POST", url = "/checkout/cart"),
            response = DebugBundleResponseInfo(statusCode = 404, durationMillis = 42),
        )
        client.captureRequest(
            request = DebugBundleRequestInfo(method = "TRACE", url = "/admin/panel"),
            response = DebugBundleResponseInfo(statusCode = 404, durationMillis = 42),
        )
        client.flush()

        assertEquals(listOf(DebugBundleEventTypes.REQUEST_EVENT), transport.events.map { it.eventType })
        assertEquals("/checkout/cart", (transport.events.single().payload["path"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `standalone breadcrumb policy emits frontend breadcrumb events`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteConfigClient(
                capturePolicy = DebugBundleRemoteCapturePolicy(
                    preset = "investigative",
                    captureLogs = "info",
                    captureRequestEvents = "all",
                    captureBreadcrumbs = "standalone",
                    captureProbeEvents = "standalone_when_activated",
                ),
            ),
        )

        client.refreshRemoteConfig()
        client.recordScreen("Checkout")
        client.flush()

        assertEquals(listOf(DebugBundleEventTypes.FRONTEND_BREADCRUMB), transport.events.map { it.eventType })
        val payload = transport.events.single().payload
        assertEquals("screen_transition", (payload["breadcrumb_type"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `batch size triggers automatic flush submission`() {
        val submitted = CountDownLatch(1)
        val transport = RecordingTransport {
            submitted.countDown()
            DebugBundleTransportResult(statusCode = 202)
        }
        val client = newClient(
            transport = transport,
            config = DebugBundleConfig(projectToken = "token", service = "checkout-android", batchSize = 2),
        )

        client.captureMessage("first")
        client.captureMessage("second")

        assertTrue(submitted.await(1, TimeUnit.SECONDS))
        assertEquals(2, transport.events.size)
        client.close()
    }

    @Test
    fun `retryable failures retain buffer and mark client degraded`() {
        val transport = RecordingTransport { DebugBundleTransportResult(statusCode = 429, retryAfter = 1.seconds) }
        val client = newClient(transport = transport)

        client.captureMessage("rate limited")
        client.flush()

        assertEquals(DebugBundleStatus.Degraded, client.status)
        assertEquals(1, transport.events.size)
        client.close()
    }

    @Test
    fun `duplicate suppression emits aggregate after first three events`() {
        val transport = RecordingTransport()
        val client = newClient(transport = transport)

        repeat(4) {
            client.captureException(IllegalStateException("same failure"))
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
        client.close()
    }

    @Test
    fun `session cap drops non exceptions but still ships exceptions`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                maxEventsPerSession = 1,
            ),
        )

        client.captureMessage("first")
        client.captureMessage("second")
        client.captureException(IllegalStateException("boom"))
        client.flush()

        assertEquals(
            listOf(DebugBundleEventTypes.LOG_EVENT, DebugBundleEventTypes.FRONTEND_EXCEPTION),
            transport.events.map { it.eventType },
        )
        client.close()
    }

    @Test
    fun `blank project token leaves client disconnected and no-op`() {
        val transport = RecordingTransport()
        val client = newClient(
            transport = transport,
            config = DebugBundleConfig(projectToken = "", service = "checkout-android"),
        )

        client.captureMessage("ignored")
        client.flush()

        assertEquals(DebugBundleStatus.Disconnected, client.status)
        assertTrue(transport.events.isEmpty())
        client.close()
    }

    @Test
    fun `heavy probes stay dormant in always-on mode`() {
        val transport = RecordingTransport()
        val client = newClient(transport = transport)
        var invoked = false

        client.probe("checkout.tax", options = ProbeOptions(heavy = true)) {
            invoked = true
            mapOf("amount" to 42)
        }
        client.captureException(IllegalStateException("boom"))
        client.flush()

        assertFalse(invoked)
        val payload = transport.events.single().payload
        val probeData = payload["probe_data"]
        assertNotNull(probeData)
        client.close()
    }

    @Test
    fun `probe buffers flush with contract shaped inline items`() {
        val transport = RecordingTransport()
        val client = newClient(transport = transport)

        client.probe("checkout.tax", mapOf("password" to "secret", "amount" to 42))
        client.captureException(IllegalStateException("boom"))
        client.flush()

        val probeData = transport.events.single().payload["probe_data"] as JsonObject
        assertEquals(1, (probeData["version"] as JsonPrimitive).content.toInt())
        val items = probeData["items"] as JsonArray
        val item = items.single() as JsonObject
        assertEquals("checkout.tax", (item["label"] as JsonPrimitive).content)
        assertEquals(JsonNull, item["activation_id"])
        assertEquals("2026-05-28T10:15:30Z", (item["timestamp"] as JsonPrimitive).content)
        val data = item["data"] as JsonObject
        assertEquals("[REDACTED]", (data["password"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `remote probe directives emit standalone probe events and wake heavy probes`() {
        val transport = RecordingTransport()
        val activationId = "11111111-1111-4111-8111-111111111111"
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteProbeConfigClient(
                activeProbes = listOf(remoteProbeDirective(activationId, "checkout.*")),
            ),
        )
        var invoked = false

        client.refreshRemoteConfig()
        client.probe("checkout.tax", options = ProbeOptions(heavy = true)) {
            invoked = true
            mapOf("amount" to 42)
        }
        client.flush()

        assertTrue(invoked)
        val event = transport.events.single()
        assertEquals(DebugBundleEventTypes.PROBE_EVENT, event.eventType)
        assertEquals(activationId, (event.payload["activation_id"] as JsonPrimitive).content)
        assertEquals("checkout.*", (event.payload["probe_label_pattern"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `ingestion response probe directives update active remote probes`() {
        val activationId = "22222222-2222-4222-8222-222222222222"
        var requestCount = 0
        val transport = RecordingTransport {
            requestCount += 1
            DebugBundleTransportResult(
                statusCode = 202,
                probeDirectives = if (requestCount == 1) {
                    listOf(remoteProbeDirective(activationId, "checkout.*"))
                } else {
                    null
                },
            )
        }
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteProbeConfigClient(activeProbes = emptyList()),
        )

        client.captureMessage("prime directives")
        client.flush()
        client.probe("checkout.tax", mapOf("amount" to 42))
        client.flush()

        val probeEvent = transport.events.last()
        assertEquals(DebugBundleEventTypes.PROBE_EVENT, probeEvent.eventType)
        assertEquals(activationId, (probeEvent.payload["activation_id"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `valid trigger token activates matching probes for current session`() {
        val transport = RecordingTransport()
        val activationId = "33333333-3333-4333-8333-333333333333"
        val triggerTokenKey = "trigger-key"
        val client = newClient(
            transport = transport,
            remoteConfigClient = remoteProbeConfigClient(
                activeProbes = emptyList(),
                triggerTokenKey = triggerTokenKey,
            ),
        )
        val token = generateProbeTriggerToken(
            key = triggerTokenKey,
            activationId = activationId,
            labelPattern = "checkout.*",
        )

        client.refreshRemoteConfig()
        assertTrue(client.activateProbeTriggerToken(token))
        client.probe("checkout.tax", mapOf("amount" to 42))
        client.flush()

        val probeEvent = transport.events.single()
        assertEquals(DebugBundleEventTypes.PROBE_EVENT, probeEvent.eventType)
        assertEquals(activationId, (probeEvent.payload["activation_id"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun `remote probe config accepts legacy id field from wire`() {
        val activationId = "44444444-4444-4444-8444-444444444444"
        val response = Json.decodeFromString<DebugBundleRemoteConfigResponse>(
            """
            {
              "probes_enabled": true,
              "remote_probes_enabled": true,
              "active_probes": [
                {
                  "id": "$activationId",
                  "label_pattern": "checkout.*",
                  "service": "checkout-android",
                  "environment": "production",
                  "expires_at": "2036-05-28T10:15:30Z"
                }
              ],
              "poll_interval_ms": 60000
            }
            """.trimIndent(),
        )

        assertEquals(activationId, response.activeProbes.single().effectiveActivationId)
    }

    @Test
    fun `file queue survives client restart after retryable failure`() {
        val queueFile = tempDir.resolve("offline-queue.json")
        val firstTransport = RecordingTransport { DebugBundleTransportResult(statusCode = 500) }
        val firstClient = DebugBundleClient.create(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = queueFile,
            ),
            transport = firstTransport,
            remoteConfigClient = balancedRemoteConfigClient(),
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }

        firstClient.captureMessage("persist me")
        firstClient.flush()
        firstClient.close()

        val secondTransport = RecordingTransport()
        val secondClient = DebugBundleClient.create(
            config = DebugBundleConfig(
                projectToken = "token",
                service = "checkout-android",
                offlineQueuePath = queueFile,
            ),
            transport = secondTransport,
            remoteConfigClient = balancedRemoteConfigClient(),
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }
        secondClient.flush()

        assertEquals(listOf("persist me"), secondTransport.events.map { (it.payload["message"] as JsonPrimitive).content })
        secondClient.close()
    }

    @Test
    fun `startup rewrites pre-upgrade queue bytes before sending historical events`() {
        val queueFile = tempDir.resolve("historical-queue.json")
        val oldEvent = DebugBundleEnvelope(
            schemaVersion = DEBUG_BUNDLE_ANDROID_SCHEMA_VERSION,
            eventId = "22222222-2222-4222-8222-000000000001",
            eventType = DebugBundleEventTypes.LOG_EVENT,
            sdkName = DEBUG_BUNDLE_ANDROID_SDK_NAME,
            sdkVersion = "1.0.0",
            service = DebugBundleServiceDescriptor("checkout-android", "production"),
            occurredAt = "2026-05-28T10:15:30Z",
            payload = buildJsonObject {
                put("level", "error")
                put("message", "failure token=old-queue-secret")
                put("attributes", buildJsonObject { put("apiKey", "old-key") })
            },
        )
        FileDebugBundleQueueStore(queueFile).append(
            listOf(oldEvent),
            Instant.parse("2026-05-28T10:15:30Z").toEpochMilli(),
            DebugBundleQueueLimits(100, 1_000_000, 86_400_000),
        )
        assertTrue(queueFile.toFile().readText().contains("old-queue-secret"))
        val transport = RecordingTransport()
        val client = DebugBundleClient.create(
            config = DebugBundleConfig(projectToken = "token", service = "checkout-android", offlineQueuePath = queueFile),
            transport = transport,
            remoteConfigClient = balancedRemoteConfigClient(),
            clock = { Instant.parse("2026-05-28T10:15:31Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }

        assertTrue(!queueFile.toFile().readText().contains("old-queue-secret"))
        assertTrue(!queueFile.toFile().readText().contains("old-key"))
        client.flush()
        assertEquals("failure token=[REDACTED]", (transport.events.single().payload["message"] as JsonPrimitive).content)
        client.close()
    }

    private fun newClient(
        transport: RecordingTransport,
        config: DebugBundleConfig = DebugBundleConfig(
            projectToken = "token",
            service = "checkout-android",
            offlineQueuePath = tempDir.resolve("queue.json"),
        ),
        remoteConfigClient: DebugBundleRemoteConfigClient = balancedRemoteConfigClient(),
    ): DebugBundleClient {
        return DebugBundleClient.create(
            config = config.copy(flushInterval = 50.milliseconds),
            transport = transport,
            remoteConfigClient = remoteConfigClient,
            clock = { Instant.parse("2026-05-28T10:15:30Z") },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }
    }

    private fun balancedRemoteConfigClient(): DebugBundleRemoteConfigClient {
        return DebugBundleRemoteConfigClient {
            DebugBundleRemoteConfigResult.Loaded(config = DebugBundleRemoteConfigResponse(capturePolicy = balancedPolicy()))
        }
    }

    private fun remoteProbeConfigClient(
        activeProbes: List<DebugBundleRemoteProbeDirective>,
        triggerTokenKey: String? = null,
    ): DebugBundleRemoteConfigClient {
        return DebugBundleRemoteConfigClient {
            DebugBundleRemoteConfigResult.Loaded(
                config = DebugBundleRemoteConfigResponse(
                    probesEnabled = true,
                    remoteProbesEnabled = true,
                    activeProbes = activeProbes,
                    triggerTokenKey = triggerTokenKey,
                    capturePolicy = DebugBundleRemoteCapturePolicy(
                        preset = "investigative",
                        captureLogs = "info",
                        captureRequestEvents = "all",
                        captureBreadcrumbs = "standalone",
                        captureProbeEvents = "standalone_when_activated",
                    ),
                ),
            )
        }
    }

    private fun remoteConfigClient(
        capturePolicy: DebugBundleRemoteCapturePolicy,
    ): DebugBundleRemoteConfigClient {
        return DebugBundleRemoteConfigClient {
            DebugBundleRemoteConfigResult.Loaded(
                config = DebugBundleRemoteConfigResponse(capturePolicy = capturePolicy),
            )
        }
    }

    private fun balancedPolicy(): DebugBundleRemoteCapturePolicy {
        return DebugBundleRemoteCapturePolicy(
            preset = "balanced",
            captureLogs = "warning",
            captureRequestEvents = "failures_only",
            captureBreadcrumbs = "exception_only",
            captureProbeEvents = "buffer_only",
        )
    }

    private fun remoteProbeDirective(
        activationId: String,
        labelPattern: String,
        service: String = "checkout-android",
        environment: String = "production",
    ): DebugBundleRemoteProbeDirective {
        return DebugBundleRemoteProbeDirective(
            activationId = activationId,
            labelPattern = labelPattern,
            service = service,
            environment = environment,
            expiresAt = "2026-05-28T11:15:30Z",
            triggerExpiresAt = "2026-05-28T11:15:30Z",
        )
    }

    private fun generateProbeTriggerToken(
        key: String,
        activationId: String,
        labelPattern: String,
    ): String {
        val payload = DebugBundleProbeTriggerPayload(
            activationId = activationId,
            labelPattern = labelPattern,
            service = "checkout-android",
            environment = "production",
            triggerExpiresAt = "2026-05-28T11:15:30Z",
        )
        val payloadSegment = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signatureSegment = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(payloadSegment.toByteArray(Charsets.UTF_8)))
        return "dbundle_probe_$payloadSegment.$signatureSegment"
    }
}
