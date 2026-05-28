package com.debugbundle.android.runtime

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleLogLevel
import kotlin.time.Duration.Companion.milliseconds

internal class DebugBundleAndroidConfigStore(
    application: Application,
) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(config: DebugBundleConfig) {
        preferences.edit {
            putString(KEY_PROJECT_TOKEN, config.projectToken)
            putBoolean(KEY_ENABLED, config.enabled)
            putString(KEY_ENVIRONMENT, config.environment)
            putString(KEY_SERVICE, config.service)
            putString(KEY_ENDPOINT, config.endpoint)
            putInt(KEY_BATCH_SIZE, config.batchSize)
            putLong(KEY_REQUEST_TIMEOUT_MILLIS, config.requestTimeout.inWholeMilliseconds)
            putString(KEY_RELEASE_CHANNEL, config.releaseChannel)
            putString(KEY_APP_VERSION, config.appVersion)
            putString(KEY_BUILD_NUMBER, config.buildNumber)
            putBoolean(KEY_CAPTURE_FATAL_EXCEPTIONS, config.captureFatalExceptions)
            putBoolean(KEY_CAPTURE_SCREENS, config.captureScreens)
            putBoolean(KEY_CAPTURE_ACTIONS, config.captureActions)
            putBoolean(KEY_CAPTURE_NETWORK, config.captureNetwork)
            putBoolean(KEY_CAPTURE_LOGS, config.captureLogs)
            putString(KEY_LOG_LEVEL, config.logLevel.name)
            putInt(KEY_OFFLINE_QUEUE_MAX_EVENTS, config.offlineQueueMaxEvents)
            putLong(KEY_OFFLINE_QUEUE_MAX_BYTES, config.offlineQueueMaxBytes)
            putLong(KEY_OFFLINE_QUEUE_TTL_MILLIS, config.offlineQueueTtl.inWholeMilliseconds)
            putStringSet(KEY_HEADER_ALLOWLIST, config.headerAllowlist)
            putStringSet(KEY_REDACT_FIELDS, config.redactFields)
            putString(KEY_SDK_VERSION, config.sdkVersion)
        }
    }

    fun load(): DebugBundleConfig? {
        val token = preferences.getString(KEY_PROJECT_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return DebugBundleConfig(
            projectToken = token,
            enabled = preferences.getBoolean(KEY_ENABLED, true),
            environment = preferences.getString(KEY_ENVIRONMENT, "production").orEmpty(),
            service = preferences.getString(KEY_SERVICE, "android-app").orEmpty(),
            endpoint = preferences.getString(KEY_ENDPOINT, DebugBundleConfig.DEFAULT_ENDPOINT).orEmpty(),
            batchSize = preferences.getInt(KEY_BATCH_SIZE, 10),
            requestTimeout = preferences.getLong(KEY_REQUEST_TIMEOUT_MILLIS, 5_000L).milliseconds,
            releaseChannel = preferences.getString(KEY_RELEASE_CHANNEL, "production").orEmpty(),
            appVersion = preferences.getString(KEY_APP_VERSION, null),
            buildNumber = preferences.getString(KEY_BUILD_NUMBER, null),
            captureFatalExceptions = preferences.getBoolean(KEY_CAPTURE_FATAL_EXCEPTIONS, true),
            captureScreens = preferences.getBoolean(KEY_CAPTURE_SCREENS, true),
            captureActions = preferences.getBoolean(KEY_CAPTURE_ACTIONS, false),
            captureNetwork = preferences.getBoolean(KEY_CAPTURE_NETWORK, true),
            captureLogs = preferences.getBoolean(KEY_CAPTURE_LOGS, true),
            logLevel = preferences.getString(KEY_LOG_LEVEL, DebugBundleLogLevel.Warning.name)
                ?.let { runCatching { DebugBundleLogLevel.valueOf(it) }.getOrNull() }
                ?: DebugBundleLogLevel.Warning,
            offlineQueueMaxEvents = preferences.getInt(KEY_OFFLINE_QUEUE_MAX_EVENTS, 500),
            offlineQueueMaxBytes = preferences.getLong(KEY_OFFLINE_QUEUE_MAX_BYTES, 5L * 1024L * 1024L),
            offlineQueueTtl = preferences.getLong(KEY_OFFLINE_QUEUE_TTL_MILLIS, 72L * 60L * 60L * 1_000L).milliseconds,
            headerAllowlist = preferences.getStringSet(KEY_HEADER_ALLOWLIST, DebugBundleConfig.DEFAULT_HEADER_ALLOWLIST)
                ?.toSet()
                ?: DebugBundleConfig.DEFAULT_HEADER_ALLOWLIST,
            redactFields = preferences.getStringSet(KEY_REDACT_FIELDS, DebugBundleConfig.DEFAULT_REDACT_FIELDS)
                ?.toSet()
                ?: DebugBundleConfig.DEFAULT_REDACT_FIELDS,
            sdkVersion = preferences.getString(KEY_SDK_VERSION, DebugBundleConfig.DEFAULT_SDK_VERSION)
                ?: DebugBundleConfig.DEFAULT_SDK_VERSION,
        )
    }

    fun lastReportedExitTimestamp(): Long = preferences.getLong(KEY_LAST_REPORTED_EXIT_TIMESTAMP, 0L)

    fun markReportedExitTimestamp(timestamp: Long) {
        preferences.edit {
            putLong(KEY_LAST_REPORTED_EXIT_TIMESTAMP, timestamp)
        }
    }

    private companion object {
        private const val PREFERENCES_NAME = "debugbundle-android"
        private const val KEY_PROJECT_TOKEN = "project_token"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENVIRONMENT = "environment"
        private const val KEY_SERVICE = "service"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_REQUEST_TIMEOUT_MILLIS = "request_timeout_millis"
        private const val KEY_RELEASE_CHANNEL = "release_channel"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_BUILD_NUMBER = "build_number"
        private const val KEY_CAPTURE_FATAL_EXCEPTIONS = "capture_fatal_exceptions"
        private const val KEY_CAPTURE_SCREENS = "capture_screens"
        private const val KEY_CAPTURE_ACTIONS = "capture_actions"
        private const val KEY_CAPTURE_NETWORK = "capture_network"
        private const val KEY_CAPTURE_LOGS = "capture_logs"
        private const val KEY_LOG_LEVEL = "log_level"
        private const val KEY_OFFLINE_QUEUE_MAX_EVENTS = "offline_queue_max_events"
        private const val KEY_OFFLINE_QUEUE_MAX_BYTES = "offline_queue_max_bytes"
        private const val KEY_OFFLINE_QUEUE_TTL_MILLIS = "offline_queue_ttl_millis"
        private const val KEY_HEADER_ALLOWLIST = "header_allowlist"
        private const val KEY_REDACT_FIELDS = "redact_fields"
        private const val KEY_SDK_VERSION = "sdk_version"
        private const val KEY_LAST_REPORTED_EXIT_TIMESTAMP = "last_reported_exit_timestamp"
    }
}
