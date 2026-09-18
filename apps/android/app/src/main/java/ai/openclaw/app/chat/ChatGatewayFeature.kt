package ai.openclaw.app.chat

import ai.openclaw.app.GatewayConnectionDisplay
import kotlinx.coroutines.flow.StateFlow

/** One Runtime-bound Gateway context consumed by Chat. No connection or routing ownership. */
internal class ChatGatewayFeature(
  val status: ChatGatewayStatusFeature,
  val scope: ChatGatewayScopeFeature,
  val routing: ChatGatewayRoutingFeature,
)

/** Connectivity and diagnostic endpoint presentation. */
internal class ChatGatewayStatusFeature(
  val connection: StateFlow<GatewayConnectionDisplay>,
  val remoteAddress: StateFlow<String?>,
)

/** Current Gateway identity and capability generation used for admission. */
internal class ChatGatewayScopeFeature(
  val activeStableId: StateFlow<String?>,
  val catalogRevision: StateFlow<Long>,
  val operatorScopes: StateFlow<List<String>>,
)

/** Gateway-provided defaults used to resolve the selected Chat owner. */
internal class ChatGatewayRoutingFeature(
  val mainSessionKey: StateFlow<String>,
  val defaultAgentId: StateFlow<String?>,
)
