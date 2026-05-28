package com.debugbundle.android.runtime

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.debugbundle.android.DebugBundleLifecycleRecorder

internal interface DebugBundleBackgroundFlushScheduler {
    fun ensureScheduled()

    fun scheduleImmediate()
}

internal object NoopDebugBundleBackgroundFlushScheduler : DebugBundleBackgroundFlushScheduler {
    override fun ensureScheduled() = Unit

    override fun scheduleImmediate() = Unit
}

internal class DebugBundleProcessLifecycleObserver(
    private val recorder: DebugBundleLifecycleRecorder,
    private val scheduler: DebugBundleBackgroundFlushScheduler,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        recorder.onAppForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        recorder.onAppBackground()
        scheduler.scheduleImmediate()
    }
}

internal class DebugBundleProcessLifecycleInstaller(
    recorder: DebugBundleLifecycleRecorder,
    scheduler: DebugBundleBackgroundFlushScheduler,
) : AutoCloseable {
    private val lifecycle = ProcessLifecycleOwner.get().lifecycle
    private val observer = DebugBundleProcessLifecycleObserver(recorder, scheduler)

    init {
        lifecycle.addObserver(observer)
    }

    override fun close() {
        lifecycle.removeObserver(observer)
    }
}
