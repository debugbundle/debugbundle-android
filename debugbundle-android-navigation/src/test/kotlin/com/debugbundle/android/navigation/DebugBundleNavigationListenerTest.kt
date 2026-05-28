package com.debugbundle.android.navigation

import androidx.navigation.NavDestination
import java.lang.reflect.Method
import sun.misc.Unsafe
import org.junit.Assert.assertEquals
import org.junit.Test

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
