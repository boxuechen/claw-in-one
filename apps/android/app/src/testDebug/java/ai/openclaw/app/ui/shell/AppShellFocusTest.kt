package ai.openclaw.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppShellFocusTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun appStopRequestsOneFocusReleaseAndResumeDoesNotRequestAnother() {
    val lifecycleOwner = TestLifecycleOwner()
    var releaseCount = 0
    composeRule.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        BackgroundInputFocusEffect { releaseCount += 1 }
      }
    }

    composeRule.runOnIdle { lifecycleOwner.background() }
    assertEquals(1, releaseCount)

    composeRule.runOnIdle { lifecycleOwner.resume() }
    assertEquals(1, releaseCount)
  }

  private class TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }

    override val lifecycle: Lifecycle = registry

    fun background() {
      registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun resume() {
      registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
      registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
  }
}
