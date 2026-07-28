package com.debugbundle.android.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sun.misc.Unsafe

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugBundleNavigationListenerTest {
    @Test
    fun `listener records screen transitions with previous screen`() {
        val recordings = mutableListOf<Triple<String, String?, String>>()
        val listener = DebugBundleNavigationListener { screen, previous, source ->
            recordings += Triple(screen, previous, source)
        }
        val first = NavDestination("activity").apply { label = "Checkout" }
        val second = NavDestination("fragment").apply { route = "payments/details" }

        invokeDestinationChanged(listener, first)
        invokeDestinationChanged(listener, second)

        assertEquals(
            listOf(
                Triple("Checkout", null, "navigation"),
                Triple("payments/details", "Checkout", "navigation"),
            ),
            recordings,
        )
    }

    @Test
    fun `install extension returns registered listener and screen name falls back to display name`() {
        val controller = NavController(ApplicationProvider.getApplicationContext<Application>())
        val listener = DebugBundleNavigationListener(recordScreen = { _, _, _ -> })

        assertSame(listener, controller.installDebugBundleNavigationListener(listener))
        assertEquals(
            NavDestination("fragment").displayName.substringAfterLast('/'),
            resolveScreenName(NavDestination("fragment")),
        )
        controller.removeOnDestinationChangedListener(listener)

        val defaultListener = controller.installDebugBundleNavigationListener()
        controller.removeOnDestinationChangedListener(defaultListener)
    }

    private fun invokeDestinationChanged(listener: DebugBundleNavigationListener, destination: NavDestination) {
        onDestinationChangedMethod().invoke(listener, navControllerStub(), destination, null)
    }

    private fun onDestinationChangedMethod(): Method {
        return DebugBundleNavigationListener::class.java.getMethod(
            "onDestinationChanged",
            androidx.navigation.NavController::class.java,
            NavDestination::class.java,
            android.os.Bundle::class.java,
        )
    }

    private fun navControllerStub(): androidx.navigation.NavController {
        return unsafe().allocateInstance(androidx.navigation.NavController::class.java) as androidx.navigation.NavController
    }

    private fun unsafe(): Unsafe {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null) as Unsafe
    }
}
