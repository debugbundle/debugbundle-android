package com.debugbundle.android

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugBundleEvidenceHttpReplyTest {
    private fun event() = DebugBundleEnvelope("2026-03-01", "11111111-1111-4111-8111-111111111111", DebugBundleEventTypes.LOG_EVENT, "@debugbundle/sdk-android", "candidate", DebugBundleServiceDescriptor("fixture", "test"), "2026-10-28T00:00:00Z", payload = JsonObject(mapOf("level" to JsonPrimitive("error"), "message" to JsonPrimitive("fixture"), "attributes" to JsonObject(emptyMap()))))
    private fun server(handler: (HttpExchange) -> Unit): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.createContext("/") { exchange -> exchange.requestBody.use { input -> input.readBytes() }; handler(exchange) }; it.start() }
    @Test
    fun `native acknowledgement discovers raw closed proof using existing request opt in`() {
        var optIn: String? = null
        val server = server { exchange ->
            optIn = exchange.requestHeaders.getFirst("X-DebugBundle-Evidence")
            val body = """{"accepted":1,"rejected":0,"errors":[],"evidence_capabilities":{"version":1,"features":["exception_evidence_v1"]}}""".toByteArray()
            exchange.sendResponseHeaders(202, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        try {
            val result = DebugBundleHttpTransport().send(DebugBundleTransportRequest("fixture", "http://127.0.0.1:${server.address.port}/v1/events", listOf(event()), 2.seconds))
            assertEquals("1", optIn); assertEquals(listOf("exception_evidence_v1"), DebugBundleEvidenceCapabilities.normalize(result.evidenceCapabilities).features)
        } finally { server.stop(0) }
    }
    @Test
    fun `oversized encoded and invalid UTF8 native replies cannot grant acceptance or reader proof`() {
        for (mode in listOf("size", "encoding", "utf8")) {
            val server = server { exchange ->
                val body = if (mode == "size") ByteArray(262145) { 65 } else byteArrayOf(0xc3.toByte(), 0x28)
                if (mode == "encoding") exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(202, body.size.toLong()); runCatching { exchange.responseBody.use { it.write(body) } }
            }
            try {
                val result = DebugBundleHttpTransport().send(DebugBundleTransportRequest("fixture", "http://127.0.0.1:${server.address.port}/v1/events", listOf(event()), 2.seconds))
                assertEquals(null, result.acknowledgement); assertEquals(null, result.evidenceCapabilities)
            } finally { server.stop(0) }
        }
    }
    @Test
    fun `body drip shares the native request deadline`() {
        val server = server { exchange ->
            exchange.sendResponseHeaders(202, 0)
            runCatching { exchange.responseBody.use { stream -> repeat(100) { stream.write(32); stream.flush(); Thread.sleep(20) } } }
        }
        try {
            val started = System.nanoTime()
            val result = DebugBundleHttpTransport().send(DebugBundleTransportRequest("fixture", "http://127.0.0.1:${server.address.port}/v1/events", listOf(event()), 150.milliseconds))
            assertEquals(null, result.acknowledgement); assertTrue((System.nanoTime() - started) / 1000000 < 1000)
        } finally { server.stop(0) }
    }
}
