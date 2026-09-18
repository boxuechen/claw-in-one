package ai.openclaw.app.androiduse

import ai.openclaw.app.chat.ChatCacheScope
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatStopTarget
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.ownership.taskOwnerKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidUseChatStopHandoffTest {
  private val nodeConnection = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") }

  @Test fun exactChatAndPhysicalConnectionFenceNativeStop() =
    runTest {
      val controls = AndroidUseLeaseController(backgroundScope, consentGranted = { true }, clock = { testScheduler.currentTime })
      controls.setStopSurfaceAvailable(true)
      val owner = ChatComposerOwner("phone", "main", "agent:main:a")
      var connection = ChatCacheScope("phone", 8)
      val target = ChatStopTarget(owner, "session-a", connection, setOf("run-a"))
      val identity =
        AndroidUseLeaseIdentity(
          "11111111-1111-4111-8111-111111111111",
          taskOwnerKey(owner.sessionKey),
          "com.example.fixture",
          AndroidUseExecutionIdentity("session-a", "run-a", "generation-1"),
        )
      assertTrue(controls.acquire(nodeConnection, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      var nodeCurrent = true
      var nodeEndpoint = "phone"
      val handoff =
        AndroidUseChatStopHandoff({ connection }, { expected ->
          assertTrue(expected == "phone")
          GatewaySession.RequestLease(nodeEndpoint, isCurrentImpl = { nodeCurrent }) { _, _, _, _ -> error("Stop handoff must not issue RPC") }
        }, controls)
      nodeCurrent = false
      assertFalse(handoff.revoke(target))
      nodeCurrent = true
      nodeEndpoint = "other-gateway"
      assertFalse(handoff.revoke(target))
      nodeEndpoint = "phone"
      connection = connection.copy(connectionGeneration = 9)
      assertFalse(handoff.revoke(target))
      connection = target.connection
      assertTrue(controls.authorize(nodeConnection, identity) is AndroidUseLeaseDecision.Allowed)
      assertTrue(handoff.revoke(target.copy(owner = owner.copy(sessionKey = "agent:main:b"))))
      assertTrue(controls.authorize(nodeConnection, identity) is AndroidUseLeaseDecision.Allowed)
      assertFalse(handoff.revoke(target.copy(runIds = emptySet())))
      assertTrue(handoff.revoke(target))
      assertTrue(controls.state.value is AndroidUseControlState.Inactive)
      assertTrue(controls.acquire(nodeConnection, null, identity.copy(controlId = "22222222-2222-4222-8222-222222222222"), identity.targetPackage) is AndroidUseLeaseDecision.Rejected)
    }
}
