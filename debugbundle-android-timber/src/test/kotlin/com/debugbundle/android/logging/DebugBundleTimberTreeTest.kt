package com.debugbundle.android.logging

import android.util.Log
import com.debugbundle.android.DebugBundleLogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugBundleTimberTreeTest {
    @Test
    fun `tree forwards log entries with timber metadata`() {
        val captured = mutableListOf<Triple<String, DebugBundleLogLevel, Map<String, Any?>>>()
        val tree = object : DebugBundleTimberTree({ message, level, context ->
            captured += Triple(message, level, context)
        }) {
            fun emit(priority: Int, tag: String?, message: String, throwable: Throwable?) {
                log(priority, tag, message, throwable)
            }
        }

        tree.emit(Log.ERROR, "Checkout", "payment failed", IllegalStateException("boom"))

        assertEquals(1, captured.size)
        assertEquals("payment failed", captured.single().first)
        assertEquals(DebugBundleLogLevel.Error, captured.single().second)
        assertEquals("timber", captured.single().third["logger"])
        assertEquals("Checkout", captured.single().third["tag"])
    }
}
