package com.debugbundle.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DebugBundleDeviceContext(
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("build_number")
    val buildNumber: String? = null,
    @SerialName("release_channel")
    val releaseChannel: String? = null,
    @SerialName("os_name")
    val osName: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("api_level")
    val apiLevel: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    @SerialName("device_type")
    val deviceType: String? = null,
    @SerialName("screen_width")
    val screenWidth: Int? = null,
    @SerialName("screen_height")
    val screenHeight: Int? = null,
    val locale: String? = null,
    val timezone: String? = null,
    @SerialName("connection_type")
    val connectionType: String? = null,
    @SerialName("battery_level")
    val batteryLevel: Double? = null,
    @SerialName("battery_charging")
    val batteryCharging: Boolean? = null,
    @SerialName("free_disk_bytes")
    val freeDiskBytes: Long? = null,
    @SerialName("free_memory_bytes")
    val freeMemoryBytes: Long? = null,
    @SerialName("rooted")
    val rooted: Boolean? = null,
)
