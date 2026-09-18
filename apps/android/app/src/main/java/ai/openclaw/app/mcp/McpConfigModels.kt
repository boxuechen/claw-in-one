package ai.openclaw.app.mcp

import kotlinx.serialization.json.JsonObject

enum class McpServerTransport {
  StreamableHttp,
  Sse,
  Stdio,
  Invalid,
}

data class GatewayMcpServerSummary(
  val name: String,
  val enabled: Boolean,
  val transport: McpServerTransport,
  /** Redacted display target. Command arguments, headers, and environment values are never projected. */
  val target: String,
  val auth: String?,
  val toolFilter: Boolean,
  val parallel: Boolean,
  val tls: String?,
)

data class GatewayMcpConfigSummary(
  val servers: List<GatewayMcpServerSummary> = emptyList(),
)

enum class McpConnectorGroup {
  Work,
  Dev,
  Home,
  Life,
}

enum class McpConnectorFollowUp {
  OAuth,
  Endpoint,
  None,
}

data class McpServerTemplate(
  val serverName: String,
  val url: String,
  val transport: McpServerTransport,
  val auth: String? = null,
  val followUp: McpConnectorFollowUp,
  val docsUrl: String,
)

sealed interface McpConnectorAction {
  data class AddMcp(
    val template: McpServerTemplate,
  ) : McpConnectorAction

  data class SearchClawHub(
    val query: String,
  ) : McpConnectorAction
}

data class McpConnectorSuggestion(
  val id: String,
  val name: String,
  val description: String,
  val group: McpConnectorGroup,
  val action: McpConnectorAction,
)

data class McpConfigSnapshot(
  val hash: String,
  val servers: Map<String, JsonObject>,
)

sealed interface GatewayMcpMutationIntent {
  val serverName: String
  val identity: String
    get() = "mcp:$serverName"

  data class Add(
    override val serverName: String,
    val config: JsonObject,
    val followUp: McpConnectorFollowUp,
  ) : GatewayMcpMutationIntent

  data class SetEnabled(
    override val serverName: String,
    val enabled: Boolean,
  ) : GatewayMcpMutationIntent

  data class Remove(
    override val serverName: String,
  ) : GatewayMcpMutationIntent
}

sealed interface GatewayMcpMutationState {
  data object Idle : GatewayMcpMutationState

  data class Working(
    val intent: GatewayMcpMutationIntent,
  ) : GatewayMcpMutationState

  data class Succeeded(
    val intent: GatewayMcpMutationIntent,
    val message: String,
  ) : GatewayMcpMutationState

  data class Failed(
    val intent: GatewayMcpMutationIntent?,
    val message: String,
  ) : GatewayMcpMutationState

  data class UnknownOutcome(
    val intent: GatewayMcpMutationIntent,
    val message: String,
  ) : GatewayMcpMutationState
}

data class GatewayMcpConfigState(
  val summary: GatewayMcpConfigSummary = GatewayMcpConfigSummary(),
  val refreshing: Boolean = false,
  val errorText: String? = null,
  val mutation: GatewayMcpMutationState = GatewayMcpMutationState.Idle,
)

internal data class McpGatewayEpoch(
  val stableId: String,
  val generation: Long,
  val catalogRevision: Long,
)
