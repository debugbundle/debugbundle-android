package com.debugbundle.smoke

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.debugbundle.android.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*

/** The same staged-package assertions run in Robolectric and on an Android runtime. */
fun assertInstalledNativeEvidence() {
        val server = MockWebServer(); val events = Collections.synchronizedList(mutableListOf<JsonObject>())
        val optedIn = Collections.synchronizedList(mutableListOf<String?>())
        val capabilities = """{"version":1,"features":["exception_evidence_v1","resource_observations_v1","recovery_v1","evidence_provenance_v1"]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                optedIn.add(request.getHeader("X-DebugBundle-Evidence"))
                if (request.path?.startsWith("/v1/sdk/config") == true)
                    return MockResponse().setResponseCode(200).setBody("""{"capture_policy":{"preset":"balanced"},"evidence_capabilities":$capabilities}""")
                if (request.path == "/v1/events") {
                    assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
                    val bytes = request.body.clone().readByteArray(); assertTrue(bytes.size <= 256 * 1024)
                    val batch = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject.getValue("events").jsonArray
                    events.addAll(batch.map { it.jsonObject })
                    return MockResponse().setResponseCode(202).setBody("""{"accepted":${batch.size},"rejected":0,"errors":[],"evidence_capabilities":$capabilities}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val client = DebugBundleClient.create(application = ApplicationProvider.getApplicationContext<Application>(),
            config = DebugBundleConfig(projectToken = "fixture-token", endpoint = server.url("/v1/events").toString(), captureFatalExceptions = false)
                .withEvidenceOptions(DebugBundleEvidenceOptions(captureRecoveryObservations = true)))
        try {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ("recovery_v1" !in client.evidenceCapabilities.features && System.nanoTime() < end) Thread.sleep(10)
            assertTrue(client.evidenceCapabilities.features.contains("recovery_v1"))
            val failure = UUID.randomUUID().toString(); val attempt = UUID.randomUUID().toString()
            val error = IllegalStateException("password=hidden outer", IllegalArgumentException("cause"))
            error.stackTrace = arrayOf(StackTraceElement("example.Checkout", "submit", "Checkout.kt", 42))
            client.withOperationContext {
                client.captureException(error, DebugBundleEvidenceContext.forFailure(failure))
                client.captureRecovery(DebugBundleRecoveryObservation(failure, attempt, "recovery_observed", operation = "checkout.refresh"))
            }
            client.flush(); assertEquals("password=hidden outer", error.message)
            assertEquals(2, events.size)
            val original = events.first { it["event_type"]?.jsonPrimitive?.content == "frontend_exception" }
            val outcome = events.first { it["event_type"]?.jsonPrimitive?.content == "diagnostic_context" }
            val nodes = original.getValue("payload").jsonObject.getValue("exception_evidence").jsonObject.getValue("nodes").jsonArray
            assertEquals(2, nodes.size)
            assertEquals("example.Checkout.submit", nodes.first().jsonObject.getValue("frames").jsonArray.first().jsonObject.getValue("function").jsonPrimitive.content)
            val reference = original.getValue("context").jsonObject.getValue("evidence_reference").jsonObject
            assertEquals(reference["scope"], outcome.getValue("payload").jsonObject["scope"])
            assertEquals(failure, outcome.getValue("payload").jsonObject.getValue("failure_ref").jsonPrimitive.content)
            assertFalse(events.toString().contains("hidden")); assertTrue(optedIn.isNotEmpty()); assertTrue(optedIn.all { it == "1" })
            assertEquals("3.1.2", original.getValue("sdk_version").jsonPrimitive.content)
        } finally { client.close(); server.shutdown() }
}
