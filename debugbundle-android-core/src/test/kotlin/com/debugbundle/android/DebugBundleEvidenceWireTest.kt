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
