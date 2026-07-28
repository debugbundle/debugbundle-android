package com.debugbundle.android

import com.debugbundle.android.crash.DebugBundleFatalCrashStore
import com.debugbundle.android.crash.DebugBundleInstalledUncaughtExceptionHandler
import com.debugbundle.android.crash.DebugBundleUncaughtExceptionHandler
import com.debugbundle.android.crash.FileDebugBundleFatalCrashStore
import com.debugbundle.android.crash.NoopDebugBundleFatalCrashStore
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import kotlin.Function0

internal const val DEBUG_BUNDLE_ANDROID_SDK_NAME = "@debugbundle/sdk-android"
internal const val DEBUG_BUNDLE_ANDROID_SCHEMA_VERSION = "2026-03-01"
internal const val DEBUG_BUNDLE_NO_EVENT_SENT = -1L
internal const val DEBUG_BUNDLE_MIN_REMOTE_CONFIG_REFRESH_INTERVAL_MILLIS = 30_000L
internal val DEBUG_BUNDLE_LOG_RECORD_RESERVED_CONTEXT_KEYS = setOf("logger", "tag", "coroutine_name", "throwable")

internal fun debugBundleQueueLimits(config: DebugBundleConfig): DebugBundleQueueLimits {
    return DebugBundleQueueLimits(
        maxEvents = config.offlineQueueMaxEvents.coerceAtLeast(1),
        maxBytes = config.offlineQueueMaxBytes.coerceAtLeast(1L),
        ttlMillis = config.offlineQueueTtl.inWholeMilliseconds.coerceAtLeast(1L),
    )
}

internal fun normalizeDebugBundleConfig(config: DebugBundleConfig): DebugBundleConfig {
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
        headerAllowlist = config.headerAllowlist.map(::normalizeDebugBundleHeaderName).toSet(),
    ).withBeforeSend(config.beforeSend)
}

internal fun defaultDebugBundleQueueStore(config: DebugBundleConfig): DebugBundleQueueStore {
    return config.offlineQueuePath?.let(::FileDebugBundleQueueStore) ?: InMemoryDebugBundleQueueStore()
}

internal fun defaultDebugBundleFatalCrashStore(config: DebugBundleConfig): DebugBundleFatalCrashStore {
    val crashPath = config.fatalCrashPath
        ?: config.offlineQueuePath?.resolveSibling("debugbundle-fatal-crash.json")
    return crashPath?.let(::FileDebugBundleFatalCrashStore) ?: NoopDebugBundleFatalCrashStore()
}

internal fun initialDebugBundleStatus(config: DebugBundleConfig): DebugBundleStatus {
    return if (config.enabled && config.projectToken.isNotBlank()) {
        DebugBundleStatus.Healthy
    } else {
        DebugBundleStatus.Disconnected
    }
}

internal fun tryCreateAndroidBootstrap(
    application: Any?,
    config: DebugBundleConfig,
    transport: DebugBundleTransport,
    remoteConfigClient: DebugBundleRemoteConfigClient,
    queueStore: DebugBundleQueueStore?,
    deviceContextProvider: DebugBundleDeviceContextProvider?,
    runtimeRegistration: AutoCloseable?,
    clock: Function0<*>,
    random: Function0<*>,
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

internal fun normalizeDebugBundleHeaderName(value: String): String {
    return value.trim().lowercase()
}

internal fun createDebugBundleClientWithoutAndroidBootstrap(
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
): DebugBundleClient {
    application?.hashCode() // Reserved for future Android lifecycle wiring.
    val normalizedConfig = normalizeDebugBundleConfig(config)
    val resolvedFatalCrashStore = defaultDebugBundleFatalCrashStore(normalizedConfig)
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
        queueStore = queueStore ?: defaultDebugBundleQueueStore(normalizedConfig),
        deviceContextProvider = deviceContextProvider ?: JvmDebugBundleDeviceContextProvider(normalizedConfig),
        fatalCrashStore = resolvedFatalCrashStore,
        fatalCrashHandlerRegistration = fatalCrashHandlerRegistration,
        runtimeRegistration = runtimeRegistration,
        clock = clock,
        random = random,
        executor = executor,
    )
}
