package ai.openclaw.app.runtime

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.GatewayConnectionProblem
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilityUnknownReason
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.ComponentKey
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorCommand
import ai.openclaw.app.supervisor.SupervisorControlError
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.requiredSetupComponents
import ai.openclaw.app.supervisor.resolveComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateTest {
  private val now = 1_788_400_000L
  private val planId = "c".repeat(32)
  private val bootId = "d".repeat(32)
  private val generation = "e".repeat(32)

  @Test
  fun coldStartWaitsForExplicitEntryEvenWhenTheGenerationWasPreviouslyAcknowledged() {
    val acknowledged = RuntimeGeneration(bootId, generation)
    val state = resolve(statusState(SupervisorStatusStage.GatewayReady), connected = true, acknowledged = acknowledged)

    assertEquals(RuntimeGatePresentation.Startup, state.gate.presentation)
    assertEquals(RuntimeGatePhase.ReadyToEnter, state.gate.phase)
    assertEquals(RuntimeGateStep.Ready, state.gate.step)
    assertEquals(RuntimeGeneration(bootId, generation), state.gate.generation)
  }

  @Test
  fun foregroundBackgroundWithTheSameLiveGenerationDoesNotShowRecovery() {
    val acknowledged = RuntimeGeneration(bootId, generation)
    val first = resolve(statusState(SupervisorStatusStage.GatewayReady), connected = true, acknowledged = acknowledged, sessionEntered = true)
    val afterTerminalActivity = resolve(statusState(SupervisorStatusStage.GatewayReady), connected = true, acknowledged = acknowledged, sessionEntered = true)

    assertEquals(RuntimeGatePresentation.Hidden, first.gate.presentation)
    assertEquals(first, afterTerminalActivity)
    assertTrue(RuntimeAction.RepairCapabilityPlan in first.actions)
  }

  @Test
  fun transientDisconnectUsesNoticeThenEscalatesToLocalPortRecovery() {
    val acknowledged = RuntimeGeneration(bootId, generation)
    val transient = resolve(statusState(SupervisorStatusStage.GatewayReady), acknowledged = acknowledged, sessionEntered = true, unhealthySeconds = 9)
    val durable = resolve(statusState(SupervisorStatusStage.GatewayReady), acknowledged = acknowledged, sessionEntered = true, unhealthySeconds = 10)

    assertEquals(RuntimeGatePresentation.TransientNotice, transient.gate.presentation)
    assertEquals(RuntimeGatePhase.ConnectingLocalPort, transient.gate.phase)
    assertEquals(RuntimeGateStep.AppConnection, transient.gate.step)
    assertEquals(RuntimeGatePresentation.Recovery, durable.gate.presentation)
    assertEquals(RuntimeGateIssue.LocalPortUnavailable, durable.gate.issue)
    assertEquals(RuntimeGateStep.AppConnection, durable.gate.step)
    assertTrue(RuntimeAction.Reconnect in durable.actions)
  }

  @Test
  fun aNewSupervisorOrGatewayGenerationRequiresResumeConfirmation() {
    val previous = RuntimeGeneration("f".repeat(32), "a".repeat(32))
    val state = resolve(statusState(SupervisorStatusStage.GatewayReady), connected = true, acknowledged = previous, sessionEntered = true)

    assertEquals(RuntimeGatePresentation.Recovery, state.gate.presentation)
    assertEquals(RuntimeGatePhase.ReadyToResume, state.gate.phase)
    assertEquals(RuntimeGateStep.Ready, state.gate.step)
  }

  @Test
  fun plannedGatewayRestartOwnsTheInterruptionInsteadOfGenericRecovery() {
    val acknowledged = RuntimeGeneration(bootId, generation)
    val state =
      resolveRuntimeState(
        supervisor = statusState(SupervisorStatusStage.GatewayReady),
        monitoring = false,
        gateway = GatewayConnectionDisplay(false, "Offline", null),
        gatewayVersion = null,
        acknowledgedGeneration = acknowledged,
        sessionEntered = true,
        nowEpochSeconds = now,
        unhealthyDurationSeconds = 30,
        deviceEligibility = DeviceEligibility.Ready,
        plannedGatewayRestart = true,
      )

    assertEquals(RuntimeGatePresentation.Recovery, state.gate.presentation)
    assertEquals(RuntimeGatePhase.PlannedGatewayRestart, state.gate.phase)
    assertEquals(RuntimeGateStep.Gateway, state.gate.step)
    assertNull(state.gate.issue)
  }

  @Test
  fun supervisorFailureIsDistinctFromAHealthyGatewaySnapshot() {
    val state = resolve(SupervisorControlState.Failed(SupervisorControlError.TimedOut), connected = true)

    assertTrue(state.gatewayConnected)
    assertEquals(LocalServiceState.Unreachable, state.localService)
    assertEquals(RuntimeGateIssue.SupervisorUnavailable, state.gate.issue)
    assertEquals(RuntimeGateStep.Supervisor, state.gate.step)
    assertFalse(RuntimeAction.EnsureGateway in state.actions)
  }

  @Test
  fun structuredPairingProblemNeverDependsOnDisplayText() {
    val problem =
      GatewayConnectionProblem(
        code = "PAIRING_REQUIRED",
        message = "opaque",
        reason = null,
        requestId = null,
        recommendedNextStep = null,
        pauseReconnect = true,
        retryable = false,
      )
    val state =
      resolveRuntimeState(
        supervisor = statusState(SupervisorStatusStage.GatewayReady),
        monitoring = false,
        gateway = GatewayConnectionDisplay(false, "any localized copy", problem),
        gatewayVersion = null,
        acknowledgedGeneration = RuntimeGeneration(bootId, generation),
        sessionEntered = true,
        nowEpochSeconds = now,
        unhealthyDurationSeconds = 1,
        deviceEligibility = DeviceEligibility.Ready,
      )

    assertEquals(RuntimeGateIssue.PairingRequired, state.gate.issue)
    assertEquals(RuntimeGateStep.AppConnection, state.gate.step)
    assertTrue(RuntimeAction.RequestGatewayPairing in state.actions)
  }

  @Test
  fun waitingKeepsThePreviousProgressButSuppressesMutatingActions() {
    val previous = status(SupervisorStatusStage.CapabilitiesReady)
    val state =
      resolve(
        SupervisorControlState.Waiting(SupervisorCommand.EnsureGateway, 2, previous),
        monitoring = true,
      )

    assertEquals(LocalServiceState.Responding, state.localService)
    assertFalse(RuntimeAction.EnsureGateway in state.actions)
    assertFalse(RuntimeAction.Refresh in state.actions)
  }

  @Test
  fun failedOptionalCapabilityExposesOnlyExistingPlanRecovery() {
    val selected = listOf(DevelopmentCapability.AndroidKotlin, DevelopmentCapability.Flutter)
    val failed =
      status(SupervisorStatusStage.CapabilityFailed).copy(
        selectedCapabilities = selected,
        resolvedComponents = resolveComponents(selected),
        currentComponent = CapabilityComponent.Flutter,
        exitCode = 1,
      )
    val state = resolve(SupervisorControlState.Status(failed))

    assertTrue(RuntimeAction.RetryCapabilityPlan in state.actions)
    assertTrue(RuntimeAction.SkipOptionalCapability in state.actions)
    assertEquals(planId, state.setup?.planId)
  }

  @Test
  fun failedRequiredAssetStaysOwnerScopedWhileTheAcknowledgedGatewayIsLive() {
    val chromium = requiredSetupComponents().first { it.key == ComponentKey.ChromiumRuntime }
    val failed =
      status(SupervisorStatusStage.CapabilityFailed).copy(
        currentComponent = chromium,
        exitCode = 1,
      )
    val state =
      resolve(
        SupervisorControlState.Status(failed),
        connected = true,
        acknowledged = RuntimeGeneration(bootId, generation),
        sessionEntered = true,
        unhealthySeconds = 30,
      )

    assertEquals(RuntimeGatePresentation.Hidden, state.gate.presentation)
    assertEquals(RuntimeGatePhase.Healthy, state.gate.phase)
    assertEquals(generation, state.gatewayGeneration)
    assertEquals(chromium, state.setup?.currentComponent)
    assertTrue(RuntimeAction.RetryCapabilityPlan in state.actions)
    assertFalse(RuntimeAction.SkipOptionalCapability in state.actions)
  }

  @Test
  fun safeProjectionNeverCarriesTheGatewaySetupCode() {
    val pairing = status(SupervisorStatusStage.GatewayPairingReady).copy(setupCode = "private_setup_credential")
    val state = resolve(SupervisorControlState.Status(pairing), connected = true)

    assertFalse(state.toString().contains("private_setup_credential"))
    assertNull(
      state.setup
        ?.javaClass
        ?.declaredFields
        ?.firstOrNull { it.name == "setupCode" },
    )
  }

  @Test
  fun completedUserGetsRuntimeRecoveryInsteadOfOnboardingWhenLinuxIsDisabled() {
    val state =
      resolve(
        statusState(SupervisorStatusStage.GatewayReady),
        connected = true,
        sessionEntered = true,
        deviceEligibility =
          DeviceEligibility.OnboardingResolvable(
            DevicePreparationRequirement.EnableLinuxEnvironment,
          ),
      )

    assertEquals(RuntimeGatePresentation.Recovery, state.gate.presentation)
    assertEquals(
      RuntimeGateIssue.DevicePreparation(DevicePreparationRequirement.EnableLinuxEnvironment),
      state.gate.issue,
    )
    assertEquals(setOf(RuntimeAction.OpenDeveloperSettings), state.actions)
  }

  @Test
  fun unknownDeviceEvidenceOffersOnlyAReadOnlyRefresh() {
    val state =
      resolve(
        SupervisorControlState.Stopped,
        deviceEligibility =
          DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.EvidenceUnavailable),
      )

    assertEquals(RuntimeGatePresentation.Startup, state.gate.presentation)
    assertEquals(setOf(RuntimeAction.RefreshDeviceEligibility), state.actions)
  }

  private fun resolve(
    state: SupervisorControlState,
    connected: Boolean = false,
    monitoring: Boolean = false,
    acknowledged: RuntimeGeneration? = null,
    sessionEntered: Boolean = false,
    unhealthySeconds: Long = 0,
    deviceEligibility: DeviceEligibility = DeviceEligibility.Ready,
  ) = resolveRuntimeState(
    state,
    monitoring,
    GatewayConnectionDisplay(connected, if (connected) "Connected" else "Offline", null),
    "2026.9.1",
    acknowledged,
    sessionEntered,
    now,
    unhealthySeconds,
    deviceEligibility,
  )

  private fun statusState(stage: SupervisorStatusStage) = SupervisorControlState.Status(status(stage))

  private fun status(stage: SupervisorStatusStage) =
    SupervisorStatus(
      supervisorBootId = bootId,
      eventSequence = 1,
      commandSequence = 1,
      timestampEpochSeconds = now,
      stage = stage,
      gatewayGeneration = generation.takeIf { stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted },
      ensureAttemptId = 1L.takeIf { stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted },
      exitCode = 0,
      planId = planId,
      selectedCapabilities = emptyList(),
      resolvedComponents = resolveComponents(emptyList()),
      readyCapabilities = emptyList(),
    )
}
