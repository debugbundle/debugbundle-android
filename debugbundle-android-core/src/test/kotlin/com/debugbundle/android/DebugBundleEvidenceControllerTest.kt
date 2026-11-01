package com.debugbundle.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugBundleEvidenceControllerTest {
    private fun event(): DebugBundleEnvelope {
        val json = Json { ignoreUnknownKeys = true }
        val corpus = json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject
        return json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), (corpus.getValue("events") as JsonArray).first().jsonObject.getValue("event"))
    }
    @Test
    fun `optional original evidence requires current reader proof and local permission`() {
        val original = event(); val controller = DebugBundleEvidenceController(DebugBundleEvidenceOptions())
        assertFalse(controller.filter(original, true)!!.payload.containsKey("exception_evidence"))
        controller.observe(Json.parseToJsonElement("""{"version":1,"features":["exception_evidence_v1"]}"""))
        assertTrue(controller.canCaptureExceptions()); assertTrue(controller.filter(original, true)!!.payload.containsKey("exception_evidence"))
        controller.observe(null); assertFalse(controller.canCaptureExceptions()); assertFalse(controller.filter(original, true)!!.payload.containsKey("exception_evidence")); assertTrue(original.payload.containsKey("exception_evidence"))
        val disabled = DebugBundleEvidenceController(DebugBundleEvidenceOptions(captureExceptionEvidence = false, evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1"))))
        assertFalse(disabled.canCaptureExceptions()); assertFalse(disabled.filter(original, true)!!.payload.containsKey("exception_evidence"))
    }
    @Test
    fun `unknown legacy references survive proof filtering`() {
        val original = event().copy(context = JsonObject(mapOf("evidence_reference" to JsonPrimitive("legacy application value"))))
        assertTrue(DebugBundleEvidenceController(DebugBundleEvidenceOptions()).filter(original, false)!!.context!!.containsKey("evidence_reference"))
    }
    @Test
    fun `advertised native bridge features intersect local settings and diagnostic policy`() {
        val controller = DebugBundleEvidenceController(DebugBundleEvidenceOptions(evidenceCapabilities = DebugBundleEvidenceCapabilities(features = DebugBundleEvidenceCapabilities.KNOWN)))
        assertTrue(controller.capabilities(true).features.contains("resource_observations_v1"))
        assertFalse(controller.capabilities(true).features.contains("recovery_v1"))
        assertFalse(controller.capabilities(false).features.contains("resource_observations_v1"))
        controller.observe(null); assertTrue(controller.capabilities(true).features.isEmpty())
    }

}
