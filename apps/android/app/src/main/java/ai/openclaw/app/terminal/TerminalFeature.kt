package ai.openclaw.app.terminal

import kotlinx.coroutines.flow.StateFlow

/** Trusted HTTP(S) origin plus shared credentials for Gateway-served Control UI pages. */
internal data class GatewayControlPage(
  val baseUrl: String,
  val token: String?,
  val password: String?,
  val tlsFingerprintSha256: String?,
)

internal data class TerminalState(
  val connected: Boolean = false,
  val adminScope: Boolean = false,
  val page: GatewayControlPage? = null,
)

internal data class TerminalActions(
  val reconnect: () -> Unit,
  val prepareAccess: suspend () -> TerminalControlAccess,
)

internal class TerminalFeature(
  val state: StateFlow<TerminalState>,
  val actions: TerminalActions,
)
