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
        val latest = original.copy(payload = JsonObject(original.payload + ("message" to JsonPrimitive("latest protected primary"))), context = JsonObject(mapOf("evidence_reference" to JsonPrimitive("legacy application field"))))
        val protected = protectDebugBundleEvidenceEnvelope(latest, DebugBundleRedactor(setOf("classification")))
        assertNotNull(protected); assertEquals(JsonPrimitive("latest protected primary"), protected!!.payload["message"]); assertFalse(protected.payload.containsKey("exception_evidence")); assertTrue(protected.isValidBeforeSendEvent())
        assertEquals(JsonPrimitive("legacy application field"), protected.context?.get("evidence_reference")); assertEquals(JsonPrimitive("budget_exceeded"), protected.context?.get("evidence_limits")?.jsonObject?.get("exception_evidence"))
        assertTrue(original.payload.containsKey("exception_evidence"))
    }
    @Test
    fun `unfit latest primary is withheld without restoring pre-hook content`() {
        val event = DebugBundleEnvelope("2026-03-01", "11111111-1111-4111-8111-111111111111", DebugBundleEventTypes.LOG_EVENT, "@debugbundle/sdk-android", "candidate", DebugBundleServiceDescriptor("fixture", "test"), "2026-10-28T00:00:00Z", payload = JsonObject(mapOf("level" to JsonPrimitive("error"), "message" to JsonPrimitive("latest"), "attributes" to JsonObject((0..300).associate { "field_$it" to JsonPrimitive("x".repeat(16000)) }))))
        assertEquals(null, protectDebugBundleEvidenceEnvelope(event, DebugBundleRedactor(emptySet())))
    }
}
