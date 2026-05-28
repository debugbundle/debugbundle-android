package com.debugbundle.android.crash

import java.time.Instant

internal class DebugBundleUncaughtExceptionHandler(
    private val crashStore: DebugBundleFatalCrashStore,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val clock: () -> Instant,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            crashStore.persist(
                DebugBundleFatalCrashRecord(
                    occurredAt = clock().toString(),
                    threadName = thread.name.take(MAX_THREAD_NAME_LENGTH),
                    errorType = throwable::class.qualifiedName ?: "Throwable",
                    errorMessage = (throwable.message ?: "").take(MAX_MESSAGE_LENGTH),
                    stackTrace = throwable.stackTrace
                        .take(MAX_STACK_FRAMES)
                        .map { it.toString().take(MAX_FRAME_LENGTH) },
                ),
            )
        } catch (_: Throwable) {
            // Preserve host crash behavior.
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val MAX_THREAD_NAME_LENGTH = 128
        private const val MAX_MESSAGE_LENGTH = 512
        private const val MAX_STACK_FRAMES = 20
        private const val MAX_FRAME_LENGTH = 256
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
