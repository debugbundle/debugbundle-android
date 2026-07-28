package com.debugbundle.android

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DebugBundleRemoteConfigClientTest {
    @Test
    fun `remote config client derives endpoint and loads authenticated response`() {
        val requestedPath = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val ifNoneMatch = AtomicReference<String>()
        val server = server { exchange ->
            requestedPath.set(exchange.requestURI.path)
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            ifNoneMatch.set(exchange.requestHeaders.getFirst("If-None-Match"))
            exchange.responseHeaders.add("ETag", "v2")
            exchange.respond(
                200,
                """
                {
                  "probes_enabled": true,
                  "remote_probes_enabled": true,
                  "active_probes": [],
                  "poll_interval_ms": 60000,
                  "unknown_future_field": true
                }
                """.trimIndent(),
            )
        }

        try {
            val result = DebugBundleHttpRemoteConfigClient().fetch(
                request(server, "/v1/events", eTag = "v1"),
            ) as DebugBundleRemoteConfigResult.Loaded

            assertEquals("/v1/sdk/config", requestedPath.get())
            assertEquals("Bearer project-token", authorization.get())
            assertEquals("v1", ifNoneMatch.get())
            assertEquals("v2", result.eTag)
            assertEquals(60_000, result.config.pollIntervalMillis)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `remote config client handles not modified failure and endpoint variants`() {
        val observedPaths = mutableListOf<String>()
        val statuses = ArrayDeque(listOf(304, 503, 200))
        val server = server { exchange ->
            observedPaths += exchange.requestURI.path
            val status = statuses.removeFirst()
            if (status == 304) {
                exchange.responseHeaders.add("ETag", "v3")
                exchange.respond(status, "")
            } else if (status == 200) {
                exchange.respond(status, "{}")
            } else {
                exchange.respond(status, "unavailable")
            }
        }

        try {
            val client = DebugBundleHttpRemoteConfigClient()
            val notModified = client.fetch(request(server, "/events")) as DebugBundleRemoteConfigResult.NotModified
            assertEquals("v3", notModified.eTag)
            assertSame(
                DebugBundleRemoteConfigResult.Failed,
                client.fetch(request(server, "/custom/base/")),
            )
            val loaded = client.fetch(request(server, "")) as DebugBundleRemoteConfigResult.Loaded
            assertEquals(DebugBundleRemoteConfigResponse(), loaded.config)
            assertEquals(
                listOf("/sdk/config", "/custom/base/sdk/config", "/sdk/config"),
                observedPaths,
            )
        } finally {
            server.stop(0)
        }

        assertSame(
            DebugBundleRemoteConfigResult.Failed,
            DebugBundleHttpRemoteConfigClient().fetch(
                DebugBundleRemoteConfigRequest(
                    projectToken = "token",
                    endpoint = "not-a-url",
                    timeout = 10.milliseconds,
                ),
            ),
        )
    }

    private fun request(
        server: HttpServer,
        endpointPath: String,
        eTag: String? = null,
    ): DebugBundleRemoteConfigRequest {
        return DebugBundleRemoteConfigRequest(
            projectToken = "project-token",
            endpoint = "http://127.0.0.1:${server.address.port}$endpointPath",
            timeout = 500.milliseconds,
            eTag = eTag,
        )
    }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer {
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handler(exchange) }
            start()
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val responseLength = if (status == 304) -1L else bytes.size.toLong()
        sendResponseHeaders(status, responseLength)
        if (responseLength >= 0) {
            responseBody.use { it.write(bytes) }
        } else {
            responseBody.close()
        }
    }
}
