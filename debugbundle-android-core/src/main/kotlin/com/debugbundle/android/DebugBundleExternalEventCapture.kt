package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleSuppressionTracker
import com.debugbundle.android.internal.debugBundleSuppressionSourceId
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DebugBundleExternalEventCapture(
    private val config: DebugBundleConfig,
    private val captureConfigured: Boolean,
    private val clock: () -> Instant,
    private val sanitizeEvent: (Map<String, Any?>) -> JsonObject?,
    private val sanitizeProbeData: (Any?) -> JsonObject,
    private val prepareEvent: (DebugBundleEnvelope) -> DebugBundleEnvelope?,
    private val shouldCaptureEvent: (String) -> Boolean,
    private val shouldSample: (String) -> Boolean,
    private val deferSuppression: Boolean = false,
    private val shouldCaptureEnvelope: (DebugBundleEnvelope) -> Boolean,
    private val probesEnabled: () -> Boolean,
    private val matchingProbeDirectives: (String) -> List<DebugBundleRemoteProbeDirective>,
    private val buildDeviceContext: () -> JsonObject,
    private val enqueue: (DebugBundleEnvelope, Boolean) -> Boolean,
) {
    private val suppressionTracker = DebugBundleSuppressionTracker()
    private val suppressionSources = LinkedHashMap<String, DebugBundleEnvelope>()

    fun captureEvent(event: Map<String, Any?>): Boolean {
        if (!captureConfigured) {
            return false
        }
        val sanitizedEvent = sanitizeEvent(event) ?: return false
        val parsedEnvelope = parseDebugBundleExternalEvent(sanitizedEvent) ?: return false
        val envelope = prepareEvent(parsedEnvelope) ?: return false
        if (
            !shouldCaptureEvent(envelope.eventType) ||
            !shouldSample(envelope.eventType) ||
            !shouldCaptureEnvelope(envelope) ||
            (!deferSuppression && !shouldCaptureBySuppressionPolicy(envelope))
        ) {
            return false
        }
        return enqueue(envelope, envelope.eventType in EXTERNAL_SESSION_EVENT_TYPES)
    }

    fun enqueuePendingSuppressionAggregates() {
        suppressionTracker.drainAggregates(clock().toEpochMilli()).forEach { aggregate ->
            val source = synchronized(suppressionSources) {
                suppressionSources[aggregate.sourceKey]
            } ?: return@forEach
            val event = prepareEvent(
                DebugBundleEnvelope(
                    schemaVersion = "2026-03-01",
                    eventId = UUID.randomUUID().toString(),
                    eventType = DebugBundleEventTypes.ERROR_SUPPRESSED,
                    sdkName = source.sdkName,
                    sdkVersion = source.sdkVersion,
                    service = source.service,
                    occurredAt = aggregate.lastSeenIso,
                    correlation = source.correlation,
                    payload = buildJsonObject {
                        put("fingerprint", aggregate.fingerprint)
                        put("suppressed_count", aggregate.suppressedCount)
                        put("window_seconds", aggregate.windowSeconds)
                        put("first_seen", aggregate.firstSeenIso)
                        put("last_seen", aggregate.lastSeenIso)
                    },
                    device = source.device ?: buildDeviceContext(),
                    context = source.context,
                ),
            )
            if (event !== null) {
                enqueue(event, false)
            }
        }
    }

    fun isProbeActive(label: String): Boolean {
        return runCatching {
            label.isNotBlank() &&
                probesEnabled() &&
                matchingProbeDirectives(label).isNotEmpty()
        }.getOrDefault(false)
    }

    fun captureProbe(
        sdkVersion: String,
        service: String,
        environment: String,
        label: String,
        data: Any?,
        occurredAt: String,
    ): Boolean {
        if (
            !captureConfigured ||
            label.isBlank()
        ) {
            return false
        }
        val directives = matchingProbeDirectives(label)
        if (directives.isEmpty()) {
            return false
        }
        val timestamp = runCatching { Instant.parse(occurredAt).toString() }
            .getOrElse { clock().toString() }
        val probeData = sanitizeProbeData(data)
        var captured = false
        directives.forEach { directive ->
            val event = prepareEvent(
                DebugBundleEnvelope(
                    schemaVersion = "2026-03-01",
                    eventId = UUID.randomUUID().toString(),
                    eventType = DebugBundleEventTypes.PROBE_EVENT,
                    sdkName = REACT_NATIVE_SDK_NAME,
                    sdkVersion = sdkVersion.ifBlank { config.sdkVersion },
                    service = DebugBundleServiceDescriptor(
                        name = service.ifBlank { config.service },
                        environment = environment.ifBlank { config.environment },
                        runtime = "react-native",
                        framework = "react-native",
                    ),
                    occurredAt = timestamp,
                    payload = buildJsonObject {
                        put("label", label)
                        put("data", probeData)
                        put("activation_id", directive.effectiveActivationId)
                        put("probe_label_pattern", directive.labelPattern)
                    },
                    device = buildDeviceContext(),
                ),
            )
            if (
                event !== null &&
                shouldCaptureEvent(event.eventType) &&
                shouldSample(event.eventType) &&
                shouldCaptureEnvelope(event)
            ) {
                captured = enqueue(event, false) || captured
            }
        }
        return captured
    }

    fun shouldCaptureBySuppressionPolicy(envelope: DebugBundleEnvelope): Boolean {
        val rawKey = buildDebugBundleSuppressionKey(envelope) ?: return true
        val sourceKey = "$REACT_NATIVE_SDK_NAME|$rawKey"
        synchronized(suppressionSources) {
            // Aggregation retains only bounded protocol metadata, never the original event payload/context.
            suppressionSources[debugBundleSuppressionSourceId(sourceKey)] = envelope.copy(payload = JsonObject(emptyMap()), context = null, device = null)
            while (suppressionSources.size > MAX_EXTERNAL_SUPPRESSION_SOURCES ||
                suppressionSources.values.sumOf { it.toString().toByteArray().size.toLong() } > 512 * 1024) {
                suppressionSources.remove(suppressionSources.keys.first())
            }
        }
        return suppressionTracker.shouldCapture(sourceKey, clock().toEpochMilli())
    }
}

private const val MAX_EXTERNAL_SUPPRESSION_SOURCES = 500
