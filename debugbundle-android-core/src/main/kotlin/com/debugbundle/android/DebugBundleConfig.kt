package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleBuildInfo
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class DebugBundleConfig(
    val projectToken: String = "",
    val enabled: Boolean = true,
    val environment: String = "production",
    val service: String = "android-app",
    val endpoint: String = DEFAULT_ENDPOINT,
    val batchSize: Int = 10,
    val flushInterval: Duration = 3.seconds,
    val sampleRate: Double = 1.0,
    val sessionSampleRate: Double = 1.0,
    val requestTimeout: Duration = 5.seconds,
    val releaseChannel: String = "production",
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val maxEventsPerSession: Int = 100,
    val maxBreadcrumbs: Int = 20,
    val captureScreens: Boolean = true,
    val captureActions: Boolean = false,
    val captureNetwork: Boolean = true,
    val captureLogs: Boolean = true,
    val logLevel: DebugBundleLogLevel = DebugBundleLogLevel.Warning,
    val captureFatalExceptions: Boolean = true,
    val headerAllowlist: Set<String> = DEFAULT_HEADER_ALLOWLIST,
    val offlineQueueMaxEvents: Int = 500,
    val offlineQueueMaxBytes: Long = 5L * 1024L * 1024L,
    val offlineQueueTtl: Duration = 72.hours,
    val offlineQueuePath: Path? = null,
    val fatalCrashPath: Path? = null,
    val maxProbeLabels: Int = 50,
    val maxProbeEntriesPerLabel: Int = 10,
    val probeFlushOnError: Boolean = true,
    val redactFields: Set<String> = DEFAULT_REDACT_FIELDS,
    val sdkVersion: String = DEFAULT_SDK_VERSION,
) {
    var beforeSend: DebugBundleBeforeSend? = null
        private set

    fun withBeforeSend(hook: DebugBundleBeforeSend?): DebugBundleConfig = copy().also {
        it.beforeSend = hook
    }

    companion object {
        const val DEFAULT_ENDPOINT: String = "https://api.debugbundle.com/v1/events"

        val DEFAULT_REDACT_FIELDS: Set<String> = setOf(
            "password",
            "secret",
            "token",
            "api_key",
            "apikey",
            "access_token",
            "refresh_token",
            "private_key",
            "passwd",
            "card_number",
            "cvv",
            "cvc",
            "pin",
            "expiry",
            "phone",
            "bearer",
            "session_id",
            "otp",
            "verification_code",
            "authorization",
            "cookie",
            "ssn",
        )

        val DEFAULT_HEADER_ALLOWLIST: Set<String> = setOf(
            "user-agent",
            "content-type",
            "accept",
            "x-request-id",
            "x-correlation-id",
            "x-debugbundle-trace-id",
            "traceparent",
        )

        val DEFAULT_SDK_VERSION: String = DebugBundleBuildInfo.SDK_VERSION

        /**
         * Stable Java-friendly construction surface for bridges that cannot call
         * Kotlin's synthetic default-argument constructor safely.
         */
        @JvmStatic
        fun create(
            projectToken: String,
            enabled: Boolean,
            environment: String,
            service: String,
            endpoint: String,
            batchSize: Int,
            flushIntervalMillis: Long,
            sampleRate: Double,
            sessionSampleRate: Double,
            requestTimeoutMillis: Long,
            releaseChannel: String,
            appVersion: String?,
            buildNumber: String?,
            maxEventsPerSession: Int,
            maxBreadcrumbs: Int,
            captureScreens: Boolean,
            captureActions: Boolean,
            captureNetwork: Boolean,
            captureLogs: Boolean,
            logLevel: DebugBundleLogLevel,
            headerAllowlist: Set<String>,
            offlineQueueMaxEvents: Int,
            offlineQueueMaxBytes: Long,
            offlineQueueTtlMillis: Long,
            maxProbeLabels: Int,
            maxProbeEntriesPerLabel: Int,
            probeFlushOnError: Boolean,
            redactFields: Set<String>,
            sdkVersion: String,
        ): DebugBundleConfig {
            return DebugBundleConfig(
                projectToken = projectToken,
                enabled = enabled,
                environment = environment,
                service = service,
                endpoint = endpoint,
                batchSize = batchSize,
                flushInterval = flushIntervalMillis.milliseconds,
                sampleRate = sampleRate,
                sessionSampleRate = sessionSampleRate,
                requestTimeout = requestTimeoutMillis.milliseconds,
                releaseChannel = releaseChannel,
                appVersion = appVersion,
                buildNumber = buildNumber,
                maxEventsPerSession = maxEventsPerSession,
                maxBreadcrumbs = maxBreadcrumbs,
                captureScreens = captureScreens,
                captureActions = captureActions,
                captureNetwork = captureNetwork,
                captureLogs = captureLogs,
                logLevel = logLevel,
                headerAllowlist = headerAllowlist,
                offlineQueueMaxEvents = offlineQueueMaxEvents,
                offlineQueueMaxBytes = offlineQueueMaxBytes,
                offlineQueueTtl = offlineQueueTtlMillis.milliseconds,
                maxProbeLabels = maxProbeLabels,
                maxProbeEntriesPerLabel = maxProbeEntriesPerLabel,
                probeFlushOnError = probeFlushOnError,
                redactFields = redactFields,
                sdkVersion = sdkVersion,
            )
        }
    }
}
