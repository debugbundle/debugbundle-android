@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.debugbundle.android

import com.debugbundle.android.internal.DebugBundleNativeValues
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A pin asserts support for the complete delivery and reader path. */
@Serializable
data class DebugBundleEvidenceCapabilities @JvmOverloads constructor(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = 1,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val features: List<String> = emptyList(),
) {
    companion object {
        internal val KNOWN = listOf("exception_evidence_v1", "resource_observations_v1", "recovery_v1", "evidence_provenance_v1")
        @JvmStatic
        fun normalize(value: DebugBundleEvidenceCapabilities?): DebugBundleEvidenceCapabilities = try {
            if (value == null || value.version != 1 || !DebugBundleNativeValues.collection(value.features) || value.features.size > 4 || value.features.distinct().size != value.features.size || value.features.any { it !in KNOWN }) DebugBundleEvidenceCapabilities()
            else DebugBundleEvidenceCapabilities(features = KNOWN.filter(value.features::contains))
        } catch (_: Throwable) { DebugBundleEvidenceCapabilities() }
        @JvmStatic
        fun normalize(value: JsonElement?): DebugBundleEvidenceCapabilities {
            if (value !is JsonObject || value.keys != setOf("version", "features")) return DebugBundleEvidenceCapabilities()
            val version = value["version"] as? JsonPrimitive ?: return DebugBundleEvidenceCapabilities()
            val features = value["features"] as? JsonArray ?: return DebugBundleEvidenceCapabilities()
            if (version.isString || version.doubleOrNull != 1.0 || features.size > 4) return DebugBundleEvidenceCapabilities()
            val strings = ArrayList<String>()
            for (item in features) { if (item !is JsonPrimitive || !item.isString) return DebugBundleEvidenceCapabilities(); strings.add(item.content) }
            return normalize(DebugBundleEvidenceCapabilities(features = strings))
        }
        @JvmStatic
        fun intersect(hops: List<DebugBundleEvidenceCapabilities>): DebugBundleEvidenceCapabilities {
            if (!DebugBundleNativeValues.collection(hops) || hops.isEmpty() || hops.size > 8) return DebugBundleEvidenceCapabilities()
            val remaining = KNOWN.toMutableSet()
            for (hop in hops) remaining.retainAll(normalize(hop).features.toSet())
            return DebugBundleEvidenceCapabilities(features = KNOWN.filter(remaining::contains))
        }
    }
}

/** Configure with the builder so existing Kotlin constructor/default/copy descriptors remain valid. */
data class DebugBundleEvidenceOptions @JvmOverloads constructor(
    val captureExceptionEvidence: Boolean = true,
    val captureRecoveryObservations: Boolean = false,
    val evidenceCapabilities: DebugBundleEvidenceCapabilities? = null,
) {
    internal fun snapshot(): DebugBundleEvidenceOptions = copy(evidenceCapabilities = evidenceCapabilities?.let(DebugBundleEvidenceCapabilities::normalize))
}

internal class DebugBundleEvidenceDeliveryState(pin: DebugBundleEvidenceCapabilities?) {
    private val proof = AtomicReference(DebugBundleEvidenceCapabilities.normalize(pin))
    fun supports(feature: String): Boolean = feature in proof.get().features
    fun descriptor(): DebugBundleEvidenceCapabilities = DebugBundleEvidenceCapabilities.normalize(proof.get())
    fun observe(value: JsonElement?): Boolean {
        val next = DebugBundleEvidenceCapabilities.normalize(value)
        return proof.getAndSet(next).features.any { it !in next.features }
    }
}
