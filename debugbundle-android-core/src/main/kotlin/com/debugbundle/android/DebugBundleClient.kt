package com.debugbundle.android

import com.debugbundle.android.crash.DebugBundleFatalCrashStore
import com.debugbundle.android.internal.DebugBundleBreadcrumbBuffer
import com.debugbundle.android.internal.DebugBundleProbeBuffer
import com.debugbundle.android.internal.DebugBundleRedactor
import com.debugbundle.android.internal.DebugBundleSuppressionTracker
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer

class DebugBundleClient internal constructor(
    private val config: DebugBundleConfig,
    private val transport: DebugBundleTransport,
    private val remoteConfigClient: DebugBundleRemoteConfigClient,
    private val queueStore: DebugBundleQueueStore,
    private val deviceContextProvider: DebugBundleDeviceContextProvider,
    private val fatalCrashStore: DebugBundleFatalCrashStore,
    private val fatalCrashHandlerRegistration: AutoCloseable?,
    private val runtimeRegistration: AutoCloseable?,
    private val clock: () -> Instant,
    private val random: () -> Double,
    private val executor: ScheduledExecutorService,
) : AutoCloseable, DebugBundleCaptureSink, DebugBundleCoroutineSupport {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val redactor = DebugBundleRedactor(config.redactFields)
    private val probeBuffer = DebugBundleProbeBuffer(config.maxProbeLabels, config.maxProbeEntriesPerLabel)
    private val breadcrumbBuffer = DebugBundleBreadcrumbBuffer(config.maxBreadcrumbs.coerceAtLeast(1))
    private val normalizedHeaderAllowlist = config.headerAllowlist.map(::normalizeDebugBundleHeaderName).toSet()
    private val deviceSnapshot = AtomicReference(JsonObject(emptyMap()))
    private val capturePolicyRef = AtomicReference(DebugBundleCapturePolicy.defaultWhenConfigFetchFails())
    private val statusRef = AtomicReference(initialDebugBundleStatus(config))
    private val lastEventAtRef = AtomicLong(DEBUG_BUNDLE_NO_EVENT_SENT)
    private val suppressionTracker = DebugBundleSuppressionTracker()
    private val persistentContext = LinkedHashMap<String, Any?>()
    private val buffer = ArrayDeque<DebugBundleEnvelope>()
    private val lock = Any()
    private val captureConfigured = config.enabled && config.projectToken.isNotBlank()
    private val sessionSampledIn = captureConfigured && (config.sessionSampleRate >= 1.0 || random() <= config.sessionSampleRate)
    private var lastScreenName: String? = null
    private var nextRetryAtMillis: Long = 0
    private var flushing: Boolean = false
    private var consecutiveFailures: Int = 0
    @Volatile private var sessionEventCount: Int = 0
    private val remoteProbeState = DebugBundleRemoteProbeState()
    private val remoteConfig = DebugBundleRemoteConfigCoordinator(
        config, remoteConfigClient, clock, executor, capturePolicyRef, remoteProbeState,
        { statusRef.set(DebugBundleStatus.Degraded) },
    )
    private val registeredCloseables = mutableListOf<AutoCloseable>()
    private val externalEventCapture by lazy {
        DebugBundleExternalEventCapture(
            config = config,
            captureConfigured = captureConfigured,
            clock = clock,
            sanitizeEvent = { redactor.sanitize(it) as? JsonObject },
            sanitizeProbeData = ::sanitizeProbeData,
            prepareEvent = { prepareEvent(it, runBeforeSend = false) },
            shouldCaptureEvent = { true },
            shouldSample = { true },
            deferSuppression = true,
            shouldCaptureEnvelope = {
                shouldCaptureDebugBundleExternalEnvelope(config, capturePolicyRef.get(), it)
            },
            probesEnabled = remoteProbeState::probesEnabled,
            matchingProbeDirectives = ::matchingRemoteProbeDirectives,
            buildDeviceContext = ::buildDeviceContext,
            enqueue = { event, counted -> enqueueCanonicalEvent(event, counted) },
        )
    }

    private val captureWorker = DebugBundleCaptureWorker(
        executor = executor,
        nowMillis = { clock().toEpochMilli() },
        ttlMillis = debugBundleQueueLimits(config).ttlMillis,
        initialize = {
            if (captureConfigured) {
                refreshDeviceSnapshot()
                (queueStore as? FileDebugBundleQueueStore)?.installPrivacyTransformer(::protectEnvelope)
                syncBufferFromQueue(clock().toEpochMilli())
                if (config.captureFatalExceptions) replayPendingFatalCrash()
            }
        },
        beforeDrain = { if (captureConfigured) { refreshDeviceSnapshot(); enqueueSuppressionAggregates() } },
        prepare = { event, hook ->
            val withDevice = if (event.sdkName == DEBUG_BUNDLE_ANDROID_SDK_NAME) event.copy(
                device = buildDeviceContext(), payload = JsonObject(event.payload + ("device" to buildDeviceContext())),
            ) else event
            prepareEvent(withDevice, hook)?.takeIf {
                passesCapturePolicy(it) && shouldCaptureDebugBundleExternalEnvelope(config, capturePolicyRef.get(), it) &&
                    shouldCaptureEvent(it.eventType) && shouldSample(it.eventType) && captureAfterSuppression(it)
            }
        },
        persist = { events ->
            val persisted = queueStore.append(events, clock().toEpochMilli(), debugBundleQueueLimits(config))
            publishQueue(persisted)
            persisted.mapTo(hashSetOf()) { it.envelope.eventId }
        },
        shouldFlush = { buffer.size >= config.batchSize },
        flush = { flushSafely(config.requestTimeout) },
        failure = { statusRef.set(DebugBundleStatus.Degraded) },
        onPrepared = { if (it.eventType in EXTERNAL_SESSION_EVENT_TYPES) sessionEventCount += 1 },
    )

    val status: DebugBundleStatus
        get() = statusRef.get()

    val lastEventAt: Long?
        get() = lastEventAtRef.get().takeIf { it != DEBUG_BUNDLE_NO_EVENT_SENT }

    val lifecycle: DebugBundleLifecycleRecorder
        get() = DebugBundleLifecycleRecorder(this)

    init {
        if (captureConfigured) {
            captureWorker.request()
            remoteConfig.request()
            val periodMillis = config.flushInterval.inWholeMilliseconds.coerceAtLeast(500)
            executor.scheduleAtFixedRate(
                { captureWorker.request(flush = true) },
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun captureException(error: Throwable, context: Map<String, Any?> = emptyMap()) = safely {
        if (!captureConfigured || !captureWorker.mightAdmit(3)) return@safely
        enqueueEvent(
            eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION,
            correlationTraceId = context["trace_id"]?.toString(),
            countTowardSession = false,
            details = DebugBundleDeferredThrowable(error, redactor),
        ) {
            buildJsonObject {
                put("error", redactor.sanitize(error))
                put("context", redactor.sanitize(mergeContext(context)))
                put(
                    "breadcrumbs",
                    json.encodeToJsonElement(
                        ListSerializer(DebugBundleBreadcrumb.serializer()),
                        breadcrumbBuffer.snapshot(),
                    ),
                )
                if (config.probeFlushOnError) {
                    put("probe_data", probeBuffer.snapshot())
                }
            }
        }
    }

    fun captureError(error: Throwable, context: Map<String, Any?> = emptyMap()) {
        captureException(error, context)
    }

    fun captureLog(
        message: String,
        level: DebugBundleLogLevel = DebugBundleLogLevel.Warning,
        context: Map<String, Any?> = emptyMap(),
    ) = safely {
        if (!captureConfigured || !capturePolicyRef.get().capturesLog(level, config.captureLogs, config.logLevel)) {
            return@safely
        }
        if (!captureWorker.mightAdmit(if (level == DebugBundleLogLevel.Error) 2 else 0)) return@safely
        val mergedContext = mergeContext(context)
        val sanitizedContext = redactor.sanitize(
            mergedContext.filterKeys { it !in DEBUG_BUNDLE_LOG_RECORD_RESERVED_CONTEXT_KEYS },
        )
        enqueueEvent(
            eventType = DebugBundleEventTypes.LOG_EVENT,
            correlationTraceId = mergedContext["trace_id"]?.toString(),
            countTowardSession = true,
            details = (mergedContext["throwable"] as? Throwable)?.let { DebugBundleDeferredThrowable(it, redactor, log = true) },
        ) {
            buildJsonObject {
                put("level", JsonPrimitive(level.name.lowercase()))
                put("message", JsonPrimitive(message))
                put("logged_at", JsonPrimitive(clock().toString()))
                put("thread_name", JsonPrimitive(Thread.currentThread().name))
                mergedContext["logger"]?.toString()?.let { put("logger", JsonPrimitive(it)) }
                mergedContext["tag"]?.toString()?.let { put("tag", JsonPrimitive(it)) }
                mergedContext["coroutine_name"]?.toString()?.let { put("coroutine_name", JsonPrimitive(it)) }
                (mergedContext["throwable"] as? Throwable)?.let { put("throwable", redactor.sanitize(it)) }
                put("context", sanitizedContext)
            }
        }
    }

    fun captureRequest(
        request: DebugBundleRequestInfo,
        response: DebugBundleResponseInfo,
        context: Map<String, Any?> = emptyMap(),
        recordBreadcrumb: Boolean = true,
    ) = safely {
        val eligible = captureConfigured && config.captureNetwork && capturePolicyRef.get()
            .capturesStandaloneRequestEvent(response.statusCode, request.url, request.method)
        if (eligible && captureWorker.mightAdmit(if (response.statusCode >= 400) 2 else 1)) enqueueEvent(
            eventType = DebugBundleEventTypes.REQUEST_EVENT,
            correlationTraceId = request.traceId ?: context["trace_id"]?.toString(),
            countTowardSession = true,
        ) {
            buildDebugBundleRequestPayload(request, response, mergeContext(context), redactor, normalizedHeaderAllowlist)
        }
        if (recordBreadcrumb && config.captureNetwork) {
            recordNetworkBreadcrumb(request, response)
        }
    }

    override fun recordRequest(
        request: DebugBundleRequestInfo,
        response: DebugBundleResponseInfo,
        context: Map<String, Any?>,
        recordBreadcrumb: Boolean,
    ) {
        captureRequest(request, response, context, recordBreadcrumb)
    }

    fun captureMessage(
        message: String,
        level: DebugBundleLogLevel = DebugBundleLogLevel.Warning,
        context: Map<String, Any?> = emptyMap(),
    ) {
        captureLog(message = message, level = level, context = context)
    }

    fun setContext(key: String, value: Any?) {
        safely {
            val safe = redactor.sanitize(mapOf(key to value)) as? JsonObject ?: return@safely
            synchronized(lock) {
                if (key in persistentContext || persistentContext.size < 50) {
                    safe[key]?.let { persistentContext[key] = it }
                }
            }
        }
    }

    fun probe(label: String, data: Any?, options: ProbeOptions = ProbeOptions()) {
        captureProbe(label, options) { data }
    }

    fun probe(label: String, options: ProbeOptions = ProbeOptions(), producer: () -> Any?) {
        captureProbe(label, options, producer)
    }

    /**
     * Additive bridge surface for a canonical event authored by another
     * DebugBundle SDK, currently React Native.
     */
    fun captureExternalEvent(event: Map<String, Any?>): Boolean {
        return runCatching { externalEventCapture.captureEvent(event) }
            .onFailure { statusRef.set(DebugBundleStatus.Degraded) }
            .getOrDefault(false)
    }

    fun isExternalProbeActive(label: String): Boolean {
        return externalEventCapture.isProbeActive(label)
    }

    fun captureExternalProbe(
        sdkVersion: String,
        service: String,
        environment: String,
        label: String,
        data: Any?,
        occurredAt: String,
    ): Boolean {
        return runCatching {
            externalEventCapture.captureProbe(
                sdkVersion = sdkVersion,
                service = service,
                environment = environment,
                label = label,
                data = data,
                occurredAt = occurredAt,
            )
        }.onFailure {
            statusRef.set(DebugBundleStatus.Degraded)
        }.getOrDefault(false)
    }

    private fun captureProbe(label: String, options: ProbeOptions, producer: () -> Any?) {
        safely {
            if (!captureConfigured || label.isBlank()) {
                return@safely
            }
            if ((redactor.sanitize(label) as? JsonPrimitive)?.content != label) return@safely
            val matchingDirectives = matchingRemoteProbeDirectives(label)
            if (!remoteProbeState.probesEnabled() || (options.heavy && matchingDirectives.isEmpty())) {
                return@safely
            }

            val probeData = sanitizeProbeData(producer())
            if (!options.heavy) {
                probeBuffer.add(label, probeData, clock().toString())
            }

            if (
                matchingDirectives.isEmpty()
            ) {
                return@safely
            }

            matchingDirectives.forEach { directive ->
                enqueueEvent(
                    eventType = DebugBundleEventTypes.PROBE_EVENT,
                    correlationTraceId = null,
                    countTowardSession = false,
                ) {
                    buildJsonObject {
                        put("label", label)
                        put("data", probeData)
                        put("activation_id", directive.effectiveActivationId)
                        put("probe_label_pattern", directive.labelPattern)
                    }
                }
            }
        }
    }

    override fun activateProbeTriggerToken(token: String): Boolean {
        var activated = false
        safely {
            val directive = DebugBundleProbeTriggerTokenValidator.validate(
                token = token,
                triggerTokenKey = remoteProbeState.triggerTokenKey(),
                now = clock(),
            ) ?: return@safely
            remoteProbeState.activateTrigger(directive)
            activated = true
        }
        return activated
    }

    fun flush(timeout: Duration = config.requestTimeout) {
        if (captureConfigured) captureWorker.flushAndWait(timeout)
    }

    fun refreshRemoteConfig(force: Boolean = true) {
        remoteConfig.refresh(force)
    }

    override fun coroutineExceptionHandler(context: Map<String, Any?>): CoroutineExceptionHandler =
        debugBundleCoroutineExceptionHandler(context, ::captureException)

    @JvmSynthetic
    fun registerRuntimeCloseable(closeable: AutoCloseable) {
        synchronized(lock) {
            registeredCloseables += closeable
        }
    }

    override fun close() {
        safely { if (captureConfigured) captureWorker.close(config.requestTimeout) }
        safely {
            synchronized(lock) {
                registeredCloseables.toList()
            }.forEach { it.close() }
        }
        safely { runtimeRegistration?.close() }
        safely { fatalCrashHandlerRegistration?.close() }
        executor.shutdownNow()
    }

    fun captureBreadcrumb(
        breadcrumbType: String,
        route: String? = null,
        data: Map<String, Any?> = emptyMap(),
    ) {
        safely {
            if (!captureConfigured || !sessionSampledIn || sessionEventCount >= config.maxEventsPerSession) {
                return@safely
            }
            val breadcrumb = DebugBundleBreadcrumb(
                occurredAt = clock().toString(),
                breadcrumbType = (redactor.sanitize(breadcrumbType) as JsonPrimitive).content,
                route = (route ?: lastScreenName)?.let { (redactor.sanitize(it) as JsonPrimitive).content },
                data = redactor.sanitize(data) as JsonObject,
            )
            breadcrumbBuffer.add(breadcrumb)
            if (capturePolicyRef.get().capturesStandaloneBreadcrumbs()) {
                enqueueEvent(
                    eventType = DebugBundleEventTypes.FRONTEND_BREADCRUMB,
                    correlationTraceId = null,
                    countTowardSession = true,
                    occurredAt = breadcrumb.occurredAt,
                ) {
                    breadcrumb.toPayload()
                }
            }
        }
    }

    override fun recordBreadcrumb(
        breadcrumbType: String,
        route: String?,
        data: Map<String, Any?>,
    ) {
        captureBreadcrumb(breadcrumbType, route, data)
    }

    internal fun recordScreen(screenName: String, previousScreen: String? = null, source: String = "manual") {
        if (!config.captureScreens) {
            return
        }
        val prior = previousScreen ?: lastScreenName
        lastScreenName = screenName
        captureBreadcrumb(
            breadcrumbType = DebugBundleBreadcrumbTypes.SCREEN,
            route = screenName,
            data = mapOf(
                "screen" to screenName,
                "previous_screen" to prior,
                "source" to source,
            ),
        )
    }

    internal fun recordAppForeground() {
        remoteConfig.request()
        captureBreadcrumb(
            breadcrumbType = DebugBundleBreadcrumbTypes.APP_LIFECYCLE,
            route = lastScreenName,
            data = mapOf("state" to "foreground"),
        )
    }

    internal fun recordAppBackground() {
        captureBreadcrumb(
            breadcrumbType = DebugBundleBreadcrumbTypes.APP_LIFECYCLE,
            route = lastScreenName,
            data = mapOf("state" to "background"),
        )
    }

    internal fun recordAction(actionType: String, targetType: String, resourceName: String? = null) {
        if (!config.captureActions) {
            return
        }
        captureBreadcrumb(
            breadcrumbType = DebugBundleBreadcrumbTypes.USER_ACTION,
            route = lastScreenName,
            data = mapOf(
                "action_type" to actionType,
                "target_type" to targetType,
                "resource_name" to resourceName,
            ),
        )
    }

    private fun enqueueEvent(
        eventType: String,
        correlationTraceId: String?,
        countTowardSession: Boolean,
        occurredAt: String = clock().toString(),
        runBeforeSend: Boolean = true,
        persisted: (() -> Unit)? = null,
        details: DebugBundleDeferredThrowable? = null,
        payloadBuilder: () -> JsonObject,
    ): Boolean {
        var enqueued = false
        safely {
            if (!captureConfigured) {
                return@safely
            }
            val candidate = DebugBundleEnvelope(
                schemaVersion = DEBUG_BUNDLE_ANDROID_SCHEMA_VERSION,
                eventId = UUID.randomUUID().toString(),
                eventType = eventType,
                sdkName = DEBUG_BUNDLE_ANDROID_SDK_NAME,
                sdkVersion = config.sdkVersion,
                service = DebugBundleServiceDescriptor(
                    name = config.service,
                    environment = config.environment,
                ),
                occurredAt = occurredAt,
                correlation = DebugBundleCorrelation(traceId = correlationTraceId),
                payload = payloadBuilder(),
                device = buildDeviceContext(),
            )
            val envelope = prepareEvent(candidate, runBeforeSend = false) ?: return@safely
            if (!passesCapturePolicy(envelope)) {
                return@safely
            }
            enqueued = enqueueCanonicalEvent(envelope, countTowardSession, runBeforeSend, persisted, details)
        }
        return enqueued
    }

    private fun captureAfterSuppression(event: DebugBundleEnvelope): Boolean {
        if (event.sdkName == REACT_NATIVE_SDK_NAME) return externalEventCapture.shouldCaptureBySuppressionPolicy(event)
        val key = buildDebugBundleSuppressionKey(event) ?: return true
        return suppressionTracker.shouldCapture(key, clock().toEpochMilli())
    }

    private fun prepareEvent(event: DebugBundleEnvelope, runBeforeSend: Boolean): DebugBundleEnvelope? {
        val canonical = protectEnvelope(canonicalizeAndroidEnvelope(event, buildDeviceContext())) ?: return null
        val authored = if (runBeforeSend) {
            applyDebugBundleBeforeSend(canonical, config.beforeSend)
        } else {
            canonical
        }
        return (if (runBeforeSend && config.beforeSend != null) authored?.let(::protectEnvelope) else authored)?.takeIf(DebugBundleEnvelope::isValidBeforeSendEvent)
    }

    private fun protectEnvelope(event: DebugBundleEnvelope): DebugBundleEnvelope? =
        protectDebugBundleEnvelope(event, redactor)

    private fun enqueueCanonicalEvent(
        event: DebugBundleEnvelope,
        countTowardSession: Boolean,
        runBeforeSend: Boolean = true,
        persisted: (() -> Unit)? = null,
        details: DebugBundleDeferredThrowable? = null,
    ): Boolean {
        val safeEvent = event.takeIf(DebugBundleEnvelope::isValidBeforeSendEvent) ?: return false
        val accepted = captureWorker.enqueue(safeEvent, runBeforeSend, details, persisted)
        return accepted
    }

    /** Worker-owned hydration and publication; no application capture lock surrounds disk work. */
    private fun syncBufferFromQueue(nowMillis: Long) {
        val pending = queueStore.snapshot(nowMillis, debugBundleQueueLimits(config))
        publishQueue(pending)
    }

    private fun publishQueue(pending: List<QueuedDebugBundleEvent>) {
        buffer.clear()
        val limits = debugBundleQueueLimits(config)
        if (pending.size > limits.maxEvents) return
        val currentDevice = buildDeviceContext()
        var bytes = 0L
        for (queued in pending) {
            val safe = runCatching { protectEnvelope(canonicalizeAndroidEnvelope(queued.envelope, currentDevice)) }.getOrNull()
            // Never shift custom-store ACK indices by publishing only the safe prefix.
            if (safe == null) { buffer.clear(); return }
            bytes += json.encodeToString(DebugBundleEnvelope.serializer(), safe).encodeToByteArray().size
            if (bytes > limits.maxBytes) { buffer.clear(); return }
            buffer.addLast(safe)
        }
    }

    private fun sanitizeProbeData(data: Any?): JsonObject {
        return when (val sanitized = redactor.sanitize(data)) {
            is JsonObject -> sanitized
            else -> buildJsonObject { put("value", sanitized) }
        }
    }

    private fun matchingRemoteProbeDirectives(label: String): List<DebugBundleRemoteProbeDirective> {
        return remoteProbeState.matchingDirectives(label, config.service, config.environment, clock())
    }

    private fun applyPiggybackProbeDirectives(directives: List<DebugBundleRemoteProbeDirective>?) {
        remoteProbeState.applyPiggybackDirectives(directives, clock())
    }

    private fun shouldCaptureEvent(eventType: String): Boolean {
        if (eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION || eventType == DebugBundleEventTypes.ERROR_SUPPRESSED) {
            return true
        }
        if (eventType == DebugBundleEventTypes.PROBE_EVENT) {
            return sessionSampledIn
        }
        return sessionSampledIn && sessionEventCount < config.maxEventsPerSession
    }

    private fun passesCapturePolicy(event: DebugBundleEnvelope): Boolean {
        return when (event.eventType) {
            DebugBundleEventTypes.LOG_EVENT -> {
                val level = (event.payload["level"] as? JsonPrimitive)
                    ?.content
                    ?.replaceFirstChar(Char::uppercase)
                    ?.let { runCatching { DebugBundleLogLevel.valueOf(it) }.getOrNull() }
                    ?: DebugBundleLogLevel.Warning
                capturePolicyRef.get().capturesLog(level, config.captureLogs, config.logLevel)
            }

            DebugBundleEventTypes.REQUEST_EVENT -> {
                val status = (event.payload["response_status"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val path = (event.payload["path"] as? JsonPrimitive)?.content ?: "/"
                val method = (event.payload["method"] as? JsonPrimitive)?.content ?: "UNKNOWN"
                config.captureNetwork &&
                    capturePolicyRef.get().capturesStandaloneRequestEvent(status, path, method)
            }

            DebugBundleEventTypes.PROBE_EVENT -> capturePolicyRef.get().capturesStandaloneProbeEvents()
            else -> true
        }
    }

    private fun enqueueSuppressionAggregates() {
        val nowMillis = clock().toEpochMilli()
        suppressionTracker.drainAggregates(nowMillis).forEach { aggregate ->
            val event = prepareEvent(
                DebugBundleEnvelope(
                    schemaVersion = DEBUG_BUNDLE_ANDROID_SCHEMA_VERSION,
                    eventId = UUID.randomUUID().toString(),
                    eventType = DebugBundleEventTypes.ERROR_SUPPRESSED,
                    sdkName = DEBUG_BUNDLE_ANDROID_SDK_NAME,
                    sdkVersion = config.sdkVersion,
                    service = DebugBundleServiceDescriptor(
                        name = config.service,
                        environment = config.environment,
                    ),
                    occurredAt = aggregate.lastSeenIso,
                    payload = buildJsonObject {
                        put("fingerprint", JsonPrimitive(aggregate.fingerprint))
                        put("suppressed_count", JsonPrimitive(aggregate.suppressedCount))
                        put("window_seconds", JsonPrimitive(aggregate.windowSeconds))
                        put("first_seen", JsonPrimitive(aggregate.firstSeenIso))
                        put("last_seen", JsonPrimitive(aggregate.lastSeenIso))
                    },
                    device = buildDeviceContext(),
                ),
                runBeforeSend = false,
            )
            if (event !== null) {
                enqueueCanonicalEvent(event, countTowardSession = false)
            }
        }
        externalEventCapture.enqueuePendingSuppressionAggregates()
        captureWorker.drainPressure().forEach { (level, count) ->
            enqueueEvent(DebugBundleEventTypes.ERROR_SUPPRESSED, null, false) {
                buildJsonObject {
                    put("fingerprint", "queue_pressure:$level")
                    put("suppressed_count", count)
                    put("first_seen", clock().toString())
                    put("last_seen", clock().toString())
                    put("window_seconds", 60)
                    put("reason", "queue_pressure")
                    put("level", level)
                }
            }
        }
    }

    private fun buildRetryDelay(result: DebugBundleTransportResult): Duration {
        if (result.statusCode == 429) {
            return result.retryAfter.takeIf { it > Duration.ZERO } ?: 5.seconds
        }
        return 1.seconds
    }

    private fun updateFailureStatus() {
        statusRef.set(
            if (consecutiveFailures >= 3) {
                DebugBundleStatus.Disconnected
            } else {
                DebugBundleStatus.Degraded
            },
        )
    }

    private fun resetHealthyStatus() {
        consecutiveFailures = 0
        statusRef.set(if (captureConfigured) DebugBundleStatus.Healthy else DebugBundleStatus.Disconnected)
    }

    private fun shouldSample(eventType: String): Boolean {
        if (eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION || eventType == DebugBundleEventTypes.ERROR_SUPPRESSED) {
            return true
        }
        return config.sampleRate >= 1.0 || random() <= config.sampleRate
    }

    private fun buildDeviceContext(): JsonObject = deviceSnapshot.get()

    private fun refreshDeviceSnapshot() {
        safely {
            val snapshot = json.encodeToJsonElement(DebugBundleDeviceContext.serializer(), deviceContextProvider.snapshot())
            (redactor.sanitize(snapshot) as? JsonObject)?.let(deviceSnapshot::set)
        }
    }

    private fun replayPendingFatalCrash() {
        val record = fatalCrashStore.load() ?: return
        enqueueEvent(
            eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION,
            correlationTraceId = null,
            countTowardSession = false,
            occurredAt = record.occurredAt,
            runBeforeSend = false,
            persisted = { fatalCrashStore.clear() },
        ) {
            buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("type", JsonPrimitive(record.errorType))
                        put("message", JsonPrimitive(record.errorMessage))
                        put("stack_trace", JsonArray(record.stackTrace.map(::JsonPrimitive)))
                    },
                )
                put(
                    "context",
                    redactor.sanitize(
                        mapOf(
                            "fatal" to true,
                            "handled" to false,
                            "mechanism" to "uncaught_exception_handler",
                            "crash_delivery" to "next_launch",
                            "thread_name" to record.threadName,
                        ),
                    ),
                )
                put("breadcrumbs", JsonArray(emptyList()))
                if (config.probeFlushOnError) {
                    put("probe_data", probeBuffer.snapshot())
                }
            }
        }
    }

    private fun recordNetworkBreadcrumb(request: DebugBundleRequestInfo, response: DebugBundleResponseInfo) {
        val route = request.routeTemplate ?: request.url
        captureBreadcrumb(
            breadcrumbType = DebugBundleBreadcrumbTypes.NETWORK,
            data = mapOf(
                "method" to request.method,
                "host_or_path" to route,
                "status" to response.statusCode,
                "duration_ms" to response.durationMillis,
            ),
        )
    }

    private fun flushSafely(timeout: Duration) {
        if (!captureConfigured) return
        safely {
            val batch = synchronized(lock) {
                if (buffer.isEmpty()) {
                    return@synchronized null
                }
                if (flushing) {
                    return@synchronized null
                }
                val now = clock().toEpochMilli()
                if (now < nextRetryAtMillis) {
                    return@synchronized null
                }
                flushing = true
                buffer.toList()
            }
            if (batch == null) {
                return@safely
            }
            try {
                val result = transport.send(
                    DebugBundleTransportRequest(
                        projectToken = config.projectToken,
                        endpoint = config.endpoint,
                        events = batch,
                        timeout = timeout,
                    ),
                )
                when {
                    result.shouldRetry -> {
                        consecutiveFailures += 1
                        updateFailureStatus()
                        val retryAfter = buildRetryDelay(result)
                        nextRetryAtMillis = clock().toEpochMilli() + retryAfter.inWholeMilliseconds
                    }

                    result.isSuccess -> handleSuccessfulTransportResult(result, batch)

                    else -> {
                        publishQueue(queueStore.reconcileAcknowledgedEvents(batch, emptySet(), clock().toEpochMilli(), debugBundleQueueLimits(config)))
                        nextRetryAtMillis = 0
                        resetHealthyStatus()
                    }
                }
            } finally {
                synchronized(lock) {
                    flushing = false
                }
            }
        }
    }

    private fun handleSuccessfulTransportResult(
        result: DebugBundleTransportResult,
        batch: List<DebugBundleEnvelope>,
    ) {
        when (val decision = decideDebugBundleAcknowledgement(result, batch)) {
            DebugBundleAcknowledgementDecision.ProtocolFailure -> {
                consecutiveFailures += 1
                updateFailureStatus()
                nextRetryAtMillis = clock().toEpochMilli() + 1.seconds.inWholeMilliseconds
            }

            DebugBundleAcknowledgementDecision.LegacyTransportSuccess -> {
                applyPiggybackProbeDirectives(result.probeDirectives)
                if (batch.any { it.eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION }) {
                    breadcrumbBuffer.clear()
                }
                publishQueue(queueStore.reconcileAcknowledgedEvents(batch, emptySet(), clock().toEpochMilli(), debugBundleQueueLimits(config)))
                nextRetryAtMillis = 0
                resetHealthyStatus()
                lastEventAtRef.set(clock().toEpochMilli())
            }

            is DebugBundleAcknowledgementDecision.Accounted -> {
                applyPiggybackProbeDirectives(result.probeDirectives)
                if (decision.acceptedFrontendException) {
                    breadcrumbBuffer.clear()
                }
                publishQueue(queueStore.reconcileAcknowledgedEvents(
                    batch, decision.retryableIndices, clock().toEpochMilli(), debugBundleQueueLimits(config),
                ))

                if (decision.accepted > 0) {
                    lastEventAtRef.set(clock().toEpochMilli())
                }
                if (decision.retryableIndices.isNotEmpty()) {
                    consecutiveFailures += 1
                    updateFailureStatus()
                    nextRetryAtMillis = clock().toEpochMilli() + 1.seconds.inWholeMilliseconds
                } else {
                    nextRetryAtMillis = 0
                    consecutiveFailures = 0
                    statusRef.set(
                        if (decision.accepted > 0) {
                            DebugBundleStatus.Healthy
                        } else {
                            DebugBundleStatus.Disconnected
                        },
                    )
                }
            }
        }
    }

    private fun mergeContext(context: Map<String, Any?>): Map<String, Any?> {
        return synchronized(lock) { LinkedHashMap<String, Any?>(persistentContext) }.apply { putAll(context) }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            statusRef.set(DebugBundleStatus.Degraded)
        }
    }

    companion object {
        @JvmStatic
        fun create(
            application: Any? = null,
            config: DebugBundleConfig,
            transport: DebugBundleTransport = DebugBundleHttpTransport(),
            remoteConfigClient: DebugBundleRemoteConfigClient = DebugBundleHttpRemoteConfigClient(),
            queueStore: DebugBundleQueueStore? = null,
            deviceContextProvider: DebugBundleDeviceContextProvider? = null,
            runtimeRegistration: AutoCloseable? = null,
            clock: () -> Instant = { Instant.now() },
            random: () -> Double = { Random.nextDouble() },
            executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "debugbundle-android").apply {
                    isDaemon = true
                }
            },
        ): DebugBundleClient {
            tryCreateAndroidBootstrap(
                application = application,
                config = config,
                transport = transport,
                remoteConfigClient = remoteConfigClient,
                queueStore = queueStore,
                deviceContextProvider = deviceContextProvider,
                runtimeRegistration = runtimeRegistration,
                clock = clock,
                random = random,
                executor = executor,
            )?.let { return it }
            return createWithoutAndroidBootstrap(
                application = application,
                config = config,
                transport = transport,
                remoteConfigClient = remoteConfigClient,
                queueStore = queueStore,
                deviceContextProvider = deviceContextProvider,
                runtimeRegistration = runtimeRegistration,
                clock = clock,
                random = random,
                executor = executor,
            )
        }

        @JvmSynthetic
        fun createWithoutAndroidBootstrap(
            application: Any? = null,
            config: DebugBundleConfig,
            transport: DebugBundleTransport = DebugBundleHttpTransport(),
            remoteConfigClient: DebugBundleRemoteConfigClient = DebugBundleHttpRemoteConfigClient(),
            queueStore: DebugBundleQueueStore? = null,
            deviceContextProvider: DebugBundleDeviceContextProvider? = null,
            runtimeRegistration: AutoCloseable? = null,
            clock: () -> Instant = { Instant.now() },
            random: () -> Double = { Random.nextDouble() },
            executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "debugbundle-android").apply {
                    isDaemon = true
                }
            },
        ): DebugBundleClient {
            return createDebugBundleClientWithoutAndroidBootstrap(
                application = application,
                config = config,
                transport = transport,
                remoteConfigClient = remoteConfigClient,
                queueStore = queueStore,
                deviceContextProvider = deviceContextProvider,
                runtimeRegistration = runtimeRegistration,
                clock = clock,
                random = random,
                executor = executor,
            )
        }

    }
}
