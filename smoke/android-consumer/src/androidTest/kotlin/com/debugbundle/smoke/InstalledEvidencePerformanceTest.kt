package com.debugbundle.smoke

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.debugbundle.android.*
import com.debugbundle.android.testkit.RecordingTransport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Installed ART comparison. Allocation counters are approximate process totals,
 * including test/runtime background work; they are not exclusive SDK-thread bytes. */
@RunWith(AndroidJUnit4::class)
class InstalledEvidencePerformanceTest {
    @Test fun compareNativeCaptureAndDeferredWork() {
        repeat(4) { repetition ->
            for (enabled in listOf(false, true)) for (pressure in listOf(false, true)) {
                checkpoint("case_begin", repetition, enabled, pressure)
                val executor = Executors.newSingleThreadScheduledExecutor()
                val transport = RecordingTransport()
                val config = DebugBundleConfig(projectToken = "synthetic", environment = "local", captureFatalExceptions = false,
                    batchSize = 1001, flushInterval = 1.hours, requestTimeout = 2.minutes, maxEventsPerSession = 2000)
                    .withEvidenceOptions(DebugBundleEvidenceOptions(captureExceptionEvidence = enabled,
                        captureRecoveryObservations = enabled, evidenceCapabilities = DebugBundleEvidenceCapabilities(
                            features = listOf("exception_evidence_v1", "recovery_v1"))))
                val client = DebugBundleClient.create(config = config, transport = transport, executor = executor,
                    queueStore = InMemoryDebugBundleQueueStore(), random = { 0.0 },
                    remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
                val release = CountDownLatch(1)
                checkpoint("client_ready", repetition, enabled, pressure)
                try {
                    executor.submit {}.get(30, TimeUnit.SECONDS)
                    val started = CountDownLatch(1)
                    val workerCpuStart = AtomicLong()
                    executor.execute {
                        workerCpuStart.set(SystemClock.currentThreadTimeMillis())
                        started.countDown(); release.await(2, TimeUnit.MINUTES)
                    }
                    assertTrue(started.await(30, TimeUnit.SECONDS))
                    checkpoint("worker_held", repetition, enabled, pressure)
                    if (pressure) repeat(256) { client.captureLog("retained warning $it", DebugBundleLogLevel.Warning) }
                    val pairs = 20
                    val errors = List(pairs) { IllegalStateException("primary $it", IllegalArgumentException("cause $it")) }
                    val scope = DebugBundleEvidenceScope(operationId = UUID.randomUUID().toString())
                    val failures = List(pairs) { UUID.randomUUID().toString() }
                    val contexts = failures.map { DebugBundleEvidenceContext.forFailure(it, scope) }
                    val observations = failures.map { DebugBundleRecoveryObservation(it, UUID.randomUUID().toString(), "attempt_completed", scope = scope, statusCode = 200) }
                    checkpoint("capture_begin", repetition, enabled, pressure)
                    val before = allocated()
                    val begin = System.nanoTime()
                    repeat(pairs) { client.captureException(errors[it], contexts[it]); client.captureRecovery(observations[it]) }
                    val callerTime = System.nanoTime() - begin
                    val callerBytes = difference(allocated(), before)
                    checkpoint("captured", repetition, enabled, pressure)
                    val staging = field(client, "captureWorker")!!
                    val count = field(staging, "ownedCount") as Int
                    val bytes = field(staging, "ownedBytes") as Long
                    assertTrue(count <= 256 && bytes <= 2 * 1024 * 1024)
                    checkpoint("flush_begin", repetition, enabled, pressure)
                    val workerBefore = allocated()
                    release.countDown(); client.flush()
                    val cpuEnd = executor.submit<Long> { SystemClock.currentThreadTimeMillis() }.get(30, TimeUnit.SECONDS)
                    val workerBytes = difference(allocated(), workerBefore)
                    checkpoint("flushed", repetition, enabled, pressure)
                    val primary = transport.events.filter { it.eventType == "frontend_exception" }
                    assertEquals(pairs, primary.size)
                    primary.forEach {
                        assertEquals(enabled, it.payload.containsKey("exception_evidence"))
                        if (enabled) assertEquals(2, it.payload.getValue("exception_evidence").jsonObject.getValue("nodes").jsonArray.size)
                    }
                    val diagnostics = transport.events.filter { it.eventType == "diagnostic_context" }
                    assertEquals(if (enabled && !pressure) pairs else 0, diagnostics.size)
                    val diagnosticBytes = diagnostics.sumOf { Json.encodeToString(DebugBundleEnvelope.serializer(), it).toByteArray().size }
                    assertTrue(diagnosticBytes <= 65536)
                    assertEquals("primary 0", errors.first().message) // Keep native errors alive through deferred projection.
                    if (repetition > 0) {
                        val row = buildJsonObject {
                            put("repeat", repetition); put("api", Build.VERSION.SDK_INT); put("evidence", enabled); put("pressure", pressure); put("pairs", pairs)
                            put("capture_ns_per_pair", callerTime.toDouble() / pairs)
                            put("caller_process_allocated_bytes_approx", callerBytes?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("deferred_process_allocated_bytes_approx", workerBytes?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("deferred_worker_cpu_ms", cpuEnd - workerCpuStart.get())
                            put("retained_count", count); put("retained_wire_bytes", bytes); put("delivered_diagnostic_bytes", diagnosticBytes)
                        }
                        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$row\n") })
                    }
                    checkpoint("case_done", repetition, enabled, pressure)
                } finally { release.countDown(); client.close(); executor.shutdownNow() }
            }
        }
    }

    // Fixed fixture labels stay outside the timed/allocation measurement windows.
    private fun checkpoint(stage: String, repetition: Int, enabled: Boolean, pressure: Boolean) {
        android.util.Log.i("DebugBundleQualification", "fixture=com.debugbundle.smoke stage=$stage repeat=$repetition evidence=$enabled pressure=$pressure")
    }

    private fun allocated(): Long? = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
    private fun difference(after: Long?, before: Long?): Long? = if (after != null && before != null && after >= before) after - before else null
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
