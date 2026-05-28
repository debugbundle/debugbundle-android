package com.debugbundle.android.runtime

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import com.debugbundle.android.DebugBundleClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant

internal class DebugBundleApplicationExitInfoReporter(
    private val application: Application,
    private val configStore: DebugBundleAndroidConfigStore,
    private val exitInfoSource: DebugBundleExitInfoSource = AndroidDebugBundleExitInfoSource(application),
) {
    fun capturePendingExitInfo(client: DebugBundleClient): Boolean {
        val lastReportedTimestamp = configStore.lastReportedExitTimestamp()
        val pending = exitInfoSource.load()
            .filter { it.reason == ApplicationExitInfo.REASON_ANR }
            .maxByOrNull { it.timestampMillis }
            ?.takeIf { it.timestampMillis > lastReportedTimestamp }
            ?: return false

        client.captureException(
            ApplicationNotRespondingException(pending.description ?: "Application did not respond"),
            context = buildMap {
                put("fatal", true)
                put("handled", false)
                put("mechanism", "application_exit_info")
                put("crash_delivery", "next_launch")
                put("exit_reason", "anr")
                put("timestamp", Instant.ofEpochMilli(pending.timestampMillis).toString())
                put("importance", pending.importance)
                put("process_state_summary", pending.processStateSummary)
                put("description", pending.description)
                put("pss_kb", pending.pssKb)
                put("rss_kb", pending.rssKb)
                pending.traceExcerpt?.let { put("anr_trace_excerpt", it) }
            },
        )
        configStore.markReportedExitTimestamp(pending.timestampMillis)
        return true
    }
}

internal fun interface DebugBundleExitInfoSource {
    fun load(): List<DebugBundleExitInfo>
}

internal data class DebugBundleExitInfo(
    val reason: Int,
    val timestampMillis: Long,
    val importance: Int,
    val processStateSummary: String?,
    val description: String?,
    val pssKb: Long?,
    val rssKb: Long?,
    val traceExcerpt: String?,
)

internal class AndroidDebugBundleExitInfoSource(
    private val application: Application,
) : DebugBundleExitInfoSource {
    override fun load(): List<DebugBundleExitInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }
        val activityManager = application.getSystemService(Application.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return runCatching {
            activityManager.getHistoricalProcessExitReasons(application.packageName, 0, 10).map { exitInfo ->
                DebugBundleExitInfo(
                    reason = exitInfo.reason,
                    timestampMillis = exitInfo.timestamp,
                    importance = exitInfo.importance,
                    processStateSummary = exitInfo.processStateSummary?.contentToString(),
                    description = exitInfo.description,
                    pssKb = exitInfo.pss,
                    rssKb = exitInfo.rss,
                    traceExcerpt = exitInfo.traceInputStream?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            reader.lineSequence().take(40).joinToString("\n").take(4_000)
                        }
                    },
                )
            }
        }.getOrDefault(emptyList())
    }
}

internal class ApplicationNotRespondingException(
    message: String,
) : RuntimeException(message)
