package ai.openclaw.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatErrorTextTest {
  @Test
  fun notConnectedErrorUsesProductLanguage() {
    assertEquals(
      "Not connected",
      userFacingChatError(error = "not connected"),
    )
  }

  @Test
  fun authenticationErrorsDoNotExposeGatewayTerminology() {
    assertEquals(
      "Authentication needed",
      userFacingChatError(error = "Gateway unauthorized"),
    )
  }
}
