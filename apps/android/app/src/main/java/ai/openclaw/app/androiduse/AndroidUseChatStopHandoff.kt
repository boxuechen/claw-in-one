package ai.openclaw.app.androiduse

import ai.openclaw.app.chat.ChatCacheScope
import ai.openclaw.app.chat.ChatStopTarget
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.ownership.taskOwnerKey

/** Typed wiring only. Chat, the Node connection, and native execution keep their own epochs/state. */
internal class AndroidUseChatStopHandoff(
  private val currentChatConnection: () -> ChatCacheScope?,
  private val captureNodeLease: (String) -> GatewaySession.RequestLease?,
  private val controls: AndroidUseLeaseController,
) {
  fun revoke(target: ChatStopTarget): Boolean {
    if (currentChatConnection() != target.connection ||
      target.owner.gatewayStableId != target.connection.gatewayId ||
      !target.owner.sessionKey.startsWith("agent:${target.owner.agentId}:")
    ) {
      return false
    }
    val node = captureNodeLease(target.connection.gatewayId) ?: return false
    if (node.endpointStableId != target.connection.gatewayId || !node.isCurrent()) return false
    val revoked = controls.revokeRuns(taskOwnerKey(target.owner.sessionKey), target.sessionId, target.runIds)
    return revoked && currentChatConnection() == target.connection && node.isCurrent()
  }
}
