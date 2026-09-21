package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DebugBundleTelemetryPrivacyTest {
    private val json = Json { explicitNulls = true }

    @Test
    fun `native policy matches the portable privacy cases`() {
        val fixture = json.parseToJsonElement(
            Files.readString(Path.of("..", "tests", "fixtures", "privacy-conformance.json")),
        ) as JsonObject
        assertEquals("telemetry-privacy-v1", (fixture["policy"] as JsonPrimitive).content)
        val cases = fixture["cases"] as JsonArray
        assertEquals(24, cases.size)
        cases.forEach { item ->
            val case = item as JsonObject
            val actual = DebugBundleRedactor(emptySet()).sanitize(case["input"])
            assertEquals(case["expected"], actual, (case["id"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `custom keys add to mandatory baseline and oversized strings are withheld`() {
        val redactor = DebugBundleRedactor(setOf("customer_code"))
        val output = redactor.sanitize(
            mapOf("password" to "secret", "customer_code" to "private", "message" to "safe"),
        ) as JsonObject
        assertEquals(JsonPrimitive("[REDACTED]"), output["password"])
        assertEquals(JsonPrimitive("[REDACTED]"), output["customer_code"])
        assertEquals(JsonPrimitive("safe"), output["message"])
        val oversized = redactor.sanitize("x".repeat(2048) + " password=secret") as JsonPrimitive
        assertEquals("[REDACTED]", oversized.content)
        assertFalse(oversized.content.contains("secret"))
    }
}
