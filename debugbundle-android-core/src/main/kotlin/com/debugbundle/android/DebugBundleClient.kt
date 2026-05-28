package com.debugbundle.android

import com.debugbundle.android.crash.DebugBundleFatalCrashStore
import com.debugbundle.android.crash.DebugBundleInstalledUncaughtExceptionHandler
import com.debugbundle.android.crash.DebugBundleUncaughtExceptionHandler
import com.debugbundle.android.crash.FileDebugBundleFatalCrashStore
import com.debugbundle.android.crash.NoopDebugBundleFatalCrashStore
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
import kotlin.Function0
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer

class DebugBundleClient private constructor(
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
    private val normalizedHeaderAllowlist = config.headerAllowlist.map(::normalizeHeaderName).toSet()
    private val capturePolicyRef = AtomicReference(DebugBundleCapturePolicy.defaultWhenConfigFetchFails())
    private val statusRef = AtomicReference(initialStatus(config))
    private val lastEventAtRef = AtomicLong(NO_EVENT_SENT)
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
    private var sessionEventCount: Int = 0
    private var remoteConfigETag: String? = null
    private var lastRemoteConfigRefreshAtMillis: Long = 0
    private var remoteConfigRefreshInFlight: Boolean = false
    private val remoteProbeState = DebugBundleRemoteProbeState()
    private val registeredCloseables = mutableListOf<AutoCloseable>()

    val status: DebugBundleStatus
        get() = statusRef.get()

    val lastEventAt: Long?
        get() = lastEventAtRef.get().takeIf { it != NO_EVENT_SENT }

    val lifecycle: DebugBundleLifecycleRecorder
        get() = DebugBundleLifecycleRecorder(this)

    init {
        if (captureConfigured) {
            syncBufferFromQueue(clock().toEpochMilli())
            if (config.captureFatalExceptions) {
                replayPendingFatalCrash()
            }
            executor.execute { refreshRemoteConfig(force = true) }
            val periodMillis = config.flushInterval.inWholeMilliseconds.coerceAtLeast(500)
            executor.scheduleAtFixedRate(
                { flushSafely(config.requestTimeout) },
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun captureException(error: Throwable, context: Map<String, Any?> = emptyMap()) {
        enqueueEvent(
            eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION,
            correlationTraceId = context["trace_id"]?.toString(),
            countTowardSession = false,
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
    ) {
        if (!capturePolicyRef.get().capturesLog(level, config.captureLogs, config.logLevel)) {
            return
        }
        val mergedContext = mergeContext(context)
        val sanitizedContext = redactor.sanitize(
            mergedContext.filterKeys { it !in LOG_RECORD_RESERVED_CONTEXT_KEYS },
        )
        enqueueEvent(
            eventType = DebugBundleEventTypes.LOG_EVENT,
            correlationTraceId = mergedContext["trace_id"]?.toString(),
            countTowardSession = true,
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
    ) {
        if (!config.captureNetwork) {
            return
        }
        if (capturePolicyRef.get().capturesStandaloneRequestEvent(response.statusCode)) {
            enqueueEvent(
                eventType = DebugBundleEventTypes.REQUEST_EVENT,
                correlationTraceId = request.traceId ?: context["trace_id"]?.toString(),
                countTowardSession = true,
            ) {
                buildRequestPayload(request, response, context)
            }
        }
        if (recordBreadcrumb) {
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
            synchronized(lock) {
                persistentContext[key] = value
            }
        }
    }

    fun probe(label: String, data: Any?, options: ProbeOptions = ProbeOptions()) {
        captureProbe(label, options) { data }
    }

    fun probe(label: String, options: ProbeOptions = ProbeOptions(), producer: () -> Any?) {
        captureProbe(label, options, producer)
    }

    private fun captureProbe(label: String, options: ProbeOptions, producer: () -> Any?) {
        safely {
            if (!captureConfigured || label.isBlank()) {
                return@safely
            }
            val matchingDirectives = matchingRemoteProbeDirectives(label)
            if (!remoteProbeState.probesEnabled() || (options.heavy && matchingDirectives.isEmpty())) {
                return@safely
            }

            val probeData = sanitizeProbeData(producer())
            if (!options.heavy) {
                probeBuffer.add(label, probeData, clock().toString())
            }

            if (
                !sessionSampledIn ||
                matchingDirectives.isEmpty() ||
                !capturePolicyRef.get().capturesStandaloneProbeEvents()
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
        flushSafely(timeout)
    }

    fun refreshRemoteConfig(force: Boolean = true) {
        refreshRemoteConfigSafely(force)
    }

    override fun coroutineExceptionHandler(context: Map<String, Any?>): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { coroutineContext, throwable ->
            val coroutineMetadata = LinkedHashMap<String, Any?>(context).apply {
                put("mechanism", "coroutine_exception_handler")
                put("handled", false)
                coroutineContext[CoroutineName]?.name?.let { put("coroutine_name", it) }
            }
            captureException(throwable, coroutineMetadata)
        }
    }

    @JvmSynthetic
    fun registerRuntimeCloseable(closeable: AutoCloseable) {
        synchronized(lock) {
            registeredCloseables += closeable
        }
    }

    override fun close() {
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
                breadcrumbType = breadcrumbType,
                route = route ?: lastScreenName,
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
        executor.execute { refreshRemoteConfig(force = false) }
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
        payloadBuilder: () -> JsonObject,
    ): Boolean {
        var enqueued = false
        safely {
            if (!captureConfigured || !shouldCaptureEvent(eventType) || !shouldSample(eventType)) {
                return@safely
            }
            val envelope = DebugBundleEnvelope(
                schemaVersion = SCHEMA_VERSION,
                eventId = UUID.randomUUID().toString(),
                eventType = eventType,
                sdkName = SDK_NAME,
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
            val suppressionKey = buildSuppressionKey(envelope)
            if (suppressionKey != null && !suppressionTracker.shouldCapture(suppressionKey, clock().toEpochMilli())) {
                return@safely
            }
            enqueuePersistedEvent(envelope, countTowardSession)
            enqueued = true
        }
        return enqueued
    }

    private fun enqueuePersistedEvent(event: DebugBundleEnvelope, countTowardSession: Boolean) {
        val nowMillis = clock().toEpochMilli()
        val limits = queueLimits()
        queueStore.append(listOf(event), nowMillis, limits)
        syncBufferFromQueue(nowMillis)
        if (countTowardSession && event.eventType != DebugBundleEventTypes.FRONTEND_EXCEPTION) {
            sessionEventCount += 1
        }
        val shouldFlush = synchronized(lock) {
            buffer.size >= config.batchSize
        }
        if (shouldFlush) {
            executor.execute { flushSafely(config.requestTimeout) }
        }
    }

    private fun syncBufferFromQueue(nowMillis: Long) {
        val pending = queueStore.snapshot(nowMillis, queueLimits())
        synchronized(lock) {
            buffer.clear()
            pending.forEach { buffer.addLast(it.envelope) }
        }
    }

    private fun queueLimits(): DebugBundleQueueLimits {
        return DebugBundleQueueLimits(
            maxEvents = config.offlineQueueMaxEvents.coerceAtLeast(1),
            maxBytes = config.offlineQueueMaxBytes.coerceAtLeast(1L),
            ttlMillis = config.offlineQueueTtl.inWholeMilliseconds.coerceAtLeast(1L),
        )
    }

    private fun filterHeaders(headers: Map<String, String>): Map<String, String> {
        val filtered = LinkedHashMap<String, String>()
        headers.forEach { (name, value) ->
            val normalizedName = normalizeHeaderName(name)
            if (normalizedName in normalizedHeaderAllowlist) {
                filtered[normalizedName] = value
            }
        }
        return filtered
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

    private fun applyRemoteProbeState(
        probesEnabled: Boolean,
        remoteProbesEnabled: Boolean,
        directives: List<DebugBundleRemoteProbeDirective>,
        triggerTokenKey: String?,
    ) {
        remoteProbeState.applyConfig(probesEnabled, remoteProbesEnabled, directives, triggerTokenKey, clock())
    }

    private fun applyPiggybackProbeDirectives(directives: List<DebugBundleRemoteProbeDirective>?) {
        remoteProbeState.applyPiggybackDirectives(directives, clock())
    }

    private fun buildRequestPayload(
        request: DebugBundleRequestInfo,
        response: DebugBundleResponseInfo,
        context: Map<String, Any?>,
    ): JsonObject {
        return buildJsonObject {
            put("method", JsonPrimitive(request.method))
            put("url", JsonPrimitive(request.url))
            put("headers", redactor.sanitize(filterHeaders(request.headers)))
            request.routeTemplate?.let { put("route_template", JsonPrimitive(it)) }
            put("response_status", JsonPrimitive(response.statusCode))
            response.durationMillis?.let { put("duration_ms", JsonPrimitive(it)) }
            put("response_headers", redactor.sanitize(filterHeaders(response.headers)))
            put("context", redactor.sanitize(mergeContext(context)))
        }
    }

    private fun buildSuppressionKey(event: DebugBundleEnvelope): String? {
        return when (event.eventType) {
            DebugBundleEventTypes.FRONTEND_EXCEPTION -> {
                val error = event.payload["error"] as? JsonObject ?: return null
                val stackTrace = error["stack_trace"] as? JsonArray
                val firstFrame = stackTrace?.firstOrNull()?.toString()
                listOf(
                    event.eventType,
                    error["type"]?.toString(),
                    error["message"]?.toString(),
                    firstFrame,
                    event.payload["route"]?.toString(),
                ).joinToString("|")
            }

            DebugBundleEventTypes.LOG_EVENT -> {
                listOf(
                    event.eventType,
                    event.payload["level"]?.toString(),
                    event.payload["message"]?.toString(),
                    event.payload["context"]?.toString(),
                ).joinToString("|")
            }

            DebugBundleEventTypes.REQUEST_EVENT -> {
                listOf(
                    event.eventType,
                    event.payload["method"]?.toString(),
                    event.payload["url"]?.toString(),
                    event.payload["response_status"]?.toString(),
                ).joinToString("|")
            }

            else -> null
        }
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

    private fun enqueueSuppressionAggregates() {
        val nowMillis = clock().toEpochMilli()
        suppressionTracker.drainAggregates(nowMillis).forEach { aggregate ->
            enqueuePersistedEvent(
                DebugBundleEnvelope(
                    schemaVersion = SCHEMA_VERSION,
                    eventId = UUID.randomUUID().toString(),
                    eventType = DebugBundleEventTypes.ERROR_SUPPRESSED,
                    sdkName = SDK_NAME,
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
                countTowardSession = false,
            )
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

    private fun buildDeviceContext(): JsonObject {
        return json.encodeToJsonElement(
            DebugBundleDeviceContext.serializer(),
            deviceContextProvider.snapshot(),
        ) as JsonObject
    }

    private fun replayPendingFatalCrash() {
        val record = fatalCrashStore.load() ?: return
        val enqueued = enqueueEvent(
            eventType = DebugBundleEventTypes.FRONTEND_EXCEPTION,
            correlationTraceId = null,
            countTowardSession = false,
            occurredAt = record.occurredAt,
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
        if (enqueued) {
            fatalCrashStore.clear()
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

    private fun refreshRemoteConfigSafely(force: Boolean) {
        safely {
            if (!captureConfigured) {
                return@safely
            }
            synchronized(lock) {
                val nowMillis = clock().toEpochMilli()
                if (remoteConfigRefreshInFlight) {
                    return@synchronized
                }
                if (!force && nowMillis - lastRemoteConfigRefreshAtMillis < MIN_REMOTE_CONFIG_REFRESH_INTERVAL_MILLIS) {
                    return@synchronized
                }
                remoteConfigRefreshInFlight = true
            }
            try {
                when (
                    val result = remoteConfigClient.fetch(
                        DebugBundleRemoteConfigRequest(
                            projectToken = config.projectToken,
                            endpoint = config.endpoint,
                            timeout = config.requestTimeout,
                            eTag = synchronized(lock) { remoteConfigETag },
                        ),
                    )
                ) {
                    is DebugBundleRemoteConfigResult.Loaded -> synchronized(lock) {
                        capturePolicyRef.set(DebugBundleCapturePolicy.fromRemotePolicy(result.config.capturePolicy))
                        remoteConfigETag = result.eTag ?: remoteConfigETag
                        lastRemoteConfigRefreshAtMillis = clock().toEpochMilli()
                        applyRemoteProbeState(
                            probesEnabled = result.config.probesEnabled,
                            remoteProbesEnabled = result.config.remoteProbesEnabled,
                            directives = result.config.activeProbes,
                            triggerTokenKey = result.config.triggerTokenKey,
                        )
                    }

                    is DebugBundleRemoteConfigResult.NotModified -> synchronized(lock) {
                        remoteConfigETag = result.eTag ?: remoteConfigETag
                        lastRemoteConfigRefreshAtMillis = clock().toEpochMilli()
                    }

                    DebugBundleRemoteConfigResult.Failed -> synchronized(lock) {
                        capturePolicyRef.set(DebugBundleCapturePolicy.defaultWhenConfigFetchFails())
                        lastRemoteConfigRefreshAtMillis = clock().toEpochMilli()
                    }
                }
            } finally {
                synchronized(lock) {
                    remoteConfigRefreshInFlight = false
                }
            }
        }
    }

    private fun flushSafely(timeout: Duration) {
        safely {
            enqueueSuppressionAggregates()
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
                    result.isSuccess -> synchronized(lock) {
                        applyPiggybackProbeDirectives(result.probeDirectives)
                        if (batch.any { it.eventType == DebugBundleEventTypes.FRONTEND_EXCEPTION }) {
                            breadcrumbBuffer.clear()
                        }
                        queueStore.removeLeading(batch.size, clock().toEpochMilli(), queueLimits())
                        syncBufferFromQueue(clock().toEpochMilli())
                        nextRetryAtMillis = 0
                        resetHealthyStatus()
                        lastEventAtRef.set(clock().toEpochMilli())
                    }

                    result.shouldRetry -> {
                        consecutiveFailures += 1
                        updateFailureStatus()
                        val retryAfter = buildRetryDelay(result)
                        nextRetryAtMillis = clock().toEpochMilli() + retryAfter.inWholeMilliseconds
                    }

                    else -> synchronized(lock) {
                        queueStore.removeLeading(batch.size, clock().toEpochMilli(), queueLimits())
                        syncBufferFromQueue(clock().toEpochMilli())
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

    private fun mergeContext(context: Map<String, Any?>): Map<String, Any?> {
        return synchronized(lock) {
            LinkedHashMap<String, Any?>(persistentContext).apply {
                putAll(context)
            }
        }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            statusRef.set(DebugBundleStatus.Degraded)
        }
    }

    companion object {
        private const val SDK_NAME = "@debugbundle/sdk-android"
        private const val SCHEMA_VERSION = "2026-03-01"
        private const val NO_EVENT_SENT = -1L
        private const val MIN_REMOTE_CONFIG_REFRESH_INTERVAL_MILLIS = 30_000L
        private val LOG_RECORD_RESERVED_CONTEXT_KEYS = setOf("logger", "tag", "coroutine_name", "throwable")

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
            application?.hashCode() // Reserved for future Android lifecycle wiring.
            val normalizedConfig = normalize(config)
            val resolvedFatalCrashStore = defaultFatalCrashStore(normalizedConfig)
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            val fatalCrashHandlerRegistration =
                if (normalizedConfig.captureFatalExceptions &&
                    normalizedConfig.enabled &&
                    normalizedConfig.projectToken.isNotBlank()
                ) {
                DebugBundleInstalledUncaughtExceptionHandler(
                    handler = DebugBundleUncaughtExceptionHandler(
                        crashStore = resolvedFatalCrashStore,
                        previous = previousHandler,
                        clock = clock,
                    ),
                    previous = previousHandler,
                )
            } else {
                null
            }
            return DebugBundleClient(
                config = normalizedConfig,
                transport = transport,
                remoteConfigClient = remoteConfigClient,
                queueStore = queueStore ?: defaultQueueStore(normalizedConfig),
                deviceContextProvider = deviceContextProvider ?: JvmDebugBundleDeviceContextProvider(normalizedConfig),
                fatalCrashStore = resolvedFatalCrashStore,
                fatalCrashHandlerRegistration = fatalCrashHandlerRegistration,
                runtimeRegistration = runtimeRegistration,
                clock = clock,
                random = random,
                executor = executor,
            )
        }

        private fun normalize(config: DebugBundleConfig): DebugBundleConfig {
            return config.copy(
                service = config.service.ifBlank { "android-app" },
                environment = config.environment.ifBlank { "production" },
                endpoint = config.endpoint.ifBlank { DebugBundleConfig.DEFAULT_ENDPOINT },
                batchSize = config.batchSize.coerceAtLeast(1),
                sampleRate = config.sampleRate.coerceIn(0.0, 1.0),
                sessionSampleRate = config.sessionSampleRate.coerceIn(0.0, 1.0),
                maxEventsPerSession = config.maxEventsPerSession.coerceAtLeast(1),
                offlineQueueMaxEvents = config.offlineQueueMaxEvents.coerceAtLeast(1),
                offlineQueueMaxBytes = config.offlineQueueMaxBytes.coerceAtLeast(1L),
                maxProbeLabels = config.maxProbeLabels.coerceAtLeast(1),
                maxProbeEntriesPerLabel = config.maxProbeEntriesPerLabel.coerceAtLeast(1),
                headerAllowlist = config.headerAllowlist.map(::normalizeHeaderName).toSet(),
            )
        }

        private fun defaultQueueStore(config: DebugBundleConfig): DebugBundleQueueStore {
            return config.offlineQueuePath?.let(::FileDebugBundleQueueStore) ?: InMemoryDebugBundleQueueStore()
        }

        private fun defaultFatalCrashStore(config: DebugBundleConfig): DebugBundleFatalCrashStore {
            val crashPath = config.fatalCrashPath
                ?: config.offlineQueuePath?.resolveSibling("debugbundle-fatal-crash.json")
            return crashPath?.let(::FileDebugBundleFatalCrashStore) ?: NoopDebugBundleFatalCrashStore()
        }

        private fun initialStatus(config: DebugBundleConfig): DebugBundleStatus {
            return if (config.enabled && config.projectToken.isNotBlank()) {
                DebugBundleStatus.Healthy
            } else {
                DebugBundleStatus.Disconnected
            }
        }

        private fun tryCreateAndroidBootstrap(
            application: Any?,
            config: DebugBundleConfig,
            transport: DebugBundleTransport,
            remoteConfigClient: DebugBundleRemoteConfigClient,
            queueStore: DebugBundleQueueStore?,
            deviceContextProvider: DebugBundleDeviceContextProvider?,
            runtimeRegistration: AutoCloseable?,
            clock: () -> Instant,
            random: () -> Double,
            executor: ScheduledExecutorService,
        ): DebugBundleClient? {
            if (application == null) {
                return null
            }
            return runCatching {
                val bootstrapClass = Class.forName("com.debugbundle.android.runtime.DebugBundleAndroidBootstrap")
                val method = bootstrapClass.getMethod(
                    "createClient",
                    Any::class.java,
                    DebugBundleConfig::class.java,
                    DebugBundleTransport::class.java,
                    DebugBundleRemoteConfigClient::class.java,
                    DebugBundleQueueStore::class.java,
                    DebugBundleDeviceContextProvider::class.java,
                    AutoCloseable::class.java,
                    Function0::class.java,
                    Function0::class.java,
                    ScheduledExecutorService::class.java,
                )
                method.invoke(
                    null,
                    application,
                    config,
                    transport,
                    remoteConfigClient,
                    queueStore,
                    deviceContextProvider,
                    runtimeRegistration,
                    clock,
                    random,
                    executor,
                ) as DebugBundleClient
            }.getOrNull()
        }

        private fun normalizeHeaderName(value: String): String {
            return value.trim().lowercase()
        }
    }
}
