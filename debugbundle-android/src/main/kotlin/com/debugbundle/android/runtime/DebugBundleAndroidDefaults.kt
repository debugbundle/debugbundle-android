package com.debugbundle.android.runtime

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Build
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleQueueLimits
import kotlin.io.path.absolute
import kotlin.io.path.pathString
import java.io.File

internal object DebugBundleAndroidDefaults {
    private const val STORAGE_DIRECTORY = "debugbundle"
    private const val QUEUE_FILE = "events.json"
    private const val FATAL_CRASH_FILE = "fatal-crash.json"

    fun resolveConfig(application: Application, config: DebugBundleConfig): DebugBundleConfig {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.packageManager.getPackageInfo(
                    application.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                application.packageManager.getPackageInfo(application.packageName, 0)
            }
        }.getOrNull()

        return config.copy(
            offlineQueuePath = config.offlineQueuePath ?: defaultQueuePath(application),
            fatalCrashPath = config.fatalCrashPath ?: defaultFatalCrashPath(application),
            appVersion = config.appVersion ?: packageInfo?.versionName,
            buildNumber = config.buildNumber ?: packageInfo?.let(::versionCodeString),
        )
    }

    fun defaultQueuePath(application: Application) =
        File(application.filesDir, "$STORAGE_DIRECTORY/$QUEUE_FILE").toPath().toAbsolutePath().normalize()

    fun defaultFatalCrashPath(application: Application) =
        File(application.filesDir, "$STORAGE_DIRECTORY/$FATAL_CRASH_FILE").toPath().toAbsolutePath().normalize()

    fun queueLimits(config: DebugBundleConfig): DebugBundleQueueLimits {
        return DebugBundleQueueLimits(
            maxEvents = config.offlineQueueMaxEvents.coerceAtLeast(1),
            maxBytes = config.offlineQueueMaxBytes.coerceAtLeast(1L),
            ttlMillis = config.offlineQueueTtl.inWholeMilliseconds.coerceAtLeast(1L),
        )
    }

    private fun versionCodeString(packageInfo: PackageInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
    }
}
