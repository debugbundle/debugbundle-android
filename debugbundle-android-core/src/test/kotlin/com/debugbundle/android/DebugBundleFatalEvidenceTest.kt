package com.debugbundle.android

import com.debugbundle.android.crash.DebugBundleFatalCrashRecord
import com.debugbundle.android.crash.DebugBundleFatalCrashStore
import com.debugbundle.android.crash.DebugBundleUncaughtExceptionHandler
import com.debugbundle.android.crash.FileDebugBundleFatalCrashStore
import com.debugbundle.android.testkit.RecordingTransport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DebugBundleFatalEvidenceTest {
    @TempDir lateinit var directory: Path
    private class Store : DebugBundleFatalCrashStore {
        var record: DebugBundleFatalCrashRecord? = null
        override fun load() = record
        override fun persist(record: DebugBundleFatalCrashRecord) { this.record = record }
        override fun clear() { record = null }
    }
    @Test
    fun `fatal custom accessors are unavailable and previous host handler receives the same throwable`() {
        val calls = AtomicInteger(); val delegated = AtomicInteger(); val store = Store()
        val error = object : RuntimeException() {
            override val message: String get() { calls.incrementAndGet(); throw IllegalStateException("foreign") }
            override fun getStackTrace(): Array<StackTraceElement> { calls.incrementAndGet(); throw IllegalStateException("foreign") }
        }
        val handler = DebugBundleUncaughtExceptionHandler(store, Thread.UncaughtExceptionHandler { _, caught -> assertSame(error, caught); delegated.incrementAndGet() }, Instant::now)
        handler.uncaughtException(Thread.currentThread(), error)
        assertEquals(0, calls.get()); assertEquals(1, delegated.get()); assertNotNull(store.record)
        assertTrue(store.record!!.stackTrace.isEmpty())
    }
    @Test
    fun `fatal record is private before persistence and replay uses supplied native frames without hooks`() {
        val store = Store(); val handler = DebugBundleUncaughtExceptionHandler(store, Thread.UncaughtExceptionHandler { _, _ -> }, { Instant.parse("2026-10-28T00:00:00Z") })
        val error = IllegalStateException("password=hidden fatal")
        error.stackTrace = (1..20).map { StackTraceElement("example.Checkout", "submit", "Checkout.kt", it) }.toTypedArray()
        handler.uncaughtException(Thread.currentThread(), error)
        assertFalse(store.record!!.errorMessage.contains("hidden"))
        val path = directory.resolve("fatal.json"); FileDebugBundleFatalCrashStore(path).persist(store.record!!)
        val transport = RecordingTransport(); val hooks = AtomicInteger(); val previous = Thread.getDefaultUncaughtExceptionHandler()
        val client = DebugBundleClient.create(config = DebugBundleConfig(projectToken = "fixture", fatalCrashPath = path, flushInterval = 1.hours)
            .withEvidenceOptions(DebugBundleEvidenceOptions(evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1"))))
            .withBeforeSend(DebugBundleBeforeSend { hooks.incrementAndGet(); it }), transport = transport,
            remoteConfigClient = DebugBundleRemoteConfigClient { DebugBundleRemoteConfigResult.Loaded(DebugBundleRemoteConfigResponse()) })
        try {
            client.flush(); val event = transport.events.single(); val graph = event.payload.getValue("exception_evidence").jsonObject
            val nodes = graph.getValue("nodes").jsonArray; assertTrue(nodes.size <= 2)
            assertTrue(nodes.sumOf { it.jsonObject.getValue("frames").jsonArray.size } <= 8)
            assertEquals("native_fatal", nodes.first().jsonObject.getValue("trace_origin").jsonPrimitive.content)
            assertTrue(DebugBundleAcceptingJsonSize.bytes(graph) <= 4096); assertEquals(0, hooks.get())
            assertFalse(event.payload.toString().contains("hidden")); assertFalse(Files.exists(path))
        } finally { client.close(); Thread.setDefaultUncaughtExceptionHandler(previous) }
    }
    @Test
    fun `oversized retained crash file is rejected before recovery`() {
        val path = directory.resolve("oversized.json")
        val record = DebugBundleFatalCrashRecord(occurredAt = Instant.now().toString(), threadName = "fixture", errorType = "Error", errorMessage = "x".repeat(300 * 1024), stackTrace = emptyList())
        Files.writeString(path, Json.encodeToString(record))
        assertNull(FileDebugBundleFatalCrashStore(path).load())
    }
}
