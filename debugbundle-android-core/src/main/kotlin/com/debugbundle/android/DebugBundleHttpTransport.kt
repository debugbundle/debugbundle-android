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
            val response = readResponse(connection, responseCode)
            DebugBundleTransportResult(
                statusCode = responseCode,
                retryAfter = retryAfter,
                probeDirectives = response?.probeDirectives?.activeProbes,
                acknowledgement = response?.toAcknowledgement(),
                acknowledgementRequired = responseCode in 200..299,
            )
        } catch (_: Throwable) {
            DebugBundleTransportResult(statusCode = 500)
        }
    }

    private fun readResponse(
        connection: HttpURLConnection,
        responseCode: Int,
    ): DebugBundleIngestionResponse? {
        return runCatching {
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (body.isBlank()) {
                null
            } else {
                json.decodeFromString<DebugBundleIngestionResponse>(body)
            }
        }.getOrNull()
    }
}

@Serializable
private data class DebugBundleIngestionResponse(
    val accepted: Int? = null,
    val rejected: Int? = null,
    val errors: List<DebugBundleIngestionError>? = null,
    @SerialName("probe_directives")
    val probeDirectives: DebugBundleIngestionProbeDirectives? = null,
) {
    fun toAcknowledgement(): DebugBundleIngestionAcknowledgement? {
        return DebugBundleIngestionAcknowledgement(
            accepted = accepted ?: return null,
            rejected = rejected ?: return null,
            errors = errors ?: return null,
        )
    }
}

@Serializable
private data class DebugBundleIngestionProbeDirectives(
    @SerialName("active_probes")
    val activeProbes: List<DebugBundleRemoteProbeDirective> = emptyList(),
)
