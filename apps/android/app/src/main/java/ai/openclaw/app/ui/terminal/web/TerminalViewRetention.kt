package ai.openclaw.app.ui.terminal.web

import ai.openclaw.app.terminal.GatewayControlPage
import ai.openclaw.app.ui.web.ControlUiWebViewRetention
import org.json.JSONObject

/** Shell-owned presentation lifetime. Stores no commands, output, or credentials. */
internal class TerminalViewRetention {
  val browser = ControlUiWebViewRetention()
  var lastSessionId: String? = null
  private var endpoint: Pair<String, String?>? = null

  fun bind(page: GatewayControlPage) {
    val next = page.baseUrl to page.tlsFingerprintSha256
    if (endpoint != null && endpoint != next) release()
    endpoint = next
  }

  fun hasPage(page: GatewayControlPage): Boolean = browser.hasPage(page)

  fun release() {
    browser.release()
    lastSessionId = null
    endpoint = null
  }

  /** Only the upstream session identifier crosses renderer recreation, never terminal bytes. */
  fun restorationScript(): String {
    val id = lastSessionId ?: return ""
    return "window.__clawPreviousSession=${JSONObject.quote(id)};" +
      "sessionStorage.setItem('openclaw.terminal.sessions.v1',JSON.stringify([${JSONObject.quote(id)}]));"
  }
}
