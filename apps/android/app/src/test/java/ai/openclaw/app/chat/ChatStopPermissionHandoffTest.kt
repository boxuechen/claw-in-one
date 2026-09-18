package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.permissions.SessionPermissionConnection
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.permissions.SessionPermissionsController
import ai.openclaw.app.permissions.SessionPermissionsTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStopPermissionHandoffTest {
  @Test fun independentEpochCountersDoNotPreventAcknowledgedStopRecovery() =
    runTest {
      val chatConnection = ChatCacheScope("phone", 7)
      val permissionConnection = SessionPermissionConnection("phone", 42, 3, setOf("operator.admin"), setOf("sessions.describe", "sessions.patch"))
      var mode = "guarded"
      var describe: () -> Unit = {}
      val permissions =
        SessionPermissionsController(
          this,
          object : SessionPermissionsTransport {
            override fun capture() = permissionConnection

            override fun publish(
              connection: SessionPermissionConnection,
              block: () -> Unit,
            ): Boolean {
              if (connection != permissionConnection) return false
              block()
              return true
            }

            override suspend fun request(
              connection: SessionPermissionConnection,
              method: String,
              params: String,
            ): String {
              if (method == "sessions.patch") {
                mode = "full"
                throw GatewayRequestOutcomeUnknown("saved but ack lost")
              }
              describe()
              return """{"session":{"key":"agent:main:a","sessionId":"session-a","permissionMode":"$mode","permissionModePending":false}}"""
            }
          },
        )
      val target = SessionPermissionTarget("phone", "agent:main:a", "main")
      permissions.refresh(target)
      permissions.choose(target, SessionPermissionMode.Full)
      assertFalse(permissions.state(target).readyToSend)
      val stopped = ChatStopTarget(ChatComposerOwner("phone", "main", target.key), "session-a", chatConnection, emptySet())
      assertTrue(reconcileStoppedChatPermissions(stopped, { chatConnection }, permissions))
      assertTrue(permissions.state(target).readyToSend)

      assertFalse(reconcileStoppedChatPermissions(stopped.copy(sessionId = "replacement"), { chatConnection }, permissions))
      assertFalse(reconcileStoppedChatPermissions(stopped, { chatConnection.copy(connectionGeneration = 8) }, permissions))

      // A Chat reconnect during the permission read cannot be mistaken for the captured Stop.
      var currentChat = chatConnection
      describe = { currentChat = chatConnection.copy(connectionGeneration = 9) }
      assertFalse(reconcileStoppedChatPermissions(stopped, { currentChat }, permissions))
    }
}
