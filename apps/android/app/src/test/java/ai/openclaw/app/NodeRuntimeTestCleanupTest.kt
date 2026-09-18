package ai.openclaw.app

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class NodeRuntimeTestCleanupTest {
  @Test
  fun drainsCallbacksQueuedBeforePollingAndFinalizersDispatchedFromWorkers() {
    val posted = CompletableDeferred<Unit>()
    Handler(Looper.getMainLooper()).post { posted.complete(Unit) }
    var finalizedOnMain = false
    drainWithMainLooper {
      posted.await()
      withContext(Dispatchers.Main) { finalizedOnMain = Looper.myLooper() == Looper.getMainLooper() }
    }
    assertTrue(finalizedOnMain)
  }

  @Test
  fun immediatelyCompletedCleanupCannotLoseItsOnlyWakeup() {
    repeat(100) { drainWithMainLooper {} }
  }

  @Test
  fun cleanupFailureStillFailsTheTestAfterDraining() {
    val failure =
      assertThrows(IllegalStateException::class.java) {
        drainWithMainLooper { error("cleanup failed") }
      }
    assertEquals("cleanup failed", failure.message)
  }
}
