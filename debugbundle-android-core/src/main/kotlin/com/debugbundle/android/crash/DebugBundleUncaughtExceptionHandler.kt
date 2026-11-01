package com.debugbundle.android.crash

import java.time.Instant
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.internal.DebugBundleRedactor

internal class DebugBundleUncaughtExceptionHandler(
    private val crashStore: DebugBundleFatalCrashStore,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Instant,
    private val redactor: DebugBundleRedactor = DebugBundleRedactor(DebugBundleConfig.DEFAULT_REDACT_FIELDS),
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            crashStore.persist(DebugBundleFatalSnapshot.capture(thread, throwable, clock, redactor))
        } catch (_: Throwable) {
            // Preserve host crash behavior.
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }

}

internal class DebugBundleInstalledUncaughtExceptionHandler(
    private val handler: Thread.UncaughtExceptionHandler,
    private val previous: Thread.UncaughtExceptionHandler?,
) : AutoCloseable {
    init {
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    override fun close() {
        if (Thread.getDefaultUncaughtExceptionHandler() === handler) {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
