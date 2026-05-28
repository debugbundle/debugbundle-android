package com.debugbundle.smoke

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.debugbundle.android.DebugBundle
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.network.DebugBundleOkHttpInterceptor
import com.debugbundle.android.network.DebugBundleTracePropagationTarget
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDrivenSmokeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun publishedArtifactsCaptureAndFlushRequestEvents() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/v1/sdk/config" -> MockResponse().setResponseCode(304)
                    request.path == "/checkout" -> MockResponse().setResponseCode(500).setBody("failure")
                    request.path == "/v1/events" -> MockResponse().setResponseCode(202).setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val tempDir = Files.createTempDirectory("debugbundle-android-smoke")
        val application = ApplicationProvider.getApplicationContext<Application>()
        server.start()

        try {
            val client = DebugBundle.init(
                application = application,
                config = DebugBundleConfig(
                    projectToken = "db_project_token_smoke",
                    service = "smoke-android",
                    environment = "ci",
                    endpoint = server.url("/v1/events").toString(),
                    batchSize = 1,
                    captureFatalExceptions = false,
                    offlineQueuePath = tempDir.resolve("queue.json"),
                    fatalCrashPath = tempDir.resolve("fatal-crash.json"),
                ),
            )

            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(
                    DebugBundleOkHttpInterceptor(
                        tracePropagationTargets = listOf(
                            DebugBundleTracePropagationTarget.host(server.hostName),
                        ),
                    ),
                )
                .build()

            okHttpClient.newCall(
                Request.Builder()
                    .url(server.url("/checkout"))
                    .build(),
            ).execute().use { response: okhttp3.Response ->
                assertEquals(500, response.code)
            }

            client.flush()
            client.close()

            val requests = mutableListOf<RecordedRequest>()
            repeat(3) {
                server.takeRequest(5, TimeUnit.SECONDS)?.let(requests::add)
            }

            val checkoutRequest = requireNotNull(requests.firstOrNull { it.path == "/checkout" }) {
                "Expected smoke request to reach the test server."
            }
            val eventRequest = requireNotNull(requests.firstOrNull { it.path == "/v1/events" }) {
                "Expected DebugBundle ingestion POST to reach the test server."
            }

            val traceId = requireNotNull(checkoutRequest.getHeader("X-DebugBundle-Trace-Id")) {
                "Expected OkHttp interceptor to inject the trace header."
            }
            assertEquals("Bearer db_project_token_smoke", eventRequest.getHeader("Authorization"))

            val eventPayload = json.parseToJsonElement(eventRequest.body.readUtf8()).jsonObject
            val events = eventPayload.getValue("events").jsonArray
            assertEquals(1, events.size)

            val event = events.single().jsonObject
            assertEquals("request_event", event.getValue("event_type").jsonPrimitive.content)
            assertEquals("@debugbundle/sdk-android", event.getValue("sdk_name").jsonPrimitive.content)
            assertEquals(DebugBundleConfig.DEFAULT_SDK_VERSION, event.getValue("sdk_version").jsonPrimitive.content)
            assertEquals("smoke-android", event.getValue("service").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals("ci", event.getValue("service").jsonObject.getValue("environment").jsonPrimitive.content)
            assertEquals(traceId, event.getValue("correlation").jsonObject.getValue("trace_id").jsonPrimitive.content)
        } finally {
            server.shutdown()
            tempDir.toFile().deleteRecursively()
        }
    }
}
