package com.debugbundle.android

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Receiving JSON.stringify bytes, independently of the native transport serializer. */
internal object DebugBundleAcceptingJsonSize {
    private class Budget(var remaining: Int = 32768, var nodes: Int = 4096) {
        fun use(count: Int) { remaining -= count; if (remaining < 0) throw EvidenceValidationUnavailable() }
    }
    fun bytes(value: JsonElement): Int = try { val budget = Budget(); count(value, budget, 0); 32768 - budget.remaining } catch (_: RuntimeException) { 32769 }
    private fun count(value: JsonElement, budget: Budget, depth: Int) {
        if (--budget.nodes < 0 || depth > 16) throw EvidenceValidationUnavailable()
        when (value) {
            JsonNull -> budget.use(4)
            is JsonObject -> {
                if (value.size > 256) throw EvidenceValidationUnavailable()
                budget.use(2 + (value.size - 1).coerceAtLeast(0))
                for ((key, child) in value) { string(key, budget); budget.use(1); count(child, budget, depth + 1) }
            }
            is JsonArray -> {
                if (value.size > 256) throw EvidenceValidationUnavailable()
                budget.use(2 + (value.size - 1).coerceAtLeast(0))
                for (child in value) count(child, budget, depth + 1)
            }
            is JsonPrimitive -> if (value.isString) string(value.content, budget) else when (value.content) {
                "true" -> budget.use(4)
                "false" -> budget.use(5)
                else -> budget.use(number(value.content.toDouble()).length)
            }
        }
    }
    private fun string(value: String, budget: Budget) {
        if (value.length > budget.remaining) throw EvidenceValidationUnavailable()
        budget.use(2); var index = 0
        while (index < value.length) {
            val letter = value[index++]
            when {
                letter in charArrayOf('"', '\\', '\b', '\u000C', '\n', '\r', '\t') -> budget.use(2)
                letter.code < 32 -> budget.use(6)
                Character.isHighSurrogate(letter) && index < value.length && Character.isLowSurrogate(value[index]) -> { budget.use(4); index++ }
                Character.isSurrogate(letter) -> budget.use(6)
                else -> budget.use(if (letter.code < 128) 1 else if (letter.code < 2048) 2 else 3)
            }
        }
    }
    internal fun utf8Within(value: String, maximum: Int): Boolean {
        if (value.length > maximum) return false
        var bytes = 0; var index = 0
        while (index < value.length) {
            val letter = value[index++]
            bytes += if (Character.isHighSurrogate(letter) && index < value.length && Character.isLowSurrogate(value[index])) { index++; 4 } else if (letter.code < 128) 1 else if (letter.code < 2048) 2 else 3
            if (bytes > maximum) return false
        }
        return true
    }
    private fun number(value: Double): String {
        require(value.isFinite())
        if (value == 0.0) return "0"
        val absolute = kotlin.math.abs(value); val exact = BigDecimal(absolute); var shortest = exact
        for (precision in 1..17) {
            val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (java.lang.Double.doubleToLongBits(candidate.toDouble()) == java.lang.Double.doubleToLongBits(absolute)) { shortest = candidate.stripTrailingZeros(); break }
        }
        val digits = shortest.unscaledValue().toString(); val exponent = digits.length - shortest.scale() - 1
        val magnitude = if (exponent in -6..20) shortest.toPlainString() else digits.take(1) + (if (digits.length > 1) "." + digits.drop(1) else "") + "e" + (if (exponent >= 0) "+" else "") + exponent
        return (if (value < 0) "-" else "") + magnitude
    }
}

internal class EvidenceValidationUnavailable : RuntimeException()
