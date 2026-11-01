@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.debugbundle.android

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON is the SDK's existing wire/store format. Unknown runtime is never fabricated. */
internal object DebugBundleServiceDescriptorSerializer : KSerializer<DebugBundleServiceDescriptor> {
    override val descriptor = buildClassSerialDescriptor("com.debugbundle.android.DebugBundleServiceDescriptor") {
        element<String>("name")
        element<String>("environment")
        element<String?>("runtime", isOptional = true)
        element<String?>("framework", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): DebugBundleServiceDescriptor {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Service metadata requires JSON")
        val fields = json.decodeJsonElement() as? JsonObject ?: throw SerializationException("Service metadata requires an object")
        if (!json.json.configuration.ignoreUnknownKeys && fields.keys.any { it !in setOf("name", "environment", "runtime", "framework") })
            throw SerializationException("Unknown service metadata field")
        fun text(key: String, required: Boolean = false): String? {
            val value = fields[key]
            if ((value == null || value == JsonNull) && !required) return null
            if (value !is JsonPrimitive || !value.isString) throw SerializationException("Invalid service metadata field")
            return value.content
        }
        val runtime = text("runtime")
        return DebugBundleServiceDescriptor(text("name", true)!!, text("environment", true)!!, runtime ?: "kotlin", text("framework"))
            .also { it.runtimeIsAbsent = runtime == null }
    }

    override fun serialize(encoder: Encoder, value: DebugBundleServiceDescriptor) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Service metadata requires JSON")
        json.encodeJsonElement(buildJsonObject {
            put("name", value.name)
            put("environment", value.environment)
            if (!value.runtimeIsAbsent) put("runtime", value.runtime)
            if (value.framework != null) put("framework", value.framework)
            else if (json.json.configuration.explicitNulls) put("framework", JsonNull)
        })
    }
}
