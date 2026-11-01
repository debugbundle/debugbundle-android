package com.debugbundle.android.runtime

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import com.debugbundle.android.DebugBundleClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

internal class DebugBundleApplicationExitInfoReporter(
    private val application: Application,
    private val configStore: DebugBundleAndroidConfigStore,
    private val exitInfoSource: DebugBundleExitInfoSource = AndroidDebugBundleExitInfoSource(application),
) {
    private val pendingTimestamp = AtomicLong()

    fun capturePendingExitInfo(client: DebugBundleClient): Boolean {
        val lastReportedTimestamp = configStore.lastReportedExitTimestamp()
        val pending = exitInfoSource.load()
            .filter { it.reason == ApplicationExitInfo.REASON_ANR }
            .maxByOrNull { it.timestampMillis }
            ?.takeIf { it.timestampMillis > lastReportedTimestamp }
            ?: return false

        val timestamp = pending.timestampMillis
        val previous = pendingTimestamp.get()
        if (previous >= timestamp || !pendingTimestamp.compareAndSet(previous, timestamp)) return false
        val captured = client.captureRuntimeException(
            ApplicationNotRespondingException(pending.description ?: "Application did not respond"),
            reportedAt = Instant.ofEpochMilli(timestamp).toString(),
            onPersisted = { configStore.markReportedExitTimestamp(timestamp); pendingTimestamp.compareAndSet(timestamp, 0) },
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
                pending.traceExcerpt?.let { put("anr_trace_excerpt", JsonPrimitive(it.take(4096))) }
            },
        )
        if (!captured) pendingTimestamp.compareAndSet(timestamp, previous)
        return captured
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
                        val bytes = ByteArray(4096); var used = 0
                        while (used < bytes.size) {
                            val count = stream.read(bytes, used, bytes.size - used)
                            if (count <= 0) break
                            used += count
                        }
                        String(bytes, 0, used, Charsets.UTF_8)
                    },
                )
            }
        }.getOrDefault(emptyList())
    }
}

internal class ApplicationNotRespondingException(
    message: String,
) : RuntimeException(message)
