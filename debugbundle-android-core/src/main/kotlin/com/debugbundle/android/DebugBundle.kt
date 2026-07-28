package com.debugbundle.android

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineExceptionHandler

object DebugBundle : DebugBundleCaptureSink, DebugBundleCoroutineSupport {
    @Volatile
    private var client: DebugBundleClient = DebugBundleClient.create(
        config = DebugBundleConfig(enabled = false),
        transport = DebugBundleTransport { DebugBundleTransportResult(statusCode = 204) },
    )

    val status: DebugBundleStatus
        get() = client.status

    val lastEventAt: Long?
        get() = client.lastEventAt

    val lifecycle: DebugBundleLifecycleRecorder
        get() = client.lifecycle

    @JvmStatic
    fun init(config: DebugBundleConfig): DebugBundleClient {
        return init(application = null, config = config)
    }

    @JvmStatic
    fun init(application: Any?, config: DebugBundleConfig): DebugBundleClient {
        client.close()
        return DebugBundleClient.create(application = application, config = config).also { client = it }
    }

    fun captureException(error: Throwable, context: Map<String, Any?> = emptyMap()) {
        client.captureException(error, context)
    }

    fun captureError(error: Throwable, context: Map<String, Any?> = emptyMap()) {
        client.captureError(error, context)
    }

    fun captureLog(
        message: String,
        level: DebugBundleLogLevel = DebugBundleLogLevel.Warning,
        context: Map<String, Any?> = emptyMap(),
    ) {
        client.captureLog(message, level, context)
    }

    fun captureRequest(
        request: DebugBundleRequestInfo,
        response: DebugBundleResponseInfo,
        context: Map<String, Any?> = emptyMap(),
        recordBreadcrumb: Boolean = true,
    ) {
        client.captureRequest(request, response, context, recordBreadcrumb)
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
        client.captureMessage(message, level, context)
    }

    fun setContext(key: String, value: Any?) {
        client.setContext(key, value)
    }

    fun probe(label: String, data: Any?, options: ProbeOptions = ProbeOptions()) {
        client.probe(label, data, options)
    }

    fun probe(label: String, options: ProbeOptions = ProbeOptions(), producer: () -> Any?) {
        client.probe(label, options, producer)
    }

    @JvmStatic
    fun captureExternalEvent(event: Map<String, Any?>): Boolean {
        return client.captureExternalEvent(event)
    }

    @JvmStatic
    fun isExternalProbeActive(label: String): Boolean {
        return client.isExternalProbeActive(label)
    }

    @JvmStatic
    fun captureExternalProbe(
        sdkVersion: String,
        service: String,
        environment: String,
        label: String,
        data: Any?,
        occurredAt: String,
    ): Boolean {
        return client.captureExternalProbe(
            sdkVersion = sdkVersion,
            service = service,
            environment = environment,
            label = label,
            data = data,
            occurredAt = occurredAt,
        )
    }

    fun captureBreadcrumb(
        breadcrumbType: String,
        route: String? = null,
        data: Map<String, Any?> = emptyMap(),
    ) {
        client.captureBreadcrumb(breadcrumbType, route, data)
    }

    override fun recordBreadcrumb(
        breadcrumbType: String,
        route: String?,
        data: Map<String, Any?>,
    ) {
        captureBreadcrumb(breadcrumbType, route, data)
    }

    override fun activateProbeTriggerToken(token: String): Boolean {
        return client.activateProbeTriggerToken(token)
    }

    fun recordScreen(screenName: String, previousScreen: String? = null, source: String = "manual") {
        client.recordScreen(screenName, previousScreen, source)
    }

    fun recordAppForeground() {
        client.recordAppForeground()
    }

    fun recordAppBackground() {
        client.recordAppBackground()
    }

    fun recordAction(actionType: String, targetType: String, resourceName: String? = null) {
        client.recordAction(actionType, targetType, resourceName)
    }

    fun flush(timeout: Duration = DebugBundleConfig().requestTimeout) {
        client.flush(timeout)
    }

    fun refreshRemoteConfig(force: Boolean = true) {
        client.refreshRemoteConfig(force)
    }

    override fun coroutineExceptionHandler(context: Map<String, Any?>): CoroutineExceptionHandler {
        return client.coroutineExceptionHandler(context)
    }
}
