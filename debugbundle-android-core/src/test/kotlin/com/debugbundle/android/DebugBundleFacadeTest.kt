package com.debugbundle.android

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class DebugBundleFacadeTest {
    @AfterEach
    fun resetFacade() {
        DebugBundle.init(DebugBundleConfig(enabled = false))
    }

    @Test
    fun `disabled facade safely delegates every public capture surface`() {
        DebugBundle.init(
            application = null,
            config = DebugBundleConfig(
                enabled = false,
                requestTimeout = 25.milliseconds,
            ),
        )

        assertEquals(DebugBundleStatus.Disconnected, DebugBundle.status)
        assertEquals(null, DebugBundle.lastEventAt)
        assertNotNull(DebugBundle.lifecycle)

        DebugBundle.captureException(IllegalStateException("exception"), mapOf("route" to "/checkout"))
        DebugBundle.captureError(IllegalArgumentException("error"))
        DebugBundle.captureLog("log", DebugBundleLogLevel.Info)
        DebugBundle.captureMessage("message", DebugBundleLogLevel.Error)
        DebugBundle.captureRequest(
            request = DebugBundleRequestInfo(method = "GET", url = "/checkout"),
            response = DebugBundleResponseInfo(statusCode = 503),
            recordBreadcrumb = false,
        )
        DebugBundle.recordRequest(
            request = DebugBundleRequestInfo(method = "POST", url = "/pay"),
            response = DebugBundleResponseInfo(statusCode = 200),
            context = mapOf("source" to "facade"),
            recordBreadcrumb = true,
        )
        DebugBundle.setContext("tenant", "store-42")
        DebugBundle.probe("checkout.value", 42)
        DebugBundle.probe("checkout.lazy") { mapOf("value" to 42) }
        DebugBundle.captureBreadcrumb("navigation", "/checkout")
        DebugBundle.recordBreadcrumb("action", "/checkout", mapOf("target" to "pay"))
        DebugBundle.recordScreen("Checkout", "Cart", "manual")
        DebugBundle.recordAppForeground()
        DebugBundle.recordAppBackground()
        DebugBundle.recordAction("tap", "button", "pay")
        DebugBundle.lifecycle.onScreenVisible("Confirmation", "Checkout", "activity")
        DebugBundle.lifecycle.onAppForeground()
        DebugBundle.lifecycle.onAppBackground()
        DebugBundle.lifecycle.onUserAction("submit", "form", "checkout")
        DebugBundle.flush(25.milliseconds)
        DebugBundle.refreshRemoteConfig(force = false)

        assertFalse(DebugBundle.captureExternalEvent(emptyMap()))
        assertFalse(DebugBundle.isExternalProbeActive("checkout.value"))
        assertFalse(
            DebugBundle.captureExternalProbe(
                sdkVersion = "1.0.0",
                service = "checkout",
                environment = "production",
                label = "checkout.value",
                data = 42,
                occurredAt = "2026-05-28T10:15:30Z",
            ),
        )
        assertFalse(DebugBundle.activateProbeTriggerToken("invalid"))
        assertNotNull(DebugBundle.coroutineExceptionHandler())
    }

    @Test
    fun `capture sink and coroutine support defaults remain callable through interfaces`() {
        val sink = object : DebugBundleCaptureSink {
            override fun recordRequest(
                request: DebugBundleRequestInfo,
                response: DebugBundleResponseInfo,
                context: Map<String, Any?>,
                recordBreadcrumb: Boolean,
            ) = Unit

            override fun recordBreadcrumb(
                breadcrumbType: String,
                route: String?,
                data: Map<String, Any?>,
            ) = Unit
        }
        val coroutineSupport = object : DebugBundleCoroutineSupport {
            override fun coroutineExceptionHandler(
                context: Map<String, Any?>,
            ): CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> }
        }

        assertFalse(sink.activateProbeTriggerToken("token"))
        assertNotNull(coroutineSupport.coroutineExceptionHandler())
    }
}
