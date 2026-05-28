package com.debugbundle.android.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.debugbundle.android.DebugBundleLifecycleRecorder

internal class DebugBundleActivityLifecycleCallbacks(
    private val recorder: DebugBundleLifecycleRecorder,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        recorder.onScreenVisible(activityScreenName(activity), source = "activity")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal class DebugBundleActivityLifecycleInstaller(
    application: Application,
    recorder: DebugBundleLifecycleRecorder,
) : AutoCloseable {
    private val appRef = application
    private val callbacks = DebugBundleActivityLifecycleCallbacks(recorder)

    init {
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    override fun close() {
        appRef.unregisterActivityLifecycleCallbacks(callbacks)
    }
}

internal fun activityScreenName(activity: Activity): String {
    val activityClass = activity::class.java
    val activityTitle = runCatching { activity.title?.toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    val localClassName = runCatching { activity.localClassName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    return when {
        activityTitle != null -> activityTitle
        activityClass.simpleName.isNotBlank() -> activityClass.simpleName
        localClassName != null -> localClassName
        else -> activityClass.name.substringAfterLast('.')
    }
}
