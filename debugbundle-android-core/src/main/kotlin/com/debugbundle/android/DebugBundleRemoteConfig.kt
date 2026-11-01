package com.debugbundle.android

import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class DebugBundleRemoteConfigRequest(
    val projectToken: String,
    val endpoint: String,
    val timeout: Duration,
    val eTag: String? = null,
)

sealed interface DebugBundleRemoteConfigResult {
    data class Loaded(
        val config: DebugBundleRemoteConfigResponse,
        val eTag: String? = null,
    ) : DebugBundleRemoteConfigResult

    data class NotModified(
        val eTag: String? = null,
    ) : DebugBundleRemoteConfigResult

    data object Failed : DebugBundleRemoteConfigResult
}

fun interface DebugBundleRemoteConfigClient {
    fun fetch(request: DebugBundleRemoteConfigRequest): DebugBundleRemoteConfigResult
}

@Serializable
data class DebugBundleRemoteConfigResponse(
    @SerialName("probes_enabled")
    val probesEnabled: Boolean = true,
    @SerialName("remote_probes_enabled")
    val remoteProbesEnabled: Boolean = false,
    @SerialName("active_probes")
    val activeProbes: List<DebugBundleRemoteProbeDirective> = emptyList(),
    @SerialName("poll_interval_ms")
    val pollIntervalMillis: Long = 0L,
    @SerialName("trigger_token_key")
    val triggerTokenKey: String? = null,
    @SerialName("capture_policy")
    val capturePolicy: DebugBundleRemoteCapturePolicy? = null,
) {
    @SerialName("evidence_capabilities")
    var evidenceCapabilities: JsonElement? = null
        internal set
}

class DebugBundleHttpRemoteConfigClient(
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = true },
) : DebugBundleRemoteConfigClient {
    override fun fetch(request: DebugBundleRemoteConfigRequest): DebugBundleRemoteConfigResult {
        var connection: HttpURLConnection? = null
        return try {
            val deadline = DebugBundleHttpDeadline(request.timeout)
            connection = URL(sdkConfigUrlFor(request.endpoint)).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            deadline.configure(connection)
            connection.setRequestProperty("Authorization", "Bearer ${request.projectToken}")
            request.eTag?.let { connection.setRequestProperty("If-None-Match", it) }
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("X-DebugBundle-Evidence", "1")

            when (val statusCode = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    DebugBundleRemoteConfigResult.NotModified(eTag = connection.getHeaderField("ETag"))
                }

                in 200..299 -> {
                    val body = DebugBundleHttpReply.read(connection, statusCode, deadline)
                    val response = json.decodeFromString<DebugBundleRemoteConfigResponse>(body)
                    DebugBundleRemoteConfigResult.Loaded(
                        config = response,
                        eTag = connection.getHeaderField("ETag"),
                    )
                }

                else -> {
                    statusCode.hashCode()
                    DebugBundleRemoteConfigResult.Failed
                }
            }
        } catch (_: Throwable) {
            DebugBundleRemoteConfigResult.Failed
        } finally { connection?.disconnect() }
    }

    private fun sdkConfigUrlFor(endpoint: String): String {
        val trimmed = endpoint.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/v1/events") -> trimmed.removeSuffix("/v1/events") + "/v1/sdk/config"
            trimmed.endsWith("/events") -> trimmed.removeSuffix("/events") + "/sdk/config"
            else -> "$trimmed/sdk/config"
        }
    }
}
