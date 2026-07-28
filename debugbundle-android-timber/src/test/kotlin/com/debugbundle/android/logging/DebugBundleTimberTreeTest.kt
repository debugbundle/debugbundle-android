package com.debugbundle.android.logging

import android.util.Log
import com.debugbundle.android.DebugBundleLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `tree maps priorities and falls back to throwable details safely`() {
        val captured = mutableListOf<Pair<String, DebugBundleLogLevel>>()
        val tree = object : DebugBundleTimberTree({ message, level, _ ->
            captured += message to level
        }) {
            fun emit(priority: Int, message: String, throwable: Throwable? = null) {
                log(priority = priority, tag = null, message = message, t = throwable)
            }
        }

        tree.emit(Log.VERBOSE, "verbose")
        tree.emit(Log.DEBUG, "debug")
        tree.emit(Log.INFO, "info")
        tree.emit(Log.WARN, "warning")
        tree.emit(Log.ASSERT, "critical")
        tree.emit(Log.ERROR, "", IllegalStateException("throwable message"))
        tree.emit(Log.ERROR, "", BareThrowable())
        tree.emit(12345, "ignored")
        tree.emit(Log.ERROR, "")

        assertEquals(
            listOf(
                DebugBundleLogLevel.Debug,
                DebugBundleLogLevel.Debug,
                DebugBundleLogLevel.Info,
                DebugBundleLogLevel.Warning,
                DebugBundleLogLevel.Critical,
                DebugBundleLogLevel.Error,
                DebugBundleLogLevel.Error,
            ),
            captured.map { it.second },
        )
        assertEquals("throwable message", captured[5].first)
        assertEquals("BareThrowable", captured[6].first)
        assertTrue(captured.none { it.first == "ignored" })
    }

    @Test
    fun `default tree capture path is safe before SDK initialization`() {
        val tree = object : DebugBundleTimberTree() {
            fun emit() {
                log(Log.ERROR, "Checkout", "not initialized", null)
            }
        }

        tree.emit()
    }

    private class BareThrowable : Throwable()
}
