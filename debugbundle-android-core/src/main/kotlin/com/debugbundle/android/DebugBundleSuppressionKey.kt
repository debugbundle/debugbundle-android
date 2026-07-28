package com.debugbundle.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun buildDebugBundleSuppressionKey(event: DebugBundleEnvelope): String? {
    return when (event.eventType) {
        DebugBundleEventTypes.FRONTEND_EXCEPTION -> {
            val error = event.payload["error"] as? JsonObject
            val stackTrace = error?.get("stack_trace") as? JsonArray
            val firstFrame = stackTrace?.firstOrNull()?.toString()
                ?: event.payload["stack"]?.toString()?.lineSequence()?.firstOrNull()
            listOf(
                event.eventType,
                error?.get("type")?.toString() ?: event.payload["name"]?.toString(),
                error?.get("message")?.toString() ?: event.payload["message"]?.toString(),
                firstFrame,
                event.payload["route"]?.toString(),
            ).joinToString("|")
        }

        DebugBundleEventTypes.LOG_EVENT -> {
            listOf(
                event.eventType,
                event.payload["level"]?.toString(),
                event.payload["message"]?.toString(),
                event.payload["context"]?.toString() ?: event.payload["attributes"]?.toString(),
            ).joinToString("|")
        }

        DebugBundleEventTypes.REQUEST_EVENT -> {
            listOf(
                event.eventType,
                event.payload["method"]?.toString(),
                event.payload["url"]?.toString() ?: event.payload["path"]?.toString(),
                event.payload["response_status"]?.toString(),
            ).joinToString("|")
        }

        else -> null
    }
}
