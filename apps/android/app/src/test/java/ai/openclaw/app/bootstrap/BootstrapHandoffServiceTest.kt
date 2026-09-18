package ai.openclaw.app.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapHandoffServiceTest {
  @Test
  fun notificationTextCoversEveryProtocolStage() {
    assertEquals(
      SupervisorProgress.entries.size,
      SupervisorProgress.entries
        .map(::bootstrapNotificationText)
        .distinct()
        .size,
    )
  }
}
