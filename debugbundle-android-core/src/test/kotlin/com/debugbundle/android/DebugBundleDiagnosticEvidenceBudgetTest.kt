package com.debugbundle.android

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleDiagnosticEvidenceBudgetTest {
    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private val scope = JsonObject(mapOf("kind" to JsonPrimitive("operation"), "operation_id" to JsonPrimitive(UUID.randomUUID().toString())))
    private val failure = UUID.randomUUID().toString()
    private val attempt = UUID.randomUUID().toString()
    private fun event(at: Instant = now, failureId: String = failure, attemptId: String = attempt, padding: Int = 0): DebugBundleEnvelope = DebugBundleEnvelope(
        schemaVersion = "2026-03-01", eventId = UUID.randomUUID().toString(), eventType = "diagnostic_context",
        sdkName = "@debugbundle/sdk-android", sdkVersion = "3.1.0", service = DebugBundleServiceDescriptor("fixture", "test"),
        occurredAt = at.toString(), payload = JsonObject(mapOf("version" to JsonPrimitive(1), "kind" to JsonPrimitive("recovery"),
            "failure_ref" to JsonPrimitive(failureId), "scope" to scope, "attempt_id" to JsonPrimitive(attemptId), "state" to JsonPrimitive("attempt_completed"))),
        context = if (padding == 0) null else JsonObject(mapOf("detail" to JsonPrimitive("x".repeat(padding)))),
    )
    @Test
    fun `acknowledgement releases pending bytes without refunding observation or attempt allowances`() {
        val budget = DebugBundleDiagnosticBudget()
        repeat(20) { val event = event(); assertTrue(budget.reserve(event)); budget.release(event.eventId) }
        assertFalse(budget.reserve(event()))
        val attempts = DebugBundleDiagnosticBudget()
        repeat(5) { val event = event(attemptId = UUID.randomUUID().toString()); assertTrue(attempts.reserve(event)); attempts.release(event.eventId) }
        assertFalse(attempts.reserve(event(attemptId = UUID.randomUUID().toString())))
    }
    @Test
    fun `offline ownership cannot renew expired windows and total retained bytes include metadata`() {
        val budget = DebugBundleDiagnosticBudget(); val original = event(); assertTrue(budget.reserve(original))
        assertFalse(budget.reserve(event(at = now.plusSeconds(301))))
        assertTrue(budget.eligible(original))
        assertFalse(budget.reserve(event(failureId = UUID.randomUUID().toString(), padding = 65_000)))
        val windows = DebugBundleDiagnosticBudget()
        repeat(50) {
            val primary = event(failureId = UUID.randomUUID().toString()).let { it.copy(eventType = "frontend_exception", context = JsonObject(mapOf("evidence_reference" to JsonObject(it.payload.filterKeys { key -> key in setOf("version", "failure_ref", "scope") })))) }
            assertTrue(windows.register(primary))
        }
        assertFalse(windows.reserve(event(failureId = UUID.randomUUID().toString())))
    }
    @Test
    fun `latest hook identity is owned and changed scope cannot borrow the original receipt`() {
        val budget = DebugBundleDiagnosticBudget(); val original = event(); assertTrue(budget.reserve(original))
        val replacement = original.copy(eventId = UUID.randomUUID().toString())
        assertTrue(budget.replace(original.eventId, replacement)); assertFalse(budget.eligible(original)); assertTrue(budget.eligible(replacement))
        val foreign = replacement.copy(correlation = DebugBundleCorrelation(traceId = "other"))
        assertFalse(budget.eligible(foreign))
        budget.release(replacement.eventId); assertFalse(budget.eligible(replacement))
    }
    @Test
    fun `resource bridge observation requires the same registered primary and coalesces once`() {
        val budget = DebugBundleDiagnosticBudget()
        val corpus = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject
        val original = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(DebugBundleEnvelope.serializer(), (corpus.getValue("events") as JsonArray).first().jsonObject.getValue("event"))
            .copy(occurredAt = now.toString(), service = DebugBundleServiceDescriptor("fixture", "test"), context = JsonObject(mapOf("evidence_reference" to JsonObject(mapOf("version" to JsonPrimitive(1), "failure_ref" to JsonPrimitive(failure), "scope" to scope)))))
        assertTrue(budget.register(original))
        val resource = event().let { it.copy(payload = JsonObject(it.payload.filterKeys { key -> key !in setOf("attempt_id", "state") } + mapOf(
            "kind" to JsonPrimitive("resource_observation"), "source_event_id" to JsonPrimitive(original.eventId), "observations" to JsonObject(emptyMap())))) }
        assertTrue(budget.reserve(resource)); budget.release(resource.eventId)
        assertFalse(budget.reserve(resource.copy(eventId = UUID.randomUUID().toString())))
    }
}
