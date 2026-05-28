package com.debugbundle.android

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DebugBundleHttpTransport(
    private val json: Json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true },
) : DebugBundleTransport {
    override fun send(request: DebugBundleTransportRequest): DebugBundleTransportResult {
        return try {
            val connection = URL(request.endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = request.timeout.inWholeMilliseconds.toInt()
            connection.readTimeout = request.timeout.inWholeMilliseconds.toInt()
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${request.projectToken}")
            connection.setRequestProperty("Content-Type", "application/json")
            val body = json.encodeToString(mapOf("events" to request.events))
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            val responseCode = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()?.seconds?.coerceAtMost(5.minutes)
                ?: Duration.ZERO
            DebugBundleTransportResult(
                statusCode = responseCode,
                retryAfter = retryAfter,
                probeDirectives = if (responseCode in 200..299) readProbeDirectives(connection) else null,
            )
        } catch (_: Throwable) {
            DebugBundleTransportResult(statusCode = 500)
        }
    }

    private fun readProbeDirectives(connection: HttpURLConnection): List<DebugBundleRemoteProbeDirective>? {
        return runCatching {
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.isBlank()) {
                null
            } else {
                json.decodeFromString<DebugBundleIngestionResponse>(body).probeDirectives?.activeProbes
            }
        }.getOrNull()
    }
}

@Serializable
private data class DebugBundleIngestionResponse(
    @SerialName("probe_directives")
    val probeDirectives: DebugBundleIngestionProbeDirectives? = null,
)

@Serializable
private data class DebugBundleIngestionProbeDirectives(
    @SerialName("active_probes")
    val activeProbes: List<DebugBundleRemoteProbeDirective> = emptyList(),
)
