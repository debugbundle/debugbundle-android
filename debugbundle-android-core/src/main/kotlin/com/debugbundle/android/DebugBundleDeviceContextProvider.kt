package com.debugbundle.android

import java.nio.file.Path
import java.util.Locale
import java.util.TimeZone

fun interface DebugBundleDeviceContextProvider {
    fun snapshot(): DebugBundleDeviceContext
}

class JvmDebugBundleDeviceContextProvider(
    private val config: DebugBundleConfig,
    private val storagePathHint: Path? = config.offlineQueuePath,
) : DebugBundleDeviceContextProvider {
    override fun snapshot(): DebugBundleDeviceContext {
        val runtime = Runtime.getRuntime()
        val freeDiskBytes = storagePathHint
            ?.toAbsolutePath()
            ?.normalize()
            ?.parent
            ?.toFile()
            ?.usableSpace
            ?.takeIf { it >= 0L }

        return DebugBundleDeviceContext(
            appVersion = config.appVersion,
            buildNumber = config.buildNumber,
            releaseChannel = config.releaseChannel,
            osName = System.getProperty("os.name"),
            osVersion = System.getProperty("os.version"),
            apiLevel = null,
            manufacturer = null,
            model = null,
            deviceType = "unknown",
            screenWidth = null,
            screenHeight = null,
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            connectionType = null,
            batteryLevel = null,
            batteryCharging = null,
            freeDiskBytes = freeDiskBytes,
            freeMemoryBytes = runtime.freeMemory(),
            rooted = null,
        )
    }
}
