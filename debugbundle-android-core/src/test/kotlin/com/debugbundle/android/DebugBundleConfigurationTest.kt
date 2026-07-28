package com.debugbundle.android

import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleConfigurationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `java factory preserves every bridge option`() {
        val config = DebugBundleConfig.create(
            projectToken = "token",
            enabled = false,
            environment = "staging",
            service = "checkout",
            endpoint = "https://events.example.test",
            batchSize = 7,
            flushIntervalMillis = 1234,
            sampleRate = 0.5,
            sessionSampleRate = 0.25,
            requestTimeoutMillis = 4321,
            releaseChannel = "beta",
            appVersion = "1.2.3",
            buildNumber = "42",
            maxEventsPerSession = 99,
            maxBreadcrumbs = 30,
            captureScreens = false,
            captureActions = true,
            captureNetwork = false,
            captureLogs = false,
            logLevel = DebugBundleLogLevel.Critical,
            headerAllowlist = setOf("X-Tenant"),
            offlineQueueMaxEvents = 17,
            offlineQueueMaxBytes = 2048,
            offlineQueueTtlMillis = 9999,
            maxProbeLabels = 12,
            maxProbeEntriesPerLabel = 4,
            probeFlushOnError = false,
            redactFields = setOf("credential"),
            sdkVersion = "9.8.7",
        )

        assertEquals("token", config.projectToken)
        assertFalse(config.enabled)
        assertEquals("staging", config.environment)
        assertEquals("checkout", config.service)
        assertEquals("https://events.example.test", config.endpoint)
        assertEquals(7, config.batchSize)
        assertEquals(1234, config.flushInterval.inWholeMilliseconds)
        assertEquals(0.5, config.sampleRate)
        assertEquals(0.25, config.sessionSampleRate)
        assertEquals(4321, config.requestTimeout.inWholeMilliseconds)
        assertEquals("beta", config.releaseChannel)
        assertEquals("1.2.3", config.appVersion)
        assertEquals("42", config.buildNumber)
        assertEquals(99, config.maxEventsPerSession)
        assertEquals(30, config.maxBreadcrumbs)
        assertFalse(config.captureScreens)
        assertTrue(config.captureActions)
        assertFalse(config.captureNetwork)
        assertFalse(config.captureLogs)
        assertEquals(DebugBundleLogLevel.Critical, config.logLevel)
        assertEquals(setOf("X-Tenant"), config.headerAllowlist)
        assertEquals(17, config.offlineQueueMaxEvents)
        assertEquals(2048, config.offlineQueueMaxBytes)
        assertEquals(9999, config.offlineQueueTtl.inWholeMilliseconds)
        assertEquals(12, config.maxProbeLabels)
        assertEquals(4, config.maxProbeEntriesPerLabel)
        assertFalse(config.probeFlushOnError)
        assertEquals(setOf("credential"), config.redactFields)
        assertEquals("9.8.7", config.sdkVersion)
    }

    @Test
    fun `normalization and default stores fail safe for invalid caller values`() {
        val hook = DebugBundleBeforeSend { it }
        val normalized = normalizeDebugBundleConfig(
            DebugBundleConfig(
                service = "",
                environment = "",
                endpoint = "",
                batchSize = 0,
                sampleRate = 2.0,
                sessionSampleRate = -1.0,
                maxEventsPerSession = 0,
                offlineQueueMaxEvents = 0,
                offlineQueueMaxBytes = 0,
                maxProbeLabels = 0,
                maxProbeEntriesPerLabel = 0,
                headerAllowlist = setOf(" X-TENANT "),
            ).withBeforeSend(hook),
        )

        assertEquals("android-app", normalized.service)
        assertEquals("production", normalized.environment)
        assertEquals(DebugBundleConfig.DEFAULT_ENDPOINT, normalized.endpoint)
        assertEquals(1, normalized.batchSize)
        assertEquals(1.0, normalized.sampleRate)
        assertEquals(0.0, normalized.sessionSampleRate)
        assertEquals(1, normalized.maxEventsPerSession)
        assertEquals(1, normalized.offlineQueueMaxEvents)
        assertEquals(1, normalized.offlineQueueMaxBytes)
        assertEquals(1, normalized.maxProbeLabels)
        assertEquals(1, normalized.maxProbeEntriesPerLabel)
        assertEquals(setOf("x-tenant"), normalized.headerAllowlist)
        assertEquals(hook, normalized.beforeSend)
        assertTrue(defaultDebugBundleQueueStore(normalized) is InMemoryDebugBundleQueueStore)
        assertNotNull(defaultDebugBundleFatalCrashStore(normalized))
        assertEquals(DebugBundleStatus.Disconnected, initialDebugBundleStatus(normalized))
        assertEquals(
            DebugBundleQueueLimits(1, 1, normalized.offlineQueueTtl.inWholeMilliseconds),
            debugBundleQueueLimits(normalized),
        )
    }

    @Test
    fun `android bootstrap reflection remains optional and fallback stays operational`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            assertEquals(
                null,
                tryCreateAndroidBootstrap(
                    application = null,
                    config = DebugBundleConfig(enabled = false),
                    transport = DebugBundleTransport { DebugBundleTransportResult(204) },
                    remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
                    queueStore = null,
                    deviceContextProvider = null,
                    runtimeRegistration = null,
                    clock = { Instant.EPOCH },
                    random = { 0.0 },
                    executor = executor,
                ),
            )
            val unavailable = tryCreateAndroidBootstrap(
                application = Any(),
                config = DebugBundleConfig(enabled = false),
                transport = DebugBundleTransport { DebugBundleTransportResult(204) },
                remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
                queueStore = null,
                deviceContextProvider = null,
                runtimeRegistration = null,
                clock = { Instant.EPOCH },
                random = { 0.0 },
                executor = executor,
            )
            assertEquals(null, unavailable)

            val fallback = createDebugBundleClientWithoutAndroidBootstrap(
                application = Any(),
                config = DebugBundleConfig(enabled = false, offlineQueuePath = tempDir.resolve("queue.json")),
                transport = DebugBundleTransport { DebugBundleTransportResult(204) },
                remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Failed },
                queueStore = null,
                deviceContextProvider = null,
                runtimeRegistration = null,
                clock = { Instant.EPOCH },
                random = { 0.0 },
                executor = executor,
            )
            assertEquals(DebugBundleStatus.Disconnected, fallback.status)
            fallback.close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `config data classes and response defaults remain stable`() {
        val request = DebugBundleRemoteConfigRequest(
            projectToken = "token",
            endpoint = "https://example.test/v1/events",
            timeout = 100.milliseconds,
            eTag = "v1",
        )
        val notModified = DebugBundleRemoteConfigResult.NotModified(request.eTag)
        val response = DebugBundleRemoteConfigResponse()

        assertEquals("v1", notModified.eTag)
        assertTrue(response.probesEnabled)
        assertFalse(response.remoteProbesEnabled)
        assertTrue(response.activeProbes.isEmpty())
        assertEquals(0, response.pollIntervalMillis)
        assertEquals(null, response.triggerTokenKey)
        assertEquals(null, response.capturePolicy)
        assertTrue(DebugBundleConfig.DEFAULT_REDACT_FIELDS.contains("authorization"))
        assertTrue(DebugBundleConfig.DEFAULT_HEADER_ALLOWLIST.contains("traceparent"))
        assertTrue(DebugBundleConfig.DEFAULT_SDK_VERSION.isNotBlank())
    }
}
