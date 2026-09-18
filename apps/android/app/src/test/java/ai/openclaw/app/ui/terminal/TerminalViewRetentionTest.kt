package ai.openclaw.app.ui.terminal

import ai.openclaw.app.terminal.GatewayControlPage
import ai.openclaw.app.ui.terminal.web.TerminalViewRetention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalViewRetentionTest {
  private val page = GatewayControlPage("http://127.0.0.1:18789", null, null, null)

  @Test fun restorationRetainsOnlyTheSessionIdAcrossRendererLossAndReauthorization() {
    val retention = TerminalViewRetention()
    retention.bind(page)
    assertEquals("", retention.restorationScript())
    retention.lastSessionId = "shell-123"
    retention.browser.rendererGone()
    retention.bind(page.copy(token = "new-credential"))
    val script = retention.restorationScript()
    assertEquals("shell-123", retention.lastSessionId)
    assertTrue(script.contains("openclaw.terminal.sessions.v1"))
    assertTrue(script.contains("JSON.stringify([\"shell-123\"])"))
    assertFalse(script.contains("new-credential"))
    assertFalse(script.contains("input("))
    retention.release()
    assertNull(retention.lastSessionId)
  }

  @Test fun endpointOrTrustChangesForgetThePreviousShell() {
    val retention = TerminalViewRetention()
    retention.bind(page)
    retention.lastSessionId = "old-shell"
    retention.bind(page.copy(baseUrl = "http://127.0.0.1:18790"))
    assertNull(retention.lastSessionId)
    retention.lastSessionId = "other-shell"
    retention.bind(page.copy(baseUrl = "http://127.0.0.1:18790", tlsFingerprintSha256 = "different-trust"))
    assertNull(retention.lastSessionId)
  }
}
