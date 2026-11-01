package com.debugbundle.android.internal

/** Exact native containers only: subclasses/delegates can execute application accessors. */
internal object DebugBundleNativeValues {
    private val maps = setOf("java.util.HashMap", "java.util.LinkedHashMap", "kotlin.collections.EmptyMap", "kotlin.collections.builders.MapBuilder", "java.util.Collections\$EmptyMap", "java.util.Collections\$SingletonMap", "java.util.ImmutableCollections\$Map1", "java.util.ImmutableCollections\$MapN")
    private val collections = setOf("java.util.ArrayList", "java.util.LinkedList", "java.util.Arrays\$ArrayList", "kotlin.collections.EmptyList", "kotlin.collections.EmptySet", "kotlin.collections.builders.ListBuilder", "kotlin.collections.builders.SetBuilder", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.Collections\$EmptyList", "java.util.Collections\$SingletonList", "java.util.Collections\$CopiesList", "java.util.Collections\$EmptySet", "java.util.Collections\$SingletonSet", "java.util.ImmutableCollections\$List12", "java.util.ImmutableCollections\$ListN", "java.util.ImmutableCollections\$Set12", "java.util.ImmutableCollections\$SetN")
    private val numbers = setOf("java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.math.BigInteger", "java.math.BigDecimal")
    fun map(value: Map<*, *>): Boolean = value.javaClass.name in maps
    fun collection(value: Iterable<*>): Boolean = value.javaClass.name in collections
    fun number(value: Number): Boolean = value.javaClass.name in numbers &&
        (value !is java.math.BigInteger || value.bitLength() <= 4096) &&
        (value !is java.math.BigDecimal || value.unscaledValue().bitLength() <= 4096) && value.toDouble().isFinite()
    fun scalarText(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.length <= 2048 }
        is Boolean -> value.toString()
        is Number -> if (number(value)) value.toString().takeIf { it.length <= 2048 } else null
        else -> null
    }
    fun contextText(value: Map<String, Any?>, key: String): String? = if (map(value)) value[key] as? String else null
    fun context(value: Map<String, Any?>): Map<String, Any?> {
        if (!map(value)) return emptyMap()
        if (value.size > 50) return mapOf("_redacted" to "[REDACTED]")
        val result = linkedMapOf<String, Any?>()
        for ((key, item) in value as Map<*, *>) if (key is String && key.length <= 128) result[key] = item
        return result
    }
}
