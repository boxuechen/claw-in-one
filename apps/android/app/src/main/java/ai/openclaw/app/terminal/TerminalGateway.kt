package ai.openclaw.app.terminal

internal data class TerminalGatewayConnection(
  val stableId: String,
  val generation: Long,
)

/** Narrow request seam for the terminal's one-time browser authorization. */
internal interface TerminalGatewayTransport {
  fun capture(): TerminalGatewayConnection?

  fun hasAdminScope(): Boolean

  suspend fun requestSetupCode(connection: TerminalGatewayConnection): String
}
