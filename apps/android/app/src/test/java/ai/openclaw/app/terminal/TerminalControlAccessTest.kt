package ai.openclaw.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class TerminalControlAccessTest {
  private val page = GatewayControlPage("https://127.0.0.1:18789", null, null, "trusted-pin")

  @Test
  fun browserHandoffPreservesNativeRouteAndTrustWithoutReusingDeviceAuth() {
    val code = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"url":"wss://192.0.2.1:18789","bootstrapToken":"ephemeral-credential"}""".toByteArray())
    val result = parseTerminalControlAccess("""{"setupCode":"$code"}""", page)
    assertEquals(page, result.page)
    assertEquals("ephemeral-credential", result.bootstrapToken)
    assertNull(result.page.token)
    assertFalse(result.toString().contains("ephemeral-credential"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun credentialFreeHandoffIsRejected() {
    val code = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"url":"wss://192.0.2.1:18789"}""".toByteArray())
    parseTerminalControlAccess("""{"setupCode":"$code"}""", page)
  }
}
