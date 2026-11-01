package com.debugbundle.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DebugBundleEvidenceCapabilitiesTest {
    @Test
    fun `reader proof matches the shared closed descriptor cases`() {
        val corpus = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject
        for (fixture in corpus.getValue("capabilities") as JsonArray) {
            val record = fixture.jsonObject
            assertEquals((record.getValue("features") as JsonArray).map { it.jsonPrimitive.content }, DebugBundleEvidenceCapabilities.normalize(record["input"]).features)
        }
        val state = DebugBundleEvidenceDeliveryState(null)
        assertFalse(state.supports("exception_evidence_v1"))
        state.observe(Json.parseToJsonElement("""{"version":1,"features":["exception_evidence_v1"]}"""))
        assertEquals(listOf("exception_evidence_v1"), state.descriptor().features)
        state.observe(null); assertFalse(state.supports("exception_evidence_v1"))
    }
    @Test
    fun `evidence configuration preserves old constructors hook builders and independent pin ownership`() {
        val features = mutableListOf("exception_evidence_v1")
        val options = DebugBundleEvidenceOptions(captureExceptionEvidence = false, captureRecoveryObservations = true, evidenceCapabilities = DebugBundleEvidenceCapabilities(features = features))
        val hook = DebugBundleBeforeSend { it }
        val config = DebugBundleConfig(projectToken = "fixture").withBeforeSend(hook).withEvidenceOptions(options).withBeforeSend(hook)
        features[0] = "changed"
        assertEquals(hook, config.beforeSend); assertFalse(config.evidenceOptions.captureExceptionEvidence); assertEquals(true, config.evidenceOptions.captureRecoveryObservations)
        assertEquals(listOf("exception_evidence_v1"), config.evidenceOptions.evidenceCapabilities?.features)
        val normalized = normalizeDebugBundleConfig(config)
        assertEquals(hook, normalized.beforeSend); assertFalse(normalized.evidenceOptions.captureExceptionEvidence); assertEquals(true, normalized.evidenceOptions.captureRecoveryObservations); assertEquals(listOf("exception_evidence_v1"), normalized.evidenceOptions.evidenceCapabilities?.features)
        assertEquals(false, DebugBundleConfig().evidenceOptions.captureRecoveryObservations)
    }
    @Test
    fun `serialized descriptors include the required defaulted protocol fields`() {
        for (descriptor in listOf(DebugBundleEvidenceCapabilities(), DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1")))) {
            val wire = Json.encodeToJsonElement(DebugBundleEvidenceCapabilities.serializer(), descriptor).jsonObject
            assertEquals(setOf("version", "features"), wire.keys)
            assertEquals(descriptor.features, DebugBundleEvidenceCapabilities.normalize(wire).features)
        }
    }

}
