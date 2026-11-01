package com.debugbundle.android.perf

import com.debugbundle.android.*
import com.debugbundle.android.testkit.RecordingTransport
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.*

fun main() {
    val meter = ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(meter.isThreadAllocatedMemorySupported)
    meter.isThreadAllocatedMemoryEnabled = true
    meter.isThreadCpuTimeEnabled = true
    val pairs = 20
    repeat(4) { repetition ->
        for (enabled in listOf(false, true)) for (pressure in listOf(false, true)) {
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
            try {
                executor.submit {}.get(30, TimeUnit.SECONDS)
                val worker = executor.submit<Long> { Thread.currentThread().id }.get()
                val started = CountDownLatch(1)
                executor.execute { started.countDown(); release.await(2, TimeUnit.MINUTES) }
                check(started.await(30, TimeUnit.SECONDS))
                if (pressure) repeat(256) { client.captureLog("retained warning $it", DebugBundleLogLevel.Warning) }
                val errors = List(pairs) { IllegalStateException("primary $it", IllegalArgumentException("cause $it")) }
                val scope = DebugBundleEvidenceScope(operationId = UUID.randomUUID().toString())
                val failures = List(pairs) { UUID.randomUUID().toString() }
                val contexts = failures.map { DebugBundleEvidenceContext.forFailure(it, scope) }
                val observations = failures.map { DebugBundleRecoveryObservation(it, UUID.randomUUID().toString(), "attempt_completed", scope = scope, statusCode = 200) }
                val caller = Thread.currentThread().id
                val before = meter.getThreadAllocatedBytes(caller)
                val begin = System.nanoTime()
                repeat(pairs) { client.captureException(errors[it], contexts[it]); client.captureRecovery(observations[it]) }
                val callerTime = System.nanoTime() - begin
                val callerBytes = meter.getThreadAllocatedBytes(caller) - before
                // Reflection is harness-only and observes the SDK's actual owned staging bytes.
                val staging = field(client, "captureWorker")!!
                val count = field(staging, "ownedCount") as Int
                val bytes = field(staging, "ownedBytes") as Long
                check(count <= 256 && bytes <= 2 * 1024 * 1024)
                val workerBefore = meter.getThreadAllocatedBytes(worker)
                val cpuBefore = meter.getThreadCpuTime(worker)
                release.countDown()
                client.flush()
                executor.submit {}.get(30, TimeUnit.SECONDS)
                val workerBytes = meter.getThreadAllocatedBytes(worker) - workerBefore
                val workerCpu = meter.getThreadCpuTime(worker) - cpuBefore
                val delivered = transport.events
                val primary = delivered.filter { it.eventType == "frontend_exception" }
                check(primary.size == pairs) { "Primary loss: ${primary.size}" }
                primary.forEach {
                    check(it.payload.containsKey("exception_evidence") == enabled)
                    if (enabled) check(it.payload.getValue("exception_evidence").jsonObject.getValue("nodes").jsonArray.size == 2)
                }
                val diagnostics = delivered.filter { it.eventType == "diagnostic_context" }
                check(diagnostics.size == if (enabled && !pressure) pairs else 0)
                val diagnosticBytes = diagnostics.sumOf { Json.encodeToString(DebugBundleEnvelope.serializer(), it).toByteArray().size }
                check(diagnosticBytes <= 65536)
                Reference.reachabilityFence(errors)
                if (repetition > 0) println(buildJsonObject {
                    put("repeat", repetition); put("runtime", System.getProperty("java.version")); put("evidence", enabled); put("pressure", pressure); put("pairs", pairs)
                    put("capture_ns_per_pair", callerTime.toDouble() / pairs); put("capture_allocated_bytes_per_pair", callerBytes.toDouble() / pairs)
                    put("deferred_worker_cpu_ns", workerCpu); put("deferred_worker_allocated_bytes", workerBytes)
                    put("retained_count", count); put("retained_wire_bytes", bytes); put("delivered_diagnostic_bytes", diagnosticBytes)
                })
            } finally { release.countDown(); client.close(); executor.shutdownNow() }
        }
    }
}

private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
