package com.debugbundle.android

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebugBundleHttpTransportTest {
    @Test
    fun `transport honors HTTP date retry hints and bounds numeric overflow`() {
        val hints = mapOf("Wed, 01 Jan 2031 00:00:00 GMT" to 300_000L,
            "Wednesday, 01-Jan-31 00:00:00 GMT" to 300_000L,
            "Wed Jan  1 00:00:00 2031" to 300_000L,
            "Sun, 06 Nov 1994 08:49:37 GMT" to 0L,
            "1e300" to 300_000L, "NaN" to 0L, "tomorrow" to 0L)
        hints.forEach { (header, expected) ->
            val server = server { exchange ->
                exchange.responseHeaders.add("Retry-After", header)
                exchange.respond(503, "{}")
            }
            try {
                val result = DebugBundleHttpTransport().send(request(server, emptyList()))
                assertEquals(expected, result.retryAfter.inWholeMilliseconds, header)
            } finally { server.stop(0) }
        }
    }

    @Test
    fun `transport sends canonical batch and parses acknowledgement directives and retry delay`() {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val server = server { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Retry-After", "999")
            exchange.respond(
                202,
                """
                {
                  "accepted": 1,
                  "rejected": 0,
                  "errors": [],
                  "probe_directives": {
                    "active_probes": [{
                      "activation_id": "11111111-1111-4111-8111-111111111111",
                      "label_pattern": "checkout.*",
                      "service": "checkout",
                      "environment": "production",
                      "expires_at": "2036-05-28T10:15:30Z"
                    }]
                  }
                }
                """.trimIndent(),
            )
        }

        try {
            val result = DebugBundleHttpTransport().send(request(server, listOf(envelope())))

            assertEquals(202, result.statusCode)
            assertEquals(300_000, result.retryAfter.inWholeMilliseconds)
            assertEquals(1, result.acknowledgement?.accepted)
            assertEquals(0, result.acknowledgement?.rejected)
            assertEquals("checkout.*", result.probeDirectives?.single()?.labelPattern)
            assertTrue(result.acknowledgementRequired)
            assertEquals("Bearer project-token", authorization.get())
            assertTrue(requestBody.get().contains("\"events\""))
            assertTrue(requestBody.get().contains("\"event_id\":\"22222222-2222-4222-8222-222222222222\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `transport treats incomplete blank malformed and unreachable responses safely`() {
        val responses = listOf(
            200 to """{"accepted":1,"rejected":0}""",
            204 to "",
            422 to "not-json",
        )

        responses.forEach { (status, body) ->
            val server = server { exchange -> exchange.respond(status, body) }
            try {
                val result = DebugBundleHttpTransport().send(request(server, listOf(envelope())))
                assertEquals(status, result.statusCode)
                assertNull(result.acknowledgement)
                assertEquals(status in 200..299, result.acknowledgementRequired)
            } finally {
                server.stop(0)
            }
        }

        val unreachable = DebugBundleHttpTransport().send(
            DebugBundleTransportRequest(
                endpoint = "not-a-valid-url",
                projectToken = "token",
                events = listOf(envelope()),
                timeout = 10.milliseconds,
            ),
        )
        assertEquals(500, unreachable.statusCode)
        assertFalseAcknowledgement(unreachable)
    }

    private fun request(
        server: HttpServer,
        events: List<DebugBundleEnvelope>,
    ): DebugBundleTransportRequest {
        return DebugBundleTransportRequest(
            endpoint = "http://127.0.0.1:${server.address.port}/v1/events",
            projectToken = "project-token",
            events = events,
            timeout = 500.milliseconds,
        )
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer {
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/events") { exchange -> handler(exchange) }
            start()
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun assertFalseAcknowledgement(result: DebugBundleTransportResult) {
        assertNull(result.acknowledgement)
        assertEquals(false, result.acknowledgementRequired)
    }

    private fun envelope(): DebugBundleEnvelope {
        return DebugBundleEnvelope(
            schemaVersion = "2026-03-01",
            eventId = "22222222-2222-4222-8222-222222222222",
            eventType = DebugBundleEventTypes.LOG_EVENT,
            sdkName = "@debugbundle/sdk-android",
            sdkVersion = "1.0.0",
            service = DebugBundleServiceDescriptor("checkout", "production"),
            occurredAt = "2026-05-28T10:15:30Z",
            payload = kotlinx.serialization.json.buildJsonObject {
                put("level", "error")
                put("message", "failed")
                put("attributes", kotlinx.serialization.json.buildJsonObject {})
            },
        )
    }
}
