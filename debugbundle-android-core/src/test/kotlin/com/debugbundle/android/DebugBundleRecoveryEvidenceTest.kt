package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.lang.ref.Reference
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBundleRecoveryEvidenceTest {
    private fun config(enabled: Boolean = true, hook: DebugBundleBeforeSend? = null): DebugBundleConfig =
        DebugBundleConfig(projectToken = "fixture", captureFatalExceptions = false, flushInterval = 1.hours)
            .withEvidenceOptions(DebugBundleEvidenceOptions(captureRecoveryObservations = enabled,
                evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1", "recovery_v1"))))
            .withBeforeSend(hook)
    private fun client(config: DebugBundleConfig, transport: RecordingTransport): DebugBundleClient {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val client = DebugBundleClient.create(config = config, transport = transport, executor = executor,
            clock = { Instant.parse("2026-10-28T00:00:00Z") },
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
        executor.submit {}.get(5, TimeUnit.SECONDS)
        return client
    }
    @Test
    fun `explicit recovery delivers independently and keeps the same failure and operation scope`() {
        val transport = RecordingTransport(); val client = client(config(), transport)
        val failure = UUID.randomUUID().toString(); val attempt = UUID.randomUUID().toString(); val error = IllegalStateException("outer")
        try {
            client.withOperationContext {
                client.captureException(error, DebugBundleEvidenceContext.forFailure(failure))
                client.captureRecovery(DebugBundleRecoveryObservation(failure, attempt, "attempt_completed", statusCode = 200))
            }
            client.flush(); Reference.reachabilityFence(error)
            assertEquals(listOf("frontend_exception", "diagnostic_context"), transport.events.map { it.eventType })
            val original = transport.events[0]; val observation = transport.events[1]
            val reference = original.context?.get("evidence_reference") as JsonObject
            assertEquals(reference["scope"], observation.payload["scope"]); assertEquals(reference["failure_ref"], observation.payload["failure_ref"])
            assertEquals("attempt_completed", (observation.payload["state"] as JsonPrimitive).content)
            assertTrue(observation.isValidBeforeSendEvent())
            val explicitScope = DebugBundleEvidenceScope(operationId = UUID.randomUUID().toString())
            client.captureRecovery(DebugBundleRecoveryObservation(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "recovery_observed", scope = explicitScope))
            client.flush(); assertEquals(3, transport.events.size)
        } finally { client.close() }
    }
    @Test
    fun `local opt in and scope are required before invoking a recovery hook`() {
        val hooks = AtomicInteger(); val transport = RecordingTransport()
        val client = client(config(enabled = false, hook = DebugBundleBeforeSend { hooks.incrementAndGet(); it }), transport)
        try {
            client.captureRecovery(DebugBundleRecoveryObservation(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "recovery_observed", scope = DebugBundleEvidenceScope(operationId = UUID.randomUUID().toString())))
            client.flush(); assertTrue(transport.events.isEmpty()); assertEquals(0, hooks.get())
        } finally { client.close() }
    }
    @Test
    fun `hook may replace diagnostic identity while keeping final receipt ownership`() {
        val transport = RecordingTransport(); val hooks = AtomicInteger(); val replacement = UUID.randomUUID().toString()
        val client = client(config(hook = DebugBundleBeforeSend { hooks.incrementAndGet(); it.copy(eventId = replacement) }), transport)
        try {
            client.captureRecovery(DebugBundleRecoveryObservation(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "recovery_observed", scope = DebugBundleEvidenceScope(operationId = UUID.randomUUID().toString()), sessionId = "0"))
            client.flush(); assertEquals(replacement, transport.events.single().eventId); assertEquals(1, hooks.get())
            assertEquals("0", transport.events.single().correlation?.sessionId)
        } finally { client.close() }
    }
}
