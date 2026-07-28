package com.debugbundle.android.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import com.debugbundle.android.navigation.DebugBundleNavigationListener
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugBundleComposeScreenRecorderTest {
    @After
    fun tearDown() {
        DebugBundleComposeScreenRecorder.recordScreen = { screenName, source ->
            com.debugbundle.android.DebugBundle.recordScreen(screenName, source = source)
        }
    }

    @Test
    fun `recorder forwards screen names and source`() {
        val recordings = mutableListOf<Pair<String, String>>()
        DebugBundleComposeScreenRecorder.recordScreen = { screenName, source ->
            recordings += screenName to source
        }

        DebugBundleComposeScreenRecorder.record("Checkout", "compose")

        assertEquals(listOf("Checkout" to "compose"), recordings)
    }

    @Test
    fun `composables record default screen source and remember navigation listener`() =
        runBlocking(ImmediateFrameClock) {
            val recordings = mutableListOf<Pair<String, String>>()
            DebugBundleComposeScreenRecorder.recordScreen = { screenName, source ->
                recordings += screenName to source
            }
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(UnitApplier, recomposer)
            val runner = launch(start = CoroutineStart.UNDISPATCHED) {
                recomposer.runRecomposeAndApplyChanges()
            }
            var listener: DebugBundleNavigationListener? = null

            try {
                composition.setContent {
                    DebugBundleScreen("Checkout")
                    listener = rememberDebugBundleNavigationListener()
                }
                recomposer.awaitIdle()
                withTimeout(1_000) {
                    while (recordings.isEmpty()) {
                        yield()
                    }
                }

                assertEquals(listOf("Checkout" to "compose"), recordings)
                assertNotNull(listener)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.join()
            }
        }

    private object UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit

        override fun insertTopDown(index: Int, instance: Unit) = Unit

        override fun move(from: Int, to: Int, count: Int) = Unit

        override fun onClear() = Unit

        override fun remove(index: Int, count: Int) = Unit
    }

    private object ImmediateFrameClock : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            return onFrame(System.nanoTime())
        }
    }
}
