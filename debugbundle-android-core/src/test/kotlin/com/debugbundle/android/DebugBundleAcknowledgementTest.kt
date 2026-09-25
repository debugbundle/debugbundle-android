package com.debugbundle.android

import com.debugbundle.android.testkit.RecordingTransport
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class DebugBundleAcknowledgementTest {
    @Test
    fun `all rejected acknowledgement never reports delivery success`() {
        val queue = InMemoryDebugBundleQueueStore()
        val transport = RecordingTransport {
            DebugBundleTransportResult(
                statusCode = 202,
                acknowledgement = DebugBundleIngestionAcknowledgement(
                    accepted = 0,
                    rejected = 1,
                    errors = listOf(DebugBundleIngestionError(index = 0, reason = "invalid_event")),
                ),
            )
        }
        val client = newClient(transport, queue)

        client.captureMessage("rejected", DebugBundleLogLevel.Error)
        client.flush()

        assertEquals(DebugBundleStatus.Disconnected, client.status)
        assertNull(client.lastEventAt)
        assertEquals(0, queue.snapshot(NOW.toEpochMilli(), LIMITS).size)
        client.close()
    }

    @Test
    fun `partial acknowledgement records accepted delivery and terminally removes accounted batch`() {
        val queue = InMemoryDebugBundleQueueStore()
        val transport = RecordingTransport {
            DebugBundleTransportResult(
                statusCode = 202,
                acknowledgement = DebugBundleIngestionAcknowledgement(
                    accepted = 1,
                    rejected = 1,
                    errors = listOf(DebugBundleIngestionError(index = 1, reason = "capture_policy_rejected")),
                ),
            )
        }
        val client = newClient(transport, queue)

        client.captureMessage("accepted", DebugBundleLogLevel.Error)
        client.captureMessage("rejected", DebugBundleLogLevel.Error)
        client.flush()

        assertEquals(DebugBundleStatus.Healthy, client.status)
        assertNotNull(client.lastEventAt)
        assertEquals(0, queue.snapshot(NOW.toEpochMilli(), LIMITS).size)
        client.close()
    }

    @Test
    fun `inconsistent acknowledgement is a protocol failure and retains the queue`() {
        val queue = InMemoryDebugBundleQueueStore()
        val transport = RecordingTransport {
            DebugBundleTransportResult(
                statusCode = 202,
                acknowledgement = DebugBundleIngestionAcknowledgement(
                    accepted = 1,
                    rejected = 0,
                    errors = listOf(DebugBundleIngestionError(index = 0, reason = "invalid_event")),
                ),
            )
        }
        val client = newClient(transport, queue)

        client.captureMessage("retain", DebugBundleLogLevel.Error)
        client.flush()

        assertEquals(DebugBundleStatus.Degraded, client.status)
        assertNull(client.lastEventAt)
        assertEquals(1, queue.snapshot(NOW.toEpochMilli(), LIMITS).size)
        client.close()
    }

    @Test
    fun `legacy custom transport without acknowledgement preserves success fallback`() {
        val queue = InMemoryDebugBundleQueueStore()
        val client = newClient(
            RecordingTransport { DebugBundleTransportResult(statusCode = 202) },
            queue,
        )

        client.captureMessage("legacy custom transport", DebugBundleLogLevel.Error)
        client.flush()

        assertEquals(DebugBundleStatus.Healthy, client.status)
        assertNotNull(client.lastEventAt)
        assertEquals(0, queue.snapshot(NOW.toEpochMilli(), LIMITS).size)
        client.close()
    }

    @Test
    fun `acknowledgements after queue overflow preserve unsent events and original retry indices`() {
        val outcomes = listOf(
            DebugBundleTransportResult(statusCode = 202) to listOf("C"),
            DebugBundleTransportResult(statusCode = 400) to listOf("C"),
            DebugBundleTransportResult(
                statusCode = 202,
                acknowledgement = DebugBundleIngestionAcknowledgement(
                    accepted = 1, rejected = 1,
                    errors = listOf(DebugBundleIngestionError(index = 1, reason = "analytics_quota_exceeded")),
                ),
            ) to listOf("B", "C"),
        )
        for ((result, expected) in outcomes) for (legacy in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val backing = InMemoryDebugBundleQueueStore()
            val queue: DebugBundleQueueStore = if (legacy) object : DebugBundleQueueStore by backing {} else backing
            val transport = RecordingTransport {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                result
            }
            val client = newClient(transport, queue, DebugBundleConfig(
                projectToken = "token", batchSize = 100, offlineQueueMaxEvents = 2,
            ))
            val sender = Executors.newSingleThreadExecutor()
            try {
                client.captureMessage("A", DebugBundleLogLevel.Error)
                client.captureMessage("B", DebugBundleLogLevel.Error)
                val flush = sender.submit { client.flush() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                client.captureMessage("C", DebugBundleLogLevel.Error)
                assertEquals(listOf("A", "B"), queue.snapshot(NOW.toEpochMilli(), LIMITS)
                    .map { (it.envelope.payload["message"] as JsonPrimitive).content })
                release.countDown()
                flush.get(5, TimeUnit.SECONDS)
                assertEquals(expected, queue.snapshot(NOW.toEpochMilli(), LIMITS)
                    .map { (it.envelope.payload["message"] as JsonPrimitive).content })
            } finally {
                release.countDown()
                sender.shutdownNow()
                client.close()
            }
        }
    }

    private fun newClient(
        transport: RecordingTransport,
        queue: DebugBundleQueueStore,
        config: DebugBundleConfig = DebugBundleConfig(projectToken = "token", service = "checkout-android"),
    ): DebugBundleClient {
        return DebugBundleClient.create(
            config = config,
            transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient {
                DebugBundleRemoteConfigResult.Loaded(
                    DebugBundleRemoteConfigResponse(
                        capturePolicy = DebugBundleRemoteCapturePolicy(
                            preset = "balanced",
                            captureLogs = "warning",
                            captureRequestEvents = "failures_only",
                            captureBreadcrumbs = "exception_only",
                            captureProbeEvents = "buffer_only",
                        ),
                    ),
                )
            },
            queueStore = queue,
            clock = { NOW },
            random = { 0.0 },
            executor = Executors.newSingleThreadScheduledExecutor(),
        ).also { it.refreshRemoteConfig() }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-28T10:15:30Z")
        val LIMITS = DebugBundleQueueLimits(
            maxEvents = 500,
            maxBytes = 5 * 1024 * 1024,
            ttlMillis = 72 * 60 * 60 * 1000,
        )
    }
}
