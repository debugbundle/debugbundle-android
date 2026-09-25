package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleRedactor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleDeferredThrowableTest {
    @Test
    fun `cleared and throwing handles keep the protected fallback without retaining raw error`() {
        val error = object : RuntimeException() { override val message: String get() = error("getter failed") }
        val pending = DebugBundleDeferredThrowable(error, DebugBundleRedactor(emptySet()))
        val fallback = event()
        assertEquals(fallback, pending.enrich(fallback))
        pending.clear()
        assertEquals(fallback, pending.enrich(fallback))
    }

    @Test
    fun `worker details preserve exception and structured log evidence after mandatory privacy`() {
        val error = IllegalStateException("failure token=UNSAFE_VALUE")
        val redactor = DebugBundleRedactor(emptySet())
        val exception = DebugBundleDeferredThrowable(error, redactor).enrich(event())
        assertEquals(JsonPrimitive("failure token=[REDACTED]"), exception.payload["message"])
        assertTrue(exception.payload["stack"].toString().contains("DebugBundleDeferredThrowableTest"))
        val log = DebugBundleDeferredThrowable(error, redactor, true).enrich(event().copy(eventType = DebugBundleEventTypes.LOG_EVENT))
        val details = (log.payload["attributes"] as JsonObject)["throwable"] as JsonObject
        assertEquals(JsonPrimitive("failure token=[REDACTED]"), details["message"])
        assertFalse(log.toString().contains("UNSAFE_VALUE"))
        val noMessage = DebugBundleDeferredThrowable(RuntimeException(), redactor).enrich(event())
        assertEquals(JsonPrimitive("RuntimeException"), noMessage.payload["message"])
    }

    private fun event() = DebugBundleEnvelope(
        schemaVersion = "2026-03-01", eventId = "22222222-2222-4222-8222-222222222222",
        eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION, sdkName = "@debugbundle/sdk-android", sdkVersion = "3.0.0",
        service = DebugBundleServiceDescriptor("test", "test"), occurredAt = "2026-09-25T00:00:00Z",
        payload = buildJsonObject { put("name", "Error"); put("message", "safe fallback"); put("stack", "safe fallback") },
    )
}
