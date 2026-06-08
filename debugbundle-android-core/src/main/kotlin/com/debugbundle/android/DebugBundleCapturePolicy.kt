package com.debugbundle.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

enum class DebugBundleCapturePreset {
    Minimal,
    Balanced,
    Investigative,
    ;

    companion object {
        fun fromWireValue(value: String?): DebugBundleCapturePreset? {
            return when (value?.lowercase()) {
                "minimal" -> Minimal
                "balanced" -> Balanced
                "investigative" -> Investigative
                else -> null
            }
        }
    }
}

enum class DebugBundleCaptureLogsMode {
    Off,
    Error,
    Warning,
    Info,
    ;

    companion object {
        fun fromWireValue(value: String?): DebugBundleCaptureLogsMode? {
            return when (value?.lowercase()) {
                "off" -> Off
                "error" -> Error
                "warning" -> Warning
                "info" -> Info
                else -> null
            }
        }
    }
}

enum class DebugBundleCaptureRequestEventsMode {
    Off,
    FailuresOnly,
    Filtered,
    All,
    ;

    companion object {
        fun fromWireValue(value: String?): DebugBundleCaptureRequestEventsMode? {
            return when (value?.lowercase()) {
                "off" -> Off
                "failures_only" -> FailuresOnly
                "filtered" -> Filtered
                "all" -> All
                else -> null
            }
        }
    }
}

enum class DebugBundleCaptureBreadcrumbsMode {
    LocalOnly,
    ExceptionOnly,
    Standalone,
    ;

    companion object {
        fun fromWireValue(value: String?): DebugBundleCaptureBreadcrumbsMode? {
            return when (value?.lowercase()) {
                "local_only" -> LocalOnly
                "exception_only" -> ExceptionOnly
                "standalone" -> Standalone
                else -> null
            }
        }
    }
}

enum class DebugBundleCaptureProbeEventsMode {
    BufferOnly,
    StandaloneWhenActivated,
    ;

    companion object {
        fun fromWireValue(value: String?): DebugBundleCaptureProbeEventsMode? {
            return when (value?.lowercase()) {
                "buffer_only" -> BufferOnly
                "standalone_when_activated" -> StandaloneWhenActivated
                else -> null
            }
        }
    }
}

