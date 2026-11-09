package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import java.nio.file.Files
import java.nio.file.Path
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugBundleTelemetryPrivacyTest {
    private val json = Json { explicitNulls = true }

    @Test
    fun `repeated structured evidence privacy stays within its allocation budget`() {
        val meter = ManagementFactory.getThreadMXBean() as ThreadMXBean
        meter.isThreadAllocatedMemoryEnabled = true
        val redactor = DebugBundleRedactor(setOf("customer_code"))
        val frame = JsonObject(mapOf("function" to JsonPrimitive("application.run"),
            "filename" to JsonPrimitive("Application.kt"), "line" to JsonPrimitive(42)))
        val input = JsonObject(mapOf("frames" to JsonArray(List(32) { frame }),
            "customer_code" to JsonPrimitive("private"), "password" to JsonPrimitive("secret")))
        repeat(10) { redactor.sanitize(input) }
        val thread = Thread.currentThread().id
        val before = meter.getThreadAllocatedBytes(thread)
        repeat(20) {
            val result = redactor.sanitize(input) as JsonObject
            assertEquals(JsonPrimitive("[REDACTED]"), result["customer_code"])
            assertEquals(JsonPrimitive("[REDACTED]"), result["password"])
            assertEquals(input["frames"], result["frames"])
        }
        val allocated = meter.getThreadAllocatedBytes(thread) - before
        assertTrue(allocated < 8 * 1024 * 1024, "Bounded privacy allocated $allocated bytes for twenty 32-frame inputs")
    }

    @Test
    fun `repeated empty containers remain values while actual cycles are bounded`() {
        val redactor = DebugBundleRedactor(emptySet()); val shared = emptyList<Any>()
        val output = redactor.sanitize(mapOf("breadcrumbs" to shared, "probe_data" to mapOf("version" to 1, "items" to shared))) as JsonObject
        assertEquals(JsonArray(emptyList()), output["breadcrumbs"])
        assertEquals(JsonArray(emptyList()), (output["probe_data"] as JsonObject)["items"])
        val cycle = linkedMapOf<String, Any?>(); cycle["self"] = cycle
        assertEquals(JsonPrimitive("[Circular]"), (redactor.sanitize(cycle) as JsonObject)["self"])
    }

    @Test
    fun `native policy matches the portable privacy cases`() {
        val fixture = json.parseToJsonElement(
            Files.readString(Path.of("..", "tests", "fixtures", "privacy-conformance.json")),
        ) as JsonObject
        assertEquals("telemetry-privacy-v1", (fixture["policy"] as JsonPrimitive).content)
        val cases = fixture["cases"] as JsonArray
        assertEquals(27, cases.size)
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
