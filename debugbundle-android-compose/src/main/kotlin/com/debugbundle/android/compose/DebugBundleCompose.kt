package com.debugbundle.android.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.debugbundle.android.DebugBundle
import com.debugbundle.android.navigation.DebugBundleNavigationListener

@Composable
fun DebugBundleScreen(
    screenName: String,
    source: String = "compose",
) {
    LaunchedEffect(screenName, source) {
        DebugBundleComposeScreenRecorder.record(screenName, source)
    }
}

@Composable
fun rememberDebugBundleNavigationListener(
    source: String = "navigation_compose",
): DebugBundleNavigationListener {
    return remember(source) {
        DebugBundleNavigationListener(source = source)
    }
}

internal object DebugBundleComposeScreenRecorder {
    @Volatile
    var recordScreen: (String, String) -> Unit = { screenName, source ->
        DebugBundle.recordScreen(screenName, source = source)
    }

    fun record(screenName: String, source: String) {
        recordScreen(screenName, source)
    }
}
