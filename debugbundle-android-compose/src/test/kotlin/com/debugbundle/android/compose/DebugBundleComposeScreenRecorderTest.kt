package com.debugbundle.android.compose

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugBundleComposeScreenRecorderTest {
    @After
    fun tearDown() {
        DebugBundleComposeScreenRecorder.recordScreen = { screenName, source ->
            com.debugbundle.android.DebugBundle.recordScreen(screenName, source = source)
        }
    }

    @Test
    fun `recorder forwards screen names and source`() {
        val recordings = mutableListOf<Pair<String, String>>()
        DebugBundleComposeScreenRecorder.recordScreen = { screenName, source ->
            recordings += screenName to source
        }

        DebugBundleComposeScreenRecorder.record("Checkout", "compose")

        assertEquals(listOf("Checkout" to "compose"), recordings)
    }
}
