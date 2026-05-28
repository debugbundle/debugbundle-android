package com.debugbundle.android.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleDeviceContext
import com.debugbundle.android.DebugBundleDeviceContextProvider
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class AndroidDebugBundleDeviceContextProvider(
    private val application: Application,
    private val config: DebugBundleConfig,
) : DebugBundleDeviceContextProvider {
    override fun snapshot(): DebugBundleDeviceContext {
        val resources = application.resources
        val displayMetrics = resources.displayMetrics
        val batteryState = batteryState()
        val memoryInfo = ActivityManager.MemoryInfo().also { memoryManager()?.getMemoryInfo(it) }

        return DebugBundleDeviceContext(
            appVersion = config.appVersion,
            buildNumber = config.buildNumber,
            releaseChannel = config.releaseChannel,
            osName = "Android",
            osVersion = Build.VERSION.RELEASE_OR_CODENAME,
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            deviceType = deviceType(resources.configuration.smallestScreenWidthDp),
            screenWidth = displayMetrics.widthPixels.takeIf { it > 0 },
            screenHeight = displayMetrics.heightPixels.takeIf { it > 0 },
            locale = currentLocale(resources.configuration),
            timezone = TimeZone.getDefault().id,
            connectionType = connectionType(),
            batteryLevel = batteryState?.level,
            batteryCharging = batteryState?.charging,
            freeDiskBytes = application.filesDir?.usableSpace?.takeIf { it >= 0L },
            freeMemoryBytes = memoryInfo.availMem.takeIf { it >= 0L },
            rooted = rooted(),
        )
    }

    private fun currentLocale(configuration: android.content.res.Configuration): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.get(0)?.toLanguageTag()
        } else {
            @Suppress("DEPRECATION")
            configuration.locale?.toLanguageTag()
        } ?: Locale.getDefault().toLanguageTag()
    }

    private fun memoryManager(): ActivityManager? {
        return application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    private fun connectionType(): String? {
        val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching {
            val network = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            when {
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        }.getOrNull()
    }

    private fun batteryState(): BatteryState? {
        val intent = application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percentage = if (level >= 0 && scale > 0) (level.toDouble() / scale.toDouble()) * 100.0 else null
        return BatteryState(level = percentage?.let { (it * 10.0).roundToInt() / 10.0 }, charging = charging)
    }

    private fun rooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) {
            return true
        }
        return ROOT_PATHS.any { File(it).exists() }
    }

    private fun deviceType(smallestScreenWidthDp: Int): String {
        return if (smallestScreenWidthDp >= 600) "tablet" else "mobile"
    }

    private data class BatteryState(
        val level: Double?,
        val charging: Boolean,
    )

    private companion object {
        private val ROOT_PATHS = listOf(
            "/system/app/Superuser.apk",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )
    }
}
