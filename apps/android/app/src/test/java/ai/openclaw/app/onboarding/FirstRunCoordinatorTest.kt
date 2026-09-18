package ai.openclaw.app.onboarding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunCoordinatorTest {
  @Test
  fun failedHandoffRetryPublishesReplacementBeforeRestartingMonitor() =
    runTest {
      val events = mutableListOf<String>()

      retryBootstrapHandoff(
        republish = {
          events += "discarded"
          events += "published"
        },
        resetMonitor = { events += "monitor-reset" },
      )

      assertEquals(listOf("discarded", "published", "monitor-reset"), events)
    }
}
