package com.debugbundle.android.internal

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded mandatory policy for application-owned JSON. Protocol envelope fields remain typed. */
internal class TelemetryPrivacy(additionalFields: Set<String>) {
    private val additional = additionalFields.toList()
    private val keys = (BASE_FIELDS + additionalFields).map(::canonical).toSet()
    private val assignment by lazy {
        val fields = (BASE_FIELDS + additional).joinToString("|") { Regex.escape(it) }
        Regex("\\b($fields)\\b([\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s&,;]+)", RegexOption.IGNORE_CASE)
    }
    private val json = Json { explicitNulls = true }

    init {
        require(additional.size <= 128 && additional.all { it.isNotBlank() && it.length <= 64 }) { "unsafe_input" }
    }

    fun protect(value: JsonElement): JsonElement {
        val work = Work()
        val output = visit(value, work, 0, true)
        require(output.toString().toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "budget_exceeded" }
        return output
    }

    private class Work(var nodes: Int = 0, var bytes: Int = 0)

    private fun count(work: Work, text: String) {
        work.bytes += text.toByteArray(StandardCharsets.UTF_8).size
        require(work.bytes <= MAX_BYTES) { "budget_exceeded" }
    }

    private fun visit(value: JsonElement, work: Work, depth: Int, structured: Boolean): JsonElement {
        work.nodes += 1
        require(work.nodes <= 4096) { "budget_exceeded" }
        if (depth > 16) return JsonPrimitive(REDACTED)
        return when (value) {
            is JsonObject -> {
                if (value.size > 256) return JsonPrimitive(REDACTED)
                val output = LinkedHashMap<String, JsonElement>()
                value.forEach { (key, nested) ->
                    if (key.length > 128) return@forEach
                    count(work, key)
                    if (scrubText(key) == key) {
                        output[key] = if (sensitiveKey(key)) JsonPrimitive(REDACTED)
                            else visit(nested, work, depth + 1, structured)
                    }
                }
                JsonObject(output)
            }
            is JsonArray -> {
                if (value.size > 256) return JsonPrimitive(REDACTED)
                JsonArray(value.map { visit(it, work, depth + 1, structured) })
            }
            is JsonPrimitive -> {
                if (!value.isString) return value
                val text = value.content
                if (text.length > 16 * 1024 || text.toByteArray(StandardCharsets.UTF_8).size > 16 * 1024) return JsonPrimitive(REDACTED)
                count(work, text)
                if (structured && (text.startsWith("{") || text.startsWith("["))) {
                    val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                    if (parsed is JsonObject || parsed is JsonArray) {
                        return JsonPrimitive(visit(parsed, work, 0, false).toString())
                    }
                }
                val cleaned = scrubText(text)
                JsonPrimitive(if (cleaned == text && (text.startsWith("{") || text.startsWith("[")) && MALFORMED_LABEL.containsMatchIn(text)) REDACTED else cleaned)
            }
            JsonNull -> JsonNull
        }
    }

    private fun sensitiveKey(key: String): Boolean {
        val parts = key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase().split(Regex("[^a-z0-9]+"))
        for (start in parts.indices) {
            val joined = StringBuilder()
            for (end in start until parts.size) {
                joined.append(parts[end])
                if (joined.toString() in keys) return true
            }
        }
        return false
    }

    private fun scrubText(text: String, scanUrls: Boolean = true): String {
        if (PEM_START.containsMatchIn(text) && !PEM.containsMatchIn(text)) return REDACTED
        var output = if (ENCODED_LABEL.containsMatchIn(text)) {
            runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8.name()) }.getOrElse { return REDACTED }
        } else text
        output = PEM.replace(output, REDACTED)
        output = HEADER.replace(output) { "${it.groupValues[1]}: $REDACTED" }
        output = BEARER.replace(output) { "${it.groupValues[1]} $REDACTED" }
        output = TOKEN.replace(output, REDACTED)
        output = assignment.replace(output) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        output = CARD.replace(output) { if (validCard(it.value)) REDACTED else it.value }
        if (!scanUrls) return output
        return URL.replace(output) { match ->
            val raw = match.value.trimEnd(')', '.', ',', ';')
            scrubUrl(raw) + match.value.substring(raw.length)
        }
    }

    private fun scrubUrl(raw: String): String = try {
        val uri = URI(raw)
        if (uri.host == null) {
            REDACTED
        } else {
            val prefix = buildString {
                append(uri.scheme).append("://")
                if (uri.rawUserInfo != null) append("REDACTED@")
                append(uri.host)
                if (uri.port != -1) append(':').append(uri.port)
                append(uri.rawPath.ifEmpty { "/" })
            }
            val query = uri.rawQuery?.split('&')?.mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
                val value = if (parts.size == 2) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) else ""
                if (key.length > 128 || scrubText(key, false) != key) return@mapNotNull null
                "${URLEncoder.encode(key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(if (sensitiveKey(key) || scrubText(value, false) != value) REDACTED else value, StandardCharsets.UTF_8.name())}"
            }?.joinToString("&")
            prefix + (if (query == null) "" else "?$query")
        }
    } catch (_: Exception) {
        REDACTED
    }

    private fun validCard(value: String): Boolean {
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 13..19 || digits.toSet().size == 1) return false
        var sum = 0
        digits.reversed().forEachIndexed { index, character ->
            var digit = character.digitToInt()
            if (index % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return sum % 10 == 0
    }

    private fun canonical(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private companion object {
        const val REDACTED = "[REDACTED]"
        const val MAX_BYTES = 256 * 1024
        val BASE_FIELDS = listOf("password", "secret", "token", "api_key", "apikey", "access_token", "refresh_token", "private_key", "accessToken", "refreshToken", "privateKey", "clientSecret", "passwd", "card_number", "credit_card", "cvv", "cvc", "pin", "expiry", "phone", "bearer", "session_id", "otp", "verification_code", "authorization", "cookie", "ssn", "client_secret", "x_api_key", "set_cookie", "proxy_authorization")
        val HEADER = Regex("\\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie)\\s*:\\s*[^\\r\\n]*", RegexOption.IGNORE_CASE)
        val BEARER = Regex("\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]{6,}", RegexOption.IGNORE_CASE)
        val TOKEN = Regex("\\bdbundle_(?:proj|mem|probe|agent)_[A-Za-z0-9_-]+\\b")
        val PEM = Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", RegexOption.IGNORE_CASE)
        val PEM_START = Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", RegexOption.IGNORE_CASE)
        val URL = Regex("\\bhttps?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
        val CARD = Regex("(?<![A-Za-z0-9_-])(?:[0-9][ -]?){12,18}[0-9](?![A-Za-z0-9_-])")
        val ENCODED_LABEL = Regex("(?:password|token|secret|authorization|cookie)%3[ad]", RegexOption.IGNORE_CASE)
        val MALFORMED_LABEL = Regex("(?:password|token|secret|authorization|cookie)[\"']?\\s*[:=]", RegexOption.IGNORE_CASE)
    }
}
