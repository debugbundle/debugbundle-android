package com.debugbundle.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DebugBundleEvidenceWireTest {
    @Test
    fun `shared hook byte boundaries preserve owner policy`() {
        val budgets = json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/before-send-budget.json")).readText()).jsonObject
        val original = json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), budgets.getValue("original"))
        for (raw in budgets.getValue("cases") as JsonArray) {
            val fixture = raw.jsonObject
            val candidate = json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), fixture.getValue("event"))
            val result = requireNotNull(applyDebugBundleBeforeSend(original, DebugBundleBeforeSend { candidate }))
            val outcome = fixture.getValue("outcome").jsonPrimitive.content
            val id = fixture.getValue("id").jsonPrimitive.content
            assertEquals(JsonPrimitive(if (outcome == "fallback") "owner removed" else "owner replacement"), result.payload["message"], id)
            assertEquals(true, result.isValidBeforeSendEvent(), id)
            if (outcome == "fallback") continue
            assertEquals(outcome == "keep", result.payload.containsKey("exception_evidence"), id)
            assertEquals(JsonPrimitive("preserved"), result.context?.get("owner_field"), id)
            if (id.endsWith("-legacy")) assertEquals(true, result.context?.containsKey("evidence_reference"), id)
            if (id.endsWith("-typed") && outcome == "reduce") assertEquals(false, result.context?.containsKey("evidence_reference"), id)
        }
    }

    @Test
    fun `combined evidence budget never restores owner removed content`() {
        val original = json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), corpus().getValue("events").jsonArrayEvent("legacy_error"))
        val source = json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), corpus().getValue("events").jsonArrayEvent("combined_optional_bytes"))
        val replacement = source.copy(payload = JsonObject(source.payload + ("message" to JsonPrimitive("owner replacement"))))
        val result = requireNotNull(applyDebugBundleBeforeSend(original, DebugBundleBeforeSend { replacement }))
        assertEquals(JsonPrimitive("owner replacement"), result.payload["message"])
        assertEquals(true, result.isValidBeforeSendEvent())
        assertEquals(false, result.payload.containsKey("exception_evidence"))
        assertEquals(true, replacement.payload.containsKey("exception_evidence"))
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private fun corpus(): JsonObject = json.parseToJsonElement(requireNotNull(javaClass.getResource("/evidence/evidence-wire-cases.json")).readText()).jsonObject

    @Test
    fun `owned evidence follows every shared accepting semantic case`() {
        for (fixture in corpus().getValue("events") as JsonArray) {
            val record = fixture.jsonObject
            assertEquals(record.getValue("valid").jsonPrimitive.boolean, DebugBundleEvidenceWire.accepts(record.getValue("event")), record.getValue("id").jsonPrimitive.content)
        }
    }

    @Test
    fun `accepting byte count follows JavaScript serialization vectors`() {
        for (fixture in corpus().getValue("json_sizes") as JsonArray) {
            val record = fixture.jsonObject
            assertEquals(record.getValue("bytes").jsonPrimitive.int, DebugBundleAcceptingJsonSize.bytes(record.getValue("value")))
        }
    }

    @Test
    fun `closed hook validation rejects an invalid nested replacement without losing protected original`() {
        val original = json.decodeFromJsonElement(DebugBundleEnvelope.serializer(), corpus().getValue("events").jsonArrayEvent("legacy_error"))
        val result = applyDebugBundleBeforeSend(original, DebugBundleBeforeSend { candidate ->
            candidate.copy(eventType = DebugBundleEventTypes.LOG_EVENT, payload = JsonObject(mapOf("level" to JsonPrimitive("error"), "message" to JsonPrimitive("invalid replacement"), "attributes" to JsonPrimitive("not-an-object"))))
        })
        assertSame(original, result)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArrayEvent(id: String): kotlinx.serialization.json.JsonElement =
        (this as JsonArray).single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject.getValue("event")
}
