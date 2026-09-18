package ai.openclaw.app.mcp

import kotlinx.coroutines.flow.StateFlow

/** Immutable MCP connection-management projection. Plugin lifecycle does not enter this state. */
internal data class ConnectionState(
  val connected: Boolean = false,
  val adminScope: Boolean = false,
  val readAvailable: Boolean = false,
  val patchAvailable: Boolean = false,
  val config: GatewayMcpConfigState = GatewayMcpConfigState(),
)

internal data class ConnectionActions(
  val refresh: () -> Unit,
  val addConnector: (McpServerTemplate) -> Unit,
  val addHttpServer: (String, String, McpServerTransport) -> Unit,
  val setEnabled: (String, Boolean) -> Unit,
  val remove: (String) -> Unit,
  val dismissMutation: () -> Unit,
  val reconcileMutation: () -> Unit,
)

internal class ConnectionFeature(
  val state: StateFlow<ConnectionState>,
  val actions: ConnectionActions,
)
