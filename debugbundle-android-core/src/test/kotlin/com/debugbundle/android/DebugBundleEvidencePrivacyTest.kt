package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugBundleEvidencePrivacyTest {
    @Test
    fun `optional privacy reduction preserves latest accepted primary and invalid legacy reference`() {
        val json = Json { ignoreUnknownKeys = true }
        val corpus = json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject
        val source = (corpus.getValue("events") as JsonArray).first().jsonObject.getValue("event")
        val original = json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), source)
        val latest = original.copy(
            payload = JsonObject(
                original.payload + mapOf(
                    "message" to JsonPrimitive("latest protected primary"),
                    "exception_evidence" to JsonObject(
                        original.payload.getValue("exception_evidence").jsonObject + ("runtime" to JsonPrimitive("invalid_runtime")),
                    ),
                ),
            ),
            context = JsonObject(mapOf("evidence_reference" to JsonPrimitive("legacy application field"))),
        )
        val protected = protectDebugBundleEvidenceEnvelope(latest, DebugBundleRedactor(emptySet()))
        assertNotNull(protected); assertEquals(JsonPrimitive("latest protected primary"), protected!!.payload["message"]); assertFalse(protected.payload.containsKey("exception_evidence")); assertTrue(protected.isValidBeforeSendEvent())
        assertEquals(JsonPrimitive("legacy application field"), protected.context?.get("evidence_reference")); assertEquals(JsonPrimitive("budget_exceeded"), protected.context?.get("evidence_limits")?.jsonObject?.get("exception_evidence"))
        assertTrue(original.payload.containsKey("exception_evidence"))
    }

    @Test
    fun `broad redactFields preserves validated protocol fields while redacting application data`() {
        val json = Json { ignoreUnknownKeys = true }
        val corpus = json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject
        val source = (corpus.getValue("events") as JsonArray).first().jsonObject.getValue("event")
        val original = json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), source)
        val failureRef = "11111111-1111-4111-8111-111111111111"
        val opId = "22222222-2222-4222-8222-222222222222"
        val enriched = original.copy(
            payload = JsonObject(
                original.payload + mapOf(
                    "browser_event" to JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive("resource_error"),
                            "message" to JsonPrimitive("failed reason=SYNTHETIC_REASON"),
                            "file_name" to JsonPrimitive("https://cdn.example.test/app.js?file=SYNTHETIC_FILE"),
                            "line_number" to JsonPrimitive(1),
                            "column_number" to JsonPrimitive(2),
                            "target" to JsonObject(
                                mapOf(
                                    "tag_name" to JsonPrimitive("script"),
                                    "source_url" to JsonPrimitive("https://cdn.example.test/app.js?tag=SYNTHETIC_TAG"),
                                ),
                            ),
                            "opaque" to JsonPrimitive(true),
                        ),
                    ),
                ),
            ),
            context = JsonObject(
                mapOf(
                    "evidence_reference" to JsonObject(
                        mapOf(
                            "version" to JsonPrimitive(1),
                            "failure_ref" to JsonPrimitive(failureRef),
                            "scope" to JsonObject(mapOf("kind" to JsonPrimitive("operation"), "operation_id" to JsonPrimitive(opId))),
                        ),
                    ),
                    "evidence_limits" to JsonObject(mapOf("version" to JsonPrimitive(1), "execution" to JsonPrimitive("unsupported"))),
                    "payout" to JsonObject(
                        mapOf(
                            "tag" to JsonPrimitive("SYNTHETIC_TAG"),
                            "reference" to JsonPrimitive("SYNTHETIC_REF"),
                            "reason" to JsonPrimitive("SYNTHETIC_REASON"),
                            "file" to JsonPrimitive("SYNTHETIC_FILE"),
                            "classification" to JsonPrimitive("SYNTHETIC_CLASS"),
                        ),
                    ),
                ),
            ),
        )
        val broad = DebugBundleRedactor(setOf("tag", "reference", "reason", "file", "classification"))
        val protected = protectDebugBundleEvidenceEnvelope(enriched, broad)
        assertNotNull(protected)
        assertTrue(protected!!.payload.containsKey("exception_evidence"))
        assertEquals(JsonPrimitive("script"), protected.payload["browser_event"]?.jsonObject?.get("target")?.jsonObject?.get("tag_name"))
        assertEquals(JsonPrimitive(failureRef), protected.context?.get("evidence_reference")?.jsonObject?.get("failure_ref"))
        assertEquals(JsonPrimitive("unsupported"), protected.context?.get("evidence_limits")?.jsonObject?.get("execution"))
        val payout = protected.context?.get("payout")?.jsonObject
        assertEquals(JsonPrimitive("[REDACTED]"), payout?.get("tag"))
        assertEquals(JsonPrimitive("[REDACTED]"), payout?.get("reference"))
        assertEquals(JsonPrimitive("[REDACTED]"), payout?.get("reason"))
        assertEquals(JsonPrimitive("[REDACTED]"), payout?.get("file"))
        assertEquals(JsonPrimitive("[REDACTED]"), payout?.get("classification"))
    }

    @Test
    fun `protocol privacy covers rejection, execution, resource observations, diagnostic context, and queue pressure`() {
        val failureRef = "11111111-1111-4111-8111-111111111111"
        val opId = "22222222-2222-4222-8222-222222222222"
        val pageId = "33333333-3333-4333-8333-333333333333"
        val attemptId = "44444444-4444-4444-8444-444444444444"
        val sourceEventId = "55555555-5555-4555-8555-555555555555"
        val broad = DebugBundleRedactor(setOf("tag", "reference", "reason", "file", "status", "scope", "state"))

        val resourceObs = JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "items" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive("timing"),
                                "observed_at" to JsonPrimitive("2026-10-26T10:00:00Z"),
                                "source_url" to JsonPrimitive("https://cdn.example.test/app.js"),
                                "availability" to JsonPrimitive("observed"),
                                "response_status" to JsonPrimitive(502),
                                "duration_ms" to JsonPrimitive(18.5),
                                "transfer_size_bytes" to JsonPrimitive(0),
                            ),
                        ),
                        JsonObject(
                            mapOf(
                                "kind" to JsonPrimitive("csp"),
                                "observed_at" to JsonPrimitive("2026-10-26T10:00:00Z"),
                                "source_url" to kotlinx.serialization.json.JsonNull,
                                "effective_directive" to JsonPrimitive("script-src"),
                                "disposition" to JsonPrimitive("enforce"),
                                "document_status_code" to JsonPrimitive(200),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val frontend = DebugBundleEnvelope(
            schemaVersion = "2026-03-01",
            eventId = "66666666-6666-4666-8666-666666666666",
            eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION,
            sdkName = "@debugbundle/sdk-browser",
            sdkVersion = "3.0.0",
            service = DebugBundleServiceDescriptor("web", "production"),
            occurredAt = "2026-10-26T10:00:00Z",
            payload = JsonObject(
                mapOf(
                    "name" to JsonPrimitive("Error"),
                    "message" to JsonPrimitive("boom"),
                    "browser" to JsonObject(mapOf("name" to JsonPrimitive("Chrome"), "version" to JsonPrimitive("120"))),
                    "browser_event" to JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive("resource_error"),
                            "message" to JsonPrimitive("failed reason=SYNTHETIC_REASON"),
                            "file_name" to JsonPrimitive("https://cdn.example.test/app.js?file=SYNTHETIC_FILE"),
                            "line_number" to JsonPrimitive(10),
                            "column_number" to JsonPrimitive(2),
                            "target" to JsonObject(
                                mapOf(
                                    "tag_name" to JsonPrimitive("custom-widget"),
                                    "source_url" to JsonPrimitive("https://cdn.example.test/app.js?tag=SYNTHETIC_TAG"),
                                    "attributes" to JsonObject(mapOf("rel" to JsonPrimitive("preload reason=SYNTHETIC_REASON"), "async" to JsonPrimitive(true))),
                                ),
                            ),
                            "page" to JsonObject(
                                mapOf(
                                    "url" to JsonPrimitive("https://app.example.test/pay?reference=SYNTHETIC_REF"),
                                    "referrer" to kotlinx.serialization.json.JsonNull,
                                    "ready_state" to JsonPrimitive("complete"),
                                    "visibility_state" to JsonPrimitive("visible"),
                                ),
                            ),
                            "opaque" to JsonPrimitive(false),
                        ),
                    ),
                    "rejection_reason" to JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive("error"),
                            "name" to JsonPrimitive("TypeError"),
                            "message" to JsonPrimitive("rejected reason=SYNTHETIC_REASON"),
                            "preview" to JsonPrimitive("tag=SYNTHETIC_TAG"),
                        ),
                    ),
                    "resource_observations" to resourceObs,
                ),
            ),
        )
        val safeFrontend = protectDebugBundleEnvelope(frontend, broad)
        assertNotNull(safeFrontend)
        assertEquals(JsonPrimitive("[REDACTED]"), safeFrontend!!.payload["browser_event"]?.jsonObject?.get("target")?.jsonObject?.get("tag_name"))
        assertEquals(JsonPrimitive("complete"), safeFrontend.payload["browser_event"]?.jsonObject?.get("page")?.jsonObject?.get("ready_state"))
        assertEquals(JsonPrimitive("rejected reason=[REDACTED]"), safeFrontend.payload["rejection_reason"]?.jsonObject?.get("message"))
        assertEquals(2, (safeFrontend.payload["resource_observations"]?.jsonObject?.get("items") as JsonArray).size)

        val backend = DebugBundleEnvelope(
            schemaVersion = "2026-03-01",
            eventId = "77777777-7777-4777-8777-777777777777",
            eventType = "backend_exception",
            sdkName = DEBUG_BUNDLE_ANDROID_SDK_NAME,
            sdkVersion = "3.1.4",
            service = DebugBundleServiceDescriptor("api", "production"),
            occurredAt = "2026-10-26T10:00:00Z",
            payload = JsonObject(
                mapOf(
                    "name" to JsonPrimitive("RuntimeException"),
                    "message" to JsonPrimitive("failed"),
                    "execution" to JsonObject(
                        mapOf(
                            "version" to JsonPrimitive(1),
                            "kind" to JsonPrimitive("job"),
                            "name" to JsonPrimitive("sync reason=SYNTHETIC_REASON"),
                            "elapsed_ms" to JsonPrimitive(12.5),
                            "limit_ms" to JsonPrimitive(5000),
                        ),
                    ),
                ),
            ),
        )
        val safeBackend = protectDebugBundleEnvelope(backend, broad)
        assertEquals(JsonPrimitive("sync reason=[REDACTED]"), safeBackend?.payload?.get("execution")?.jsonObject?.get("name"))

        val recovery = DebugBundleEnvelope(
            schemaVersion = "2026-03-01",
            eventId = "88888888-8888-4888-8888-888888888888",
            eventType = "diagnostic_context",
            sdkName = DEBUG_BUNDLE_ANDROID_SDK_NAME,
            sdkVersion = "3.1.4",
            service = DebugBundleServiceDescriptor("app", "production"),
            occurredAt = "2026-10-26T10:00:00Z",
            payload = JsonObject(
                mapOf(
                    "version" to JsonPrimitive(1),
                    "kind" to JsonPrimitive("recovery"),
                    "failure_ref" to JsonPrimitive(failureRef),
                    "scope" to JsonObject(mapOf("kind" to JsonPrimitive("operation"), "operation_id" to JsonPrimitive(opId))),
                    "attempt_id" to JsonPrimitive(attemptId),
                    "state" to JsonPrimitive("recovery_observed"),
                    "operation" to JsonPrimitive("retry reason=SYNTHETIC_REASON"),
                    "status_code" to JsonPrimitive(200),
                    "duration_ms" to JsonPrimitive(14.0),
                ),
            ),
        )
        val safeRecovery = protectDebugBundleEnvelope(recovery, broad)
        assertEquals(JsonPrimitive("retry reason=[REDACTED]"), safeRecovery?.payload?.get("operation"))
        assertEquals(JsonPrimitive(200), safeRecovery?.payload?.get("status_code"))

        val diagObs = recovery.copy(
            eventId = "99999999-9999-4999-8999-999999999999",
            correlation = DebugBundleCorrelation(sessionId = "sess-123"),
            payload = JsonObject(
                mapOf(
                    "version" to JsonPrimitive(1),
                    "kind" to JsonPrimitive("resource_observation"),
                    "failure_ref" to JsonPrimitive(failureRef),
                    "scope" to JsonObject(mapOf("kind" to JsonPrimitive("page"), "page_id" to JsonPrimitive(pageId))),
                    "source_event_id" to JsonPrimitive(sourceEventId),
                    "observations" to resourceObs,
                ),
            ),
        )
        assertNotNull(protectDebugBundleEnvelope(diagObs, broad))

        val badDiag = recovery.copy(payload = JsonObject(mapOf("version" to JsonPrimitive(1), "kind" to JsonPrimitive("unknown"))))
        assertEquals(null, protectDebugBundleEnvelope(badDiag, broad))

        val suppressed = DebugBundleEnvelope(
            schemaVersion = "2026-03-01",
            eventId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            eventType = DebugBundleEventTypes.ERROR_SUPPRESSED,
            sdkName = DEBUG_BUNDLE_ANDROID_SDK_NAME,
            sdkVersion = "3.1.4",
            service = DebugBundleServiceDescriptor("app", "production"),
            occurredAt = "2026-10-26T10:00:00Z",
            payload = JsonObject(
                mapOf(
                    "fingerprint" to JsonPrimitive("fp"),
                    "suppressed_count" to JsonPrimitive(3),
                    "window_seconds" to JsonPrimitive(60),
                    "first_seen" to JsonPrimitive("2026-10-26T10:00:00Z"),
                    "last_seen" to JsonPrimitive("2026-10-26T10:01:00Z"),
                    "reason" to JsonPrimitive("queue_pressure"),
                    "level" to JsonPrimitive("error"),
                ),
            ),
        )
        assertEquals(JsonPrimitive("queue_pressure"), protectDebugBundleEnvelope(suppressed, broad)?.payload?.get("reason"))
    }
    @Test
    fun `unfit latest primary is withheld without restoring pre-hook content`() {
        val event = DebugBundleEnvelope("2026-03-01", "11111111-1111-4111-8111-111111111111", DebugBundleEventTypes.LOG_EVENT, "@debugbundle/sdk-android", "candidate", DebugBundleServiceDescriptor("fixture", "test"), "2026-10-28T00:00:00Z", payload = JsonObject(mapOf("level" to JsonPrimitive("error"), "message" to JsonPrimitive("latest"), "attributes" to JsonObject((0..300).associate { "field_$it" to JsonPrimitive("x".repeat(16000)) }))))
        assertEquals(null, protectDebugBundleEvidenceEnvelope(event, DebugBundleRedactor(emptySet())))
    }
}
