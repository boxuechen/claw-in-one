package ai.openclaw.app.chat

import kotlinx.coroutines.flow.StateFlow

/** Selected conversation identity only; these flows never authorize a send or device action. */
internal class ChatSelectionFeature(
  val key: StateFlow<String>,
  val ownerAgentId: StateFlow<String?>,
  val generation: StateFlow<Long>,
  val defaultOwner: StateFlow<GatewayDefaultAgentOwner?>,
)
