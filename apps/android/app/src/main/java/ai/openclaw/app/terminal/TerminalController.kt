package ai.openclaw.app.terminal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Owns the terminal page identity and ephemeral browser-authorization handoff. */
internal class TerminalController(
  scope: CoroutineScope,
  connected: StateFlow<Boolean>,
  adminScope: StateFlow<Boolean>,
  private val transport: TerminalGatewayTransport,
  reconnect: () -> Unit,
) {
  private val mutablePage = MutableStateFlow<GatewayControlPage?>(null)
  internal val page: StateFlow<GatewayControlPage?> = mutablePage

  val state: StateFlow<TerminalState> =
    combine(connected, adminScope, mutablePage) { isConnected, admin, page ->
      TerminalState(
        connected = isConnected,
        adminScope = admin,
        page = page.takeIf { isConnected },
      )
    }.stateIn(
      scope,
      SharingStarted.Eagerly,
      TerminalState(connected.value, adminScope.value, mutablePage.value.takeIf { connected.value }),
    )

  val feature =
    TerminalFeature(
      state = state,
      actions =
        TerminalActions(
          reconnect = reconnect,
          prepareAccess = ::prepareAccess,
        ),
    )

  fun replacePage(page: GatewayControlPage?) {
    mutablePage.value = page
  }

  private suspend fun prepareAccess(): TerminalControlAccess {
    val connection = transport.capture() ?: error("Gateway unavailable")
    val page = mutablePage.value ?: error("Control page unavailable")
    if (page.token != null || page.password != null) return TerminalControlAccess(page)
    check(transport.hasAdminScope()) { "Terminal requires full access" }
    val response = transport.requestSetupCode(connection)
    if (transport.capture() != connection || mutablePage.value != page) {
      throw CancellationException("terminal connection changed")
    }
    return parseTerminalControlAccess(response, page)
  }
}
