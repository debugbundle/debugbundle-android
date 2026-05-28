package com.debugbundle.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
        if (isImmediateRequestIncident(responseStatus)) {
            return true
        }
        return when (captureRequestEvents) {
            DebugBundleCaptureRequestEventsMode.Off -> false
            DebugBundleCaptureRequestEventsMode.FailuresOnly -> requestAnomalyThreshold(responseStatus) != null
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
        if (responseStatus == null) {
            return false
        }
        if (responseStatus >= 500) {
            return true
        }
        if (responseStatus in immediateClientErrorStatuses) {
            return true
        }
        return when (preset) {
            DebugBundleCapturePreset.Minimal -> false
            DebugBundleCapturePreset.Balanced -> responseStatus in BALANCED_IMMEDIATE_REQUEST_STATUSES
            DebugBundleCapturePreset.Investigative -> responseStatus in INVESTIGATIVE_IMMEDIATE_REQUEST_STATUSES
        }
    }

    private fun requestAnomalyThreshold(responseStatus: Int?): DebugBundleRequestAnomalyThreshold? {
        if (responseStatus == null || responseStatus < 400 || responseStatus >= 500) {
            return null
        }
        return when (preset) {
            DebugBundleCapturePreset.Minimal -> null
            DebugBundleCapturePreset.Investigative -> {
                if (responseStatus in INVESTIGATIVE_ANOMALY_STATUSES) {
                    DebugBundleRequestAnomalyThreshold(
                        minimumOccurrences5m = 8,
                        minimumRatio5mTo1h = 2.0,
                    )
                } else {
                    null
                }
            }

            DebugBundleCapturePreset.Balanced -> {
                when {
                    responseStatus in BALANCED_STANDARD_ANOMALY_STATUSES -> {
                        DebugBundleRequestAnomalyThreshold(
                            minimumOccurrences5m = 20,
                            minimumRatio5mTo1h = 3.0,
                        )
                    }

                    responseStatus in BALANCED_HIGH_VOLUME_ANOMALY_STATUSES -> {
                        DebugBundleRequestAnomalyThreshold(
                            minimumOccurrences5m = 50,
                            minimumRatio5mTo1h = 5.0,
                        )
                    }

                    else -> null
                }
            }
        }
    }

    companion object {
        private val RECOMMENDED_IMMEDIATE_CLIENT_ERROR_STATUSES = listOf(401, 403, 409, 422)
        private val BALANCED_IMMEDIATE_REQUEST_STATUSES = setOf(408, 423, 424, 425, 429)
        private val INVESTIGATIVE_IMMEDIATE_REQUEST_STATUSES = BALANCED_IMMEDIATE_REQUEST_STATUSES + 409
        private val BALANCED_STANDARD_ANOMALY_STATUSES = setOf(401, 403, 404, 409, 422)
        private val BALANCED_HIGH_VOLUME_ANOMALY_STATUSES = setOf(400, 410)
        private val INVESTIGATIVE_ANOMALY_STATUSES =
            BALANCED_STANDARD_ANOMALY_STATUSES + BALANCED_HIGH_VOLUME_ANOMALY_STATUSES

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
            )
        }

        private fun defaultsForPreset(preset: DebugBundleCapturePreset): DebugBundleCapturePolicy {
            return when (preset) {
                DebugBundleCapturePreset.Minimal -> MINIMAL
                DebugBundleCapturePreset.Balanced -> BALANCED
                DebugBundleCapturePreset.Investigative -> INVESTIGATIVE
            }
        }
    }
}

data class DebugBundleRequestAnomalyThreshold(
    val minimumOccurrences5m: Int,
    val minimumRatio5mTo1h: Double,
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
)
