package com.debugbundle.android

class DebugBundleLifecycleRecorder internal constructor(
    private val client: DebugBundleClient,
) {
    fun onScreenVisible(screenName: String, previousScreen: String? = null, source: String = "manual") {
        client.recordScreen(screenName, previousScreen, source)
    }

    fun onAppForeground() {
        client.recordAppForeground()
    }

    fun onAppBackground() {
        client.recordAppBackground()
    }

    fun onUserAction(actionType: String, targetType: String, resourceName: String? = null) {
        client.recordAction(actionType, targetType, resourceName)
    }
}
