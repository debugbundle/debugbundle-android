package com.debugbundle.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class DebugBundleBreadcrumb(
    @SerialName("ts")
    val occurredAt: String,
    @SerialName("breadcrumb_type")
    val breadcrumbType: String,
    val route: String? = null,
    val data: JsonObject,
)

internal fun DebugBundleBreadcrumb.toPayload(): JsonObject {
    return buildJsonObject {
        put("breadcrumb_type", JsonPrimitive(breadcrumbType))
        route?.let { put("route", JsonPrimitive(it)) }
        put("data", data)
    }
}

object DebugBundleBreadcrumbTypes {
    const val SCREEN = "screen_transition"
    const val APP_LIFECYCLE = "app_lifecycle"
    const val USER_ACTION = "user_action"
    const val NETWORK = "network_request"
}
