package ai.openclaw.app.chat

import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.permissions.SessionPermissionsController

/**
 * Chat cache and permission transport use different epoch counters. Match the captured Chat
 * connection here; the permission owner checks its own physical connection and canonical SID.
 */
internal suspend fun reconcileStoppedChatPermissions(
  stopped: ChatStopTarget,
  currentChatConnection: () -> ChatCacheScope?,
  permissions: SessionPermissionsController,
): Boolean {
  if (currentChatConnection() != stopped.connection) return false
  val target = SessionPermissionTarget(stopped.connection.gatewayId, stopped.owner.sessionKey, stopped.owner.agentId)
  val ref = permissions.refresh(target).ref ?: return false
  if (currentChatConnection() != stopped.connection || ref.sessionId != stopped.sessionId) return false
  return permissions.reconcileStopped(ref) && currentChatConnection() == stopped.connection
}
