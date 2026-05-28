package com.debugbundle.android

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DebugBundleRemoteProbeDirective(
    @SerialName("activation_id")
    val activationId: String = "",
    val id: String? = null,
    @SerialName("label_pattern")
    val labelPattern: String,
    val service: String,
    val environment: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("trigger_expires_at")
    val triggerExpiresAt: String? = null,
) {
    val effectiveActivationId: String
        get() = activationId.ifBlank { id.orEmpty() }
}

@Serializable
internal data class DebugBundleProbeTriggerPayload(
    @SerialName("activation_id")
    val activationId: String,
    @SerialName("label_pattern")
    val labelPattern: String,
    val service: String,
    val environment: String,
    @SerialName("trigger_expires_at")
    val triggerExpiresAt: String,
)

internal object DebugBundleProbeTriggerTokenValidator {
    private const val PREFIX = "dbundle_probe_"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(token: String, triggerTokenKey: String?, now: Instant): DebugBundleRemoteProbeDirective? {
        if (triggerTokenKey.isNullOrBlank() || !token.startsWith(PREFIX)) {
            return null
        }
        val encoded = token.removePrefix(PREFIX)
        val separatorIndex = encoded.indexOf('.')
        if (separatorIndex <= 0 || separatorIndex == encoded.lastIndex) {
            return null
        }

        val payloadSegment = encoded.substring(0, separatorIndex)
        val signatureSegment = encoded.substring(separatorIndex + 1)
        if (!hasValidSignature(payloadSegment, signatureSegment, triggerTokenKey)) {
            return null
        }

        val payload = decodePayload(payloadSegment) ?: return null
        val expiresAt = runCatching { Instant.parse(payload.triggerExpiresAt) }.getOrNull() ?: return null
        if (!expiresAt.isAfter(now)) {
            return null
        }

        return DebugBundleRemoteProbeDirective(
            activationId = payload.activationId,
            labelPattern = payload.labelPattern,
            service = payload.service,
            environment = payload.environment,
            expiresAt = payload.triggerExpiresAt,
            triggerExpiresAt = payload.triggerExpiresAt,
        )
    }

    private fun decodePayload(payloadSegment: String): DebugBundleProbeTriggerPayload? {
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(payloadSegment).toString(Charsets.UTF_8)
            json.decodeFromString<DebugBundleProbeTriggerPayload>(decoded)
        }.getOrNull()
    }

    private fun hasValidSignature(payloadSegment: String, signatureSegment: String, triggerTokenKey: String): Boolean {
        return runCatching {
            val expected = hmac(payloadSegment, triggerTokenKey)
            val actual = Base64.getUrlDecoder().decode(signatureSegment)
            MessageDigest.isEqual(expected, actual)
        }.getOrDefault(false)
    }

    private fun hmac(payloadSegment: String, triggerTokenKey: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(triggerTokenKey.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return mac.doFinal(payloadSegment.toByteArray(Charsets.UTF_8))
    }
}