data class DebugBundleCapturePolicy(
    val preset: DebugBundleCapturePreset,
    val captureLogs: DebugBundleCaptureLogsMode,
    val captureRequestEvents: DebugBundleCaptureRequestEventsMode,
    val captureBreadcrumbs: DebugBundleCaptureBreadcrumbsMode,
    val captureProbeEvents: DebugBundleCaptureProbeEventsMode,
    val immediateClientErrorStatuses: Set<Int>,
    val immediateClientErrorPathRules: List<DebugBundleImmediateClientErrorPathRule> = emptyList(),
) {
    fun capturesLog(level: DebugBundleLogLevel, localEnabled: Boolean, localThreshold: DebugBundleLogLevel): Boolean {
        if (!localEnabled || !localThreshold.captures(level)) {
            return false
        }
        val policyThreshold = when (captureLogs) {
            DebugBundleCaptureLogsMode.Off -> return false
            DebugBundleCaptureLogsMode.Error -> DebugBundleLogLevel.Error
            DebugBundleCaptureLogsMode.Warning -> DebugBundleLogLevel.Warning
            DebugBundleCaptureLogsMode.Info -> DebugBundleLogLevel.Info
        }
        return policyThreshold.captures(level)
    }

    fun capturesStandaloneRequestEvent(responseStatus: Int?): Boolean {
        return capturesStandaloneRequestEvent(responseStatus, requestPath = null, httpMethod = null)
    }

    fun capturesStandaloneRequestEvent(responseStatus: Int?, requestPath: String?, httpMethod: String?): Boolean {
        if (isImmediateRequestIncident(responseStatus, requestPath, httpMethod)) {
            return true
        }
        return when (captureRequestEvents) {
            DebugBundleCaptureRequestEventsMode.Off -> false
            DebugBundleCaptureRequestEventsMode.FailuresOnly -> responseStatus != null && responseStatus >= 500
            DebugBundleCaptureRequestEventsMode.Filtered -> false
            DebugBundleCaptureRequestEventsMode.All -> true
        }
    }

    fun capturesStandaloneBreadcrumbs(): Boolean {
        return captureBreadcrumbs == DebugBundleCaptureBreadcrumbsMode.Standalone
    }

    fun capturesStandaloneProbeEvents(): Boolean {
        return captureProbeEvents == DebugBundleCaptureProbeEventsMode.StandaloneWhenActivated
    }

    fun isImmediateRequestIncident(responseStatus: Int?): Boolean {
        return isImmediateRequestIncident(responseStatus, requestPath = null, httpMethod = null)
    }

    fun isImmediateRequestIncident(responseStatus: Int?, requestPath: String?, httpMethod: String?): Boolean {
        if (responseStatus == null) {
            return false
        }
        if (responseStatus >= 500) {
            return true
        }
        if (responseStatus in immediateClientErrorStatuses) {
            return true
        }
        if (matchesImmediateClientErrorPathRule(responseStatus, requestPath, httpMethod)) {
            return true
        }
        return when (preset) {
            DebugBundleCapturePreset.Minimal -> false
            DebugBundleCapturePreset.Balanced -> responseStatus in BALANCED_IMMEDIATE_REQUEST_STATUSES
            DebugBundleCapturePreset.Investigative -> responseStatus in INVESTIGATIVE_IMMEDIATE_REQUEST_STATUSES
        }
    }

    private fun matchesImmediateClientErrorPathRule(responseStatus: Int, requestPath: String?, httpMethod: String?): Boolean {
        if (responseStatus !in 400..499 || requestPath == null) {
            return false
        }
        val normalizedPath = normalizeRequestPath(requestPath)
        val normalizedMethod = httpMethod?.uppercase()
        return immediateClientErrorPathRules.any { rule ->
            if (rule.statusCode != responseStatus) {
                return@any false
            }
            if (rule.methods.isNotEmpty() && (normalizedMethod == null || normalizedMethod !in rule.methods)) {
                return@any false
            }
            if (rule.pathPattern.endsWith("*")) {
                normalizedPath.startsWith(rule.pathPattern.dropLast(1))
            } else {
                normalizedPath == rule.pathPattern
            }
        }
    }

    companion object {
        private val RECOMMENDED_IMMEDIATE_CLIENT_ERROR_STATUSES = listOf(401, 403, 409, 422)
        private val BALANCED_IMMEDIATE_REQUEST_STATUSES = setOf(408, 423, 424, 425, 429)
        private val INVESTIGATIVE_IMMEDIATE_REQUEST_STATUSES = BALANCED_IMMEDIATE_REQUEST_STATUSES + 409
        private val VALID_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        val MINIMAL: DebugBundleCapturePolicy = DebugBundleCapturePolicy(
            preset = DebugBundleCapturePreset.Minimal,
            captureLogs = DebugBundleCaptureLogsMode.Error,
            captureRequestEvents = DebugBundleCaptureRequestEventsMode.FailuresOnly,
            captureBreadcrumbs = DebugBundleCaptureBreadcrumbsMode.LocalOnly,
            captureProbeEvents = DebugBundleCaptureProbeEventsMode.BufferOnly,
            immediateClientErrorStatuses = emptySet(),
        )

        val BALANCED: DebugBundleCapturePolicy = DebugBundleCapturePolicy(
            preset = DebugBundleCapturePreset.Balanced,
            captureLogs = DebugBundleCaptureLogsMode.Warning,
            captureRequestEvents = DebugBundleCaptureRequestEventsMode.FailuresOnly,
            captureBreadcrumbs = DebugBundleCaptureBreadcrumbsMode.ExceptionOnly,
            captureProbeEvents = DebugBundleCaptureProbeEventsMode.BufferOnly,
            immediateClientErrorStatuses = emptySet(),
        )

        val INVESTIGATIVE: DebugBundleCapturePolicy = DebugBundleCapturePolicy(
            preset = DebugBundleCapturePreset.Investigative,
            captureLogs = DebugBundleCaptureLogsMode.Info,
            captureRequestEvents = DebugBundleCaptureRequestEventsMode.All,
            captureBreadcrumbs = DebugBundleCaptureBreadcrumbsMode.Standalone,
            captureProbeEvents = DebugBundleCaptureProbeEventsMode.StandaloneWhenActivated,
            immediateClientErrorStatuses = RECOMMENDED_IMMEDIATE_CLIENT_ERROR_STATUSES.toSet(),
        )

        fun defaultWhenConfigFetchFails(): DebugBundleCapturePolicy = MINIMAL

        fun defaultWhenResponseOmitsPolicy(): DebugBundleCapturePolicy = BALANCED

        fun fromRemotePolicy(policy: DebugBundleRemoteCapturePolicy?): DebugBundleCapturePolicy {
            if (policy == null) {
                return defaultWhenResponseOmitsPolicy()
            }
            val preset = DebugBundleCapturePreset.fromWireValue(policy.preset)
                ?: return defaultWhenResponseOmitsPolicy()
            val defaults = defaultsForPreset(preset)
            return DebugBundleCapturePolicy(
                preset = preset,
                captureLogs = DebugBundleCaptureLogsMode.fromWireValue(policy.captureLogs) ?: defaults.captureLogs,
                captureRequestEvents = DebugBundleCaptureRequestEventsMode.fromWireValue(policy.captureRequestEvents)
                    ?: defaults.captureRequestEvents,
                captureBreadcrumbs = DebugBundleCaptureBreadcrumbsMode.fromWireValue(policy.captureBreadcrumbs)
                    ?: defaults.captureBreadcrumbs,
                captureProbeEvents = DebugBundleCaptureProbeEventsMode.fromWireValue(policy.captureProbeEvents)
                    ?: defaults.captureProbeEvents,
                immediateClientErrorStatuses = policy.immediateClientErrorStatuses
                    .filter { it in 400..499 }
                    .toSortedSet(),
                immediateClientErrorPathRules = policy.immediateClientErrorPathRules
                    .filter {
                        it.statusCode in 400..499 &&
                            isValidPathPattern(it.pathPattern) &&
                            it.methods.size <= 7 &&
                            it.methods.all { method -> method.uppercase() in VALID_METHODS }
                    }
                    .map {
                        DebugBundleImmediateClientErrorPathRule(
                            statusCode = it.statusCode,
                            pathPattern = it.pathPattern,
                            methods = it.methods.map { method -> method.uppercase() }
                                .distinct()
                                .sorted(),
                        )
                    },
            )
        }

        private fun defaultsForPreset(preset: DebugBundleCapturePreset): DebugBundleCapturePolicy {
            return when (preset) {
                DebugBundleCapturePreset.Minimal -> MINIMAL
                DebugBundleCapturePreset.Balanced -> BALANCED
                DebugBundleCapturePreset.Investigative -> INVESTIGATIVE
            }
        }

        private fun isValidPathPattern(value: String): Boolean {
            if (value.isEmpty() || value.length > 256 || !value.startsWith("/") || value.contains("?") || value.contains("#")) {
                return false
            }
            val wildcardIndex = value.indexOf("*")
            return wildcardIndex == -1 || wildcardIndex == value.lastIndex
        }

        private fun normalizeRequestPath(value: String): String {
            return try {
                val path = URI(value).path
                if (!path.isNullOrEmpty()) path else "/"
            } catch (_: IllegalArgumentException) {
                val path = value.substringBefore("?").substringBefore("#")
                if (path.startsWith("/") && path.isNotEmpty()) path else "/"
            }
        }
    }
}

data class DebugBundleRequestAnomalyThreshold(
    val minimumOccurrences5m: Int,
    val minimumRatio5mTo1h: Double,
)

@Serializable
data class DebugBundleImmediateClientErrorPathRule(
    @SerialName("status_code")
    val statusCode: Int,
    @SerialName("path_pattern")
    val pathPattern: String,
    val methods: List<String> = emptyList(),
)

@Serializable
data class DebugBundleRemoteCapturePolicy(
    val preset: String? = null,
    @SerialName("capture_logs")
    val captureLogs: String? = null,
    @SerialName("capture_request_events")
    val captureRequestEvents: String? = null,
    @SerialName("capture_breadcrumbs")
    val captureBreadcrumbs: String? = null,
    @SerialName("capture_probe_events")
    val captureProbeEvents: String? = null,
    @SerialName("immediate_client_error_statuses")
    val immediateClientErrorStatuses: List<Int> = emptyList(),
    @SerialName("immediate_client_error_path_rules")
    val immediateClientErrorPathRules: List<DebugBundleImmediateClientErrorPathRule> = emptyList(),
)
