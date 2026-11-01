package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleOfflineEvidenceTest {
    @TempDir lateinit var directory: Path
    @Test
    fun `unknown startup policy holds offline recovery while original errors still deliver`() {
        val now = Instant.parse("2026-10-28T00:00:00Z"); val path = directory.resolve("queue.json")
        val config = DebugBundleConfig(projectToken = "fixture", captureFatalExceptions = false, offlineQueuePath = path, flushInterval = 1.hours)
            .withEvidenceOptions(DebugBundleEvidenceOptions(captureRecoveryObservations = true,
                evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("recovery_v1"))))
        val observation = DebugBundleEnvelope(schemaVersion = "2026-03-01", eventId = UUID.randomUUID().toString(), eventType = "diagnostic_context",
            sdkName = "@debugbundle/sdk-android", sdkVersion = "3.1.0", service = DebugBundleServiceDescriptor("fixture", "test"), occurredAt = now.toString(),
            payload = JsonObject(mapOf("version" to JsonPrimitive(1), "kind" to JsonPrimitive("recovery"), "failure_ref" to JsonPrimitive(UUID.randomUUID().toString()),
                "scope" to JsonObject(mapOf("kind" to JsonPrimitive("operation"), "operation_id" to JsonPrimitive(UUID.randomUUID().toString()))),
                "attempt_id" to JsonPrimitive(UUID.randomUUID().toString()), "state" to JsonPrimitive("recovery_observed"))))
        assertTrue(observation.isValidBeforeSendEvent())
        val store = FileDebugBundleQueueStore(path); store.append(listOf(observation), now.toEpochMilli(), debugBundleQueueLimits(config))
        val response = AtomicReference<DebugBundleRemoteConfigResult>(DebugBundleRemoteConfigResult.Failed)
        val executor = Executors.newSingleThreadScheduledExecutor(); val transport = RecordingTransport()
        val client = DebugBundleClient.create(config = config, queueStore = store, transport = transport, executor = executor, clock = { now },
            remoteConfigClient = DebugBundleRemoteConfigClient { response.get() })
        try {
            executor.submit {}.get(5, TimeUnit.SECONDS)
            client.captureMessage("ordinary original", DebugBundleLogLevel.Error); client.flush()
            assertEquals(listOf("log_event"), transport.events.map { it.eventType })
            assertEquals(listOf(observation.eventId), store.snapshot(now.toEpochMilli(), debugBundleQueueLimits(config)).map { it.envelope.eventId })
            response.set(DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse())); client.refreshRemoteConfig(); client.flush()
            assertEquals(listOf("log_event", "diagnostic_context"), transport.events.map { it.eventType })
            assertEquals(observation.eventId, transport.events.last().eventId); assertTrue(store.snapshot(now.toEpochMilli(), debugBundleQueueLimits(config)).isEmpty())
        } finally { client.close() }
    }
}
