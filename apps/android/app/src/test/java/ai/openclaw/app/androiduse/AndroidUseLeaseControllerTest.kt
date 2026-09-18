package ai.openclaw.app.androiduse

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.node.MobileUiTarget
import ai.openclaw.app.vscreen.VScreenTargetCandidate
import ai.openclaw.app.vscreen.VScreenTargetRegistry
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidUseLeaseControllerTest {
  private val nodeConnection = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") }

  @Test
  fun savedConsentGatesAcquisitionAndContinuationWithoutRevivingOldControl() =
    runTest {
      var enabled = false
      val controller = AndroidUseLeaseController(this, consentGranted = { enabled }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val identity = identity()
      assertRejected("ANDROID_USE_DISABLED", controller.acquire(nodeConnection, null, identity, identity.targetPackage))
      enabled = true
      assertTrue(controller.acquire(nodeConnection, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      val execution = kotlinx.coroutines.Job()
      assertTrue(controller.attachExecution(nodeConnection, identity, execution))
      enabled = false
      assertRejected("ANDROID_USE_DISABLED", controller.authorize(nodeConnection, identity))
      assertTrue(execution.isCancelled)
      enabled = true
      assertRejected("CONTROL_INACTIVE", controller.authorize(nodeConnection, identity))
      assertRejected("CONTROL_REPLAYED", controller.acquire(nodeConnection, null, identity, identity.targetPackage))
      val fresh = identity.copy(controlId = "fresh", execution = identity.execution.copy(runId = "fresh"))
      assertTrue(controller.acquire(nodeConnection, null, fresh, fresh.targetPackage) is AndroidUseLeaseDecision.Allowed)
    }

  @Test
  fun externalHandoffGateRevokesOnlyItsRunAndHoldsAdmissionUntilUserLaunch() =
    runTest {
      var handoffActive = false
      val controller =
        AndroidUseLeaseController(
          this,
          consentGranted = { true },
          clock = { testScheduler.currentTime },
          handoffBlocked = { handoffActive },
        )
      controller.setStopSurfaceAvailable(true)
      val identity = identity()
      assertTrue(controller.acquire(nodeConnection, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      handoffActive = true
      assertFalse(controller.revokeForHandoff(identity.ownerKey, identity.execution.sessionId, "unrelated"))
      assertTrue(controller.authorize(nodeConnection, identity) is AndroidUseLeaseDecision.Allowed)
      assertTrue(controller.revokeForHandoff(identity.ownerKey, identity.execution.sessionId, identity.execution.runId))
      assertRejected("CONTROL_INACTIVE", controller.authorize(nodeConnection, identity))
      val next = identity.copy(controlId = "next", execution = identity.execution.copy(runId = "next"))
      assertRejected("CONTROL_BUSY", controller.acquire(nodeConnection, null, next, next.targetPackage))
      handoffActive = false
      assertRejected("RUN_STOPPED", controller.acquire(nodeConnection, null, identity, identity.targetPackage))
      assertTrue(controller.acquire(nodeConnection, null, next, next.targetPackage) is AndroidUseLeaseDecision.Allowed)
    }

  @Test
  fun successfulRunReleaseRetiresAuthorityWithoutARevocationReason() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val identity = identity()
      assertTrue(controller.acquire(nodeConnection, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      val execution = kotlinx.coroutines.Job()
      assertTrue(controller.attachExecution(nodeConnection, identity, execution))

      assertTrue(controller.releaseControl(identity))

      assertTrue(execution.isCancelled)
      assertEquals(null, (controller.state.value as AndroidUseControlState.Inactive).lastRevocation)
      assertRejected("CONTROL_INACTIVE", controller.authorize(nodeConnection, identity))
      val replay = identity.copy(controlId = "22222222-2222-4222-8222-222222222222")
      assertRejected("RUN_STOPPED", controller.acquire(nodeConnection, null, replay, replay.targetPackage))
      assertFalse(controller.releaseControl(identity))
    }

  @Test
  fun retiredPhysicalConnectionCannotAcquireOrBorrowReplacementControl() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      var ready = true
      val old = GatewaySession.RequestLease("gateway", isCurrentImpl = { ready }) { _, _, _, _ -> error("No RPC expected") }
      val identity = identity()
      assertTrue(controller.acquire(old, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      ready = false
      assertRejected("CONTROL_INACTIVE", controller.authorize(old, identity))
      assertRejected("GATEWAY_DISCONNECTED", controller.acquire(old, null, identity, identity.targetPackage))

      val next = identity.copy(controlId = "22222222-2222-4222-8222-222222222222")
      assertTrue(controller.acquire(nodeConnection, null, next, next.targetPackage) is AndroidUseLeaseDecision.Allowed)
      assertFalse(controller.invalidateConnection(old))
      assertRejected("CONTROL_INACTIVE", controller.authorize(old, next))
      assertTrue(controller.authorize(nodeConnection, next) is AndroidUseLeaseDecision.Allowed)
      assertTrue(controller.invalidateConnection(nodeConnection))
    }

  @Test
  fun operatorRetirementOnlyCancelsItsCapturedExecution() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val operator = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") }
      val unrelated = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") }
      val identity = identity()
      assertTrue(controller.acquire(nodeConnection, operator, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      val execution = kotlinx.coroutines.Job()
      assertTrue(controller.attachExecution(nodeConnection, identity, execution))
      assertFalse(controller.invalidateConnection(unrelated))
      assertTrue(execution.isActive)
      assertTrue(controller.invalidateConnection(operator))
      assertTrue(execution.isCancelled)
      assertFalse(controller.invalidateConnection(operator))
    }

  @Test
  fun executionActivityTracksOnlyTheAttachedJob() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val identity = identity()
      assertTrue(controller.acquire(nodeConnection, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      assertEquals(AndroidUseActivity.Waiting, (controller.state.value as AndroidUseControlState.Active).activity)

      val execution = kotlinx.coroutines.Job()
      val unrelated = kotlinx.coroutines.Job()
      assertTrue(controller.attachExecution(nodeConnection, identity, execution))
      assertEquals(AndroidUseActivity.Executing, (controller.state.value as AndroidUseControlState.Active).activity)
      controller.detachExecution(unrelated)
      assertEquals(AndroidUseActivity.Executing, (controller.state.value as AndroidUseControlState.Active).activity)
      controller.detachExecution(execution)
      assertEquals(AndroidUseActivity.Waiting, (controller.state.value as AndroidUseControlState.Active).activity)
    }

  @Test
  fun operatorLossIsCheckedBeforeDelayedRetirementNotification() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      var ready = true
      val operator = GatewaySession.RequestLease("gateway", isCurrentImpl = { ready }) { _, _, _, _ -> error("No RPC expected") }
      val identity = identity()
      assertTrue(controller.acquire(nodeConnection, operator, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)
      ready = false
      assertRejected("CONTROL_INACTIVE", controller.authorize(nodeConnection, identity))
      assertEquals(AndroidUseControlState.Inactive(AndroidUseRevocation.GatewayDisconnected), controller.state.value)
      val next = identity.copy(controlId = "22222222-2222-4222-8222-222222222222")
      assertRejected("GATEWAY_DISCONNECTED", controller.acquire(nodeConnection, operator, next, next.targetPackage))
    }

  @Test
  fun `requires reachable stop and exact foreground target`() =
    runTest {
      val controller = controller()
      val identity = identity()

      assertRejected("STOP_SURFACE_UNAVAILABLE", controller.acquire(nodeConnection, null, identity, identity.targetPackage))
      controller.setStopSurfaceAvailable(true)
      assertRejected("TARGET_NOT_FOREGROUND", controller.acquire(nodeConnection, null, identity, "com.example.other"))
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
    }

  @Test
  fun `one lease excludes competing chats and binds every credential`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val first = identity()
      val competing =
        identity(
          controlId = "22222222-2222-4222-8222-222222222222",
          ownerKey = "d".repeat(64),
        )

      assertTrue(controller.acquire(nodeConnection, null, first, first.targetPackage) is AndroidUseLeaseDecision.Allowed)
      assertRejected("CONTROL_BUSY", controller.acquire(nodeConnection, null, competing, competing.targetPackage))
      assertRejected("CONTROL_OWNER_MISMATCH", controller.authorize(nodeConnection, first.copy(ownerKey = "f".repeat(64))))
      assertTrue(controller.authorize(nodeConnection, first) is AndroidUseLeaseDecision.Allowed)
    }

  @Test
  fun `stop revokes immediately and consumed control ID cannot replay`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val identity = identity()
      assertTrue(controller.acquire(nodeConnection, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)

      assertTrue(controller.revoke(AndroidUseRevocation.UserStop))
      assertFalse(controller.revoke(AndroidUseRevocation.UserStop))
      assertEquals(
        AndroidUseRevocation.UserStop,
        (controller.state.value as AndroidUseControlState.Inactive).lastRevocation,
      )
      assertRejected("CONTROL_REPLAYED", controller.acquire(nodeConnection, null, identity, identity.targetPackage))
    }

  @Test
  fun `host revoke binds session run and execution generation`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val own = identity()
      assertTrue(controller.acquire(nodeConnection, null, own, own.targetPackage) is AndroidUseLeaseDecision.Allowed)

      for (execution in listOf(
        own.execution.copy(sessionId = "another-session"),
        own.execution.copy(runId = "another-run"),
        own.execution.copy(generation = "another-generation"),
      )) {
        assertFalse(controller.revokeControl(own.copy(execution = execution), AndroidUseRevocation.HostRevoked))
        assertTrue(controller.authorize(nodeConnection, own) is AndroidUseLeaseDecision.Allowed)
      }
      assertTrue(controller.revokeControl(own, AndroidUseRevocation.HostRevoked))
      assertFalse(controller.revokeControl(own, AndroidUseRevocation.HostRevoked))
      assertRejected("CONTROL_REPLAYED", controller.acquire(nodeConnection, null, own, own.targetPackage))
    }

  @Test
  fun `revoke arriving before acquisition prevents delayed work`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val own = identity()
      assertFalse(controller.revokeControl(own, AndroidUseRevocation.HostRevoked))
      assertRejected("CONTROL_REPLAYED", controller.acquire(nodeConnection, null, own, own.targetPackage))
    }

  @Test
  fun `lease expires without another command`() =
    runTest {
      var elapsed = 0L
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { elapsed }, leaseDurationMs = 1_000)
      controller.setStopSurfaceAvailable(true)
      val identity = identity()
      assertTrue(controller.acquire(nodeConnection, null, identity, identity.targetPackage) is AndroidUseLeaseDecision.Allowed)

      elapsed = 1_000
      advanceTimeBy(1_000)
      runCurrent()

      assertEquals(
        AndroidUseRevocation.Expired,
        (controller.state.value as AndroidUseControlState.Inactive).lastRevocation,
      )
      assertRejected("CONTROL_INACTIVE", controller.authorize(nodeConnection, identity))
    }

  @Test
  fun `losing stop surface revokes active authority`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      assertTrue(controller.acquire(nodeConnection, null, identity(), "com.example.fixture") is AndroidUseLeaseDecision.Allowed)

      controller.setStopSurfaceAvailable(false)

      assertEquals(
        AndroidUseRevocation.StopSurfaceUnavailable,
        (controller.state.value as AndroidUseControlState.Inactive).lastRevocation,
      )
    }

  @Test
  fun `foreground package change revokes active authority`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      assertTrue(controller.acquire(nodeConnection, null, identity(), "com.example.fixture") is AndroidUseLeaseDecision.Allowed)

      assertFalse(controller.revokeIfTargetChanged(null))
      assertFalse(controller.revokeIfTargetChanged("com.example.fixture"))
      assertTrue(controller.revokeIfTargetChanged("com.example.other"))

      assertEquals(
        AndroidUseRevocation.TargetChanged,
        (controller.state.value as AndroidUseControlState.Inactive).lastRevocation,
      )
    }

  private fun kotlinx.coroutines.test.TestScope.controller(): AndroidUseLeaseController = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })

  @Test fun `chat stop retires exact runs without cancelling another owner or new run`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val own = identity()
      assertTrue(controller.acquire(nodeConnection, null, own, own.targetPackage) is AndroidUseLeaseDecision.Allowed)
      assertTrue(controller.revokeRuns("b".repeat(64), "session-a", setOf("run-a")))
      assertTrue(controller.revokeRuns(own.ownerKey, "other-session", setOf("run-a")))
      assertFalse(controller.revokeRuns(own.ownerKey, "session-a", setOf("other-run")))
      assertFalse(controller.revokeRuns(own.ownerKey, "session-a", emptySet()))
      assertTrue(controller.authorize(nodeConnection, own) is AndroidUseLeaseDecision.Allowed)
      assertTrue(controller.revokeRuns(own.ownerKey, "session-a", setOf("run-a")))
      val delayed = own.copy(controlId = "22222222-2222-4222-8222-222222222222")
      assertRejected("RUN_STOPPED", controller.acquire(nodeConnection, null, delayed, own.targetPackage))
      val fresh = delayed.copy(execution = own.execution.copy(runId = "fresh-run"))
      assertTrue(controller.acquire(nodeConnection, null, fresh, own.targetPackage) is AndroidUseLeaseDecision.Allowed)
      controller.revoke(AndroidUseRevocation.UserStop)
    }

  @Test fun `VScreen retirement and human input match only the exact target generation`() =
    runTest {
      val controller = controller()
      controller.setStopSurfaceAvailable(true)
      val registry = VScreenTargetRegistry { controller.revokeVScreen(it) }
      val own = identity()
      val candidate =
        VScreenTargetCandidate(
          producerId = "claw-in-one-android-vscreen-producer",
          workloadRequestId = "92345678-1234-4123-8123-123456789abc",
          attachmentId = "82345678-1234-4123-8123-123456789abc",
          targetRef = "opaque-target-1",
          targetGeneration = 1,
          sourceGeneration = 1,
          displayId = 7,
          width = 720,
          height = 1280,
          dpi = 320,
        )
      assertTrue(controller.acquire(nodeConnection, null, own, own.targetPackage) is AndroidUseLeaseDecision.Allowed)
      registry.publish(candidate)
      registry.clear(candidate.attachmentId, candidate.targetGeneration)
      assertTrue(controller.authorize(nodeConnection, own) is AndroidUseLeaseDecision.Allowed)
      controller.revoke(AndroidUseRevocation.UserStop)

      val binding = registry.publish(candidate)
      val vscreen =
        own.copy(
          controlId = "22222222-2222-4222-8222-222222222222",
          display = AndroidUseDisplay.VScreen,
          assignmentId = "92345678-1234-4123-8123-123456789abc",
          target =
            MobileUiTarget.VScreenDisplay(
              own.targetPackage,
              binding.displayId,
              binding.attachmentId,
              binding.targetGeneration,
              binding.revision,
            ),
        )
      assertTrue(controller.acquire(nodeConnection, null, vscreen, null) is AndroidUseLeaseDecision.Allowed)
      assertFalse(controller.revokeVScreen(binding.copy(targetGeneration = 2)))
      assertFalse(controller.revokeVScreen(binding.copy(revision = binding.revision - 1)))
      val replacement =
        registry.publish(
          candidate.copy(
            attachmentId = "82345678-1234-4123-8123-123456789abd",
            targetRef = "opaque-target-2",
            targetGeneration = 2,
            sourceGeneration = 2,
            displayId = 8,
          ),
        )
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
      val next =
        vscreen.copy(
          controlId = "33333333-3333-4333-8333-333333333333",
          target =
            (vscreen.target as MobileUiTarget.VScreenDisplay).copy(
              attachmentId = replacement.attachmentId,
              targetGeneration = replacement.targetGeneration,
              displayId = 8,
              bindingRevision = replacement.revision,
            ),
        )
      assertTrue(controller.acquire(nodeConnection, null, next, null) is AndroidUseLeaseDecision.Allowed)
      assertFalse(controller.revokeVScreen(binding))
      assertTrue(controller.authorize(nodeConnection, next) is AndroidUseLeaseDecision.Allowed)
      assertFalse(controller.revokeForHumanInput(binding.attachmentId, binding.targetGeneration))
      registry.clear(binding.attachmentId, binding.targetGeneration)
      assertTrue(controller.authorize(nodeConnection, next) is AndroidUseLeaseDecision.Allowed)
      assertTrue(controller.revokeForHumanInput(replacement.attachmentId, replacement.targetGeneration))
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
      assertEquals(
        AndroidUseRevocation.HumanInput,
        (controller.state.value as AndroidUseControlState.Inactive).lastRevocation,
      )
    }

  private fun identity(
    controlId: String = "11111111-1111-4111-8111-111111111111",
    ownerKey: String = "a".repeat(64),
  ) = AndroidUseLeaseIdentity(
    controlId = controlId,
    ownerKey = ownerKey,
    targetPackage = "com.example.fixture",
    execution = AndroidUseExecutionIdentity("session-a", "run-a", "generation-a"),
  )

  private fun assertRejected(
    code: String,
    decision: AndroidUseLeaseDecision,
  ) {
    assertTrue(decision is AndroidUseLeaseDecision.Rejected)
    assertEquals(code, (decision as AndroidUseLeaseDecision.Rejected).code)
  }
}
