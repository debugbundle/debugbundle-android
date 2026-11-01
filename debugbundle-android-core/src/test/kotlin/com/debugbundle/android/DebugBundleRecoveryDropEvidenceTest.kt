package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DebugBundleRecoveryDropEvidenceTest {
    @Test fun `sampling drops release the final hook identity before another outcome`() = verify(false)
    @Test fun `expiry after a hook releases the final identity before another outcome`() = verify(true)
    private fun verify(expiry: Boolean) {
        val accepted = AtomicBoolean(false); val clock = AtomicReference(Instant.parse("2026-10-28T00:00:00Z"))
        val transport = RecordingTransport(); val executor = Executors.newSingleThreadScheduledExecutor()
        val client = DebugBundleClient.create(config = DebugBundleConfig(projectToken = "fixture", captureFatalExceptions = false,
            flushInterval = 1.hours, sampleRate = if (expiry) 1.0 else 0.5,
            offlineQueueTtl = if (expiry) 10.milliseconds else 1.hours)
            .withEvidenceOptions(DebugBundleEvidenceOptions(captureRecoveryObservations = true,
                evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("recovery_v1"))))
            .withBeforeSend(DebugBundleBeforeSend {
                if (expiry && !accepted.get()) clock.set(clock.get().plusMillis(20))
                it.copy(eventId = UUID.randomUUID().toString(), context = JsonObject(mapOf("detail" to JsonPrimitive("x".repeat(15_000)))))
            }), transport = transport, executor = executor, clock = { clock.get() }, random = { if (accepted.get()) 0.0 else 0.9 },
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
        val failure = UUID.randomUUID().toString(); val attempt = UUID.randomUUID().toString(); val scope = DebugBundleEvidenceScope(operationId = UUID.randomUUID().toString())
        try {
            executor.submit {}.get(5, TimeUnit.SECONDS)
            repeat(5) { client.captureRecovery(DebugBundleRecoveryObservation(failure, attempt, "attempt_failed", scope)); client.flush() }
            assertEquals(0, transport.events.size)
            accepted.set(true); client.captureRecovery(DebugBundleRecoveryObservation(failure, attempt, "recovery_observed", scope)); client.flush()
            assertEquals(1, transport.events.size)
        } finally { client.close() }
    }
}
