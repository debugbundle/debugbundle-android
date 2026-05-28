package com.debugbundle.android.navigation

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import com.debugbundle.android.DebugBundle

class DebugBundleNavigationListener(
    private val source: String = "navigation",
    private val recordScreen: (String, String?, String) -> Unit = { screen, previous, capturedSource ->
        DebugBundle.recordScreen(screen, previous, capturedSource)
    },
) : NavController.OnDestinationChangedListener {
    private var previousScreen: String? = null

    override fun onDestinationChanged(controller: NavController, destination: NavDestination, arguments: Bundle?) {
        val screenName = resolveScreenName(destination)
        recordScreen(screenName, previousScreen, source)
        previousScreen = screenName
    }
}

fun NavController.installDebugBundleNavigationListener(
    listener: DebugBundleNavigationListener = DebugBundleNavigationListener(),
): DebugBundleNavigationListener {
    addOnDestinationChangedListener(listener)
    return listener
}

internal fun resolveScreenName(destination: NavDestination): String {
    return destination.route
        ?: destination.label?.toString()?.takeIf { it.isNotBlank() }
        ?: destination.displayName.substringAfterLast('/')
}
