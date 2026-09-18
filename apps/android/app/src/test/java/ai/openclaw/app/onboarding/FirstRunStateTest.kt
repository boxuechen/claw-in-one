package ai.openclaw.app.onboarding

import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.bootstrap.BootstrapDeliveryError
import ai.openclaw.app.bootstrap.BootstrapDeliveryState
import ai.openclaw.app.bootstrap.BootstrapHandoffState
import ai.openclaw.app.eligibility.DeviceBlockReason
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorCommand
import ai.openclaw.app.supervisor.SupervisorControlError
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.requiredSetupComponents
import ai.openclaw.app.supervisor.resolveComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunStateTest {
  @Test
  fun completedMilestoneWinsOverCurrentRuntimeHealth() {
    assertEquals(
      FirstRunState.Completed,
      resolveFirstRunState(
        snapshot(
          onboardingReceipt = receipt(),
          deviceEligibility =
            DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableDeveloperOptions),
          supervisorPresent = false,
          gatewayConnected = false,
          aiSetup = AiSetupState.Disconnected,
        ),
      ),
    )
  }

  @Test
  fun compatibilityAndPreparationRemainAheadOfLinuxSetup() {
    assertEquals(
      FirstRunState.AwaitingDeviceEligibility,
      resolveFirstRunState(snapshot(deviceEligibility = DeviceEligibility.Blocked(DeviceBlockReason.AvfUnavailable))),
    )
    assertEquals(
      FirstRunState.DevicePreparation(DevicePreparationRequirement.EnableDeveloperOptions),
      resolveFirstRunState(
        snapshot(
          deviceEligibility =
            DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableDeveloperOptions),
        ),
      ),
    )
    assertEquals(
      FirstRunState.DevicePreparation(DevicePreparationRequirement.EnableLinuxEnvironment),
      resolveFirstRunState(
        snapshot(
          deviceEligibility =
            DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableLinuxEnvironment),
        ),
      ),
    )
  }

  @Test
  fun bootstrapOwnsItsTerminalAndFailureStates() {
    assertEquals(
      FirstRunState.LinuxProvisioning(LinuxProvisioningStep.SupervisorRequired(commandReady = true)),
      resolveFirstRunState(
        snapshot(
          bootstrapDelivery = BootstrapDeliveryState.Ready("content://bootstrap", "command"),
          bootstrapHandoff = BootstrapHandoffState.Waiting,
        ),
      ),
    )
    assertEquals(
      FirstRunState.Failed(FirstRunFailure.BootstrapDelivery(BootstrapDeliveryError.WriteFailed)),
      resolveFirstRunState(
        snapshot(
          bootstrapDelivery = BootstrapDeliveryState.Failed(BootstrapDeliveryError.WriteFailed),
        ),
      ),
    )
  }

  @Test
  fun firstRunInstallsOnlyTheFixedRequiredEnvironment() {
    assertEquals(
      FirstRunState.EnvironmentSetup(EnvironmentSetupStep.InstallReady),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = controlStatus(SupervisorStatusStage.CapabilitiesRequired),
        ),
      ),
    )

    val command = SupervisorCommand.applyCapabilities(PLAN_ID, emptySet())
    assertEquals(
      FirstRunState.EnvironmentSetup(
        EnvironmentSetupStep.Installing(
          status = null,
          resolvedComponents = requiredSetupComponents(),
        ),
      ),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = SupervisorControlState.Waiting(command, sequence = 2, latestStatus = null),
        ),
      ),
    )
  }

  @Test
  fun installProgressAndFailureStayWithTheEnvironmentOwner() {
    val downloading =
      supervisorStatus(
        stage = SupervisorStatusStage.DownloadingOpenClaw,
        currentComponent = CapabilityComponent.OpenClaw,
        completedBytes = 25,
        totalBytes = 100,
      )
    assertEquals(
      FirstRunState.EnvironmentSetup(
        EnvironmentSetupStep.Installing(
          status = downloading,
          resolvedComponents = requiredSetupComponents(),
        ),
      ),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = SupervisorControlState.Status(downloading),
        ),
      ),
    )

    val failed =
      supervisorStatus(
        stage = SupervisorStatusStage.CapabilityFailed,
        exitCode = 23,
        currentComponent = CapabilityComponent.OpenClaw,
      )
    assertEquals(
      FirstRunState.Failed(FirstRunFailure.Setup(failed)),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = SupervisorControlState.Status(failed),
        ),
      ),
    )
  }

  @Test
  fun supervisorTransportFailureDoesNotBecomeAnInstallFailure() {
    assertEquals(
      FirstRunState.Failed(FirstRunFailure.Supervisor(SupervisorControlError.StatusReadFailed)),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = SupervisorControlState.Failed(SupervisorControlError.StatusReadFailed),
        ),
      ),
    )
  }

  @Test
  fun requiredEnvironmentAdvancesThroughGatewayAndAiSetupWithoutDevicePairing() {
    val ready = controlStatus(SupervisorStatusStage.CapabilitiesReady)
    assertEquals(
      FirstRunState.EnvironmentSetup(EnvironmentSetupStep.ConnectingGateway(starting = false)),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = ready,
          gatewayConnected = false,
        ),
      ),
    )
    assertEquals(
      FirstRunState.AiSetup(AiSetupState.Disconnected),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = ready,
          gatewayConnected = true,
          aiSetup = AiSetupState.Disconnected,
        ),
      ),
    )

    val finalizing =
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = ready,
          gatewayConnected = true,
          aiSetup = AiSetupState.Ready("openai/gpt", 42.0),
        ),
      )
    assertTrue(finalizing is FirstRunState.Finalizing)
    assertEquals(
      RequiredSetupEvidence(SUPERVISOR_ID, PLAN_ID, eventSequence = 7),
      (finalizing as FirstRunState.Finalizing).evidence,
    )
  }

  @Test
  fun aiSetupCannotFinalizeWithoutExactRequiredSetupEvidence() {
    assertEquals(
      FirstRunState.LinuxProvisioning(LinuxProvisioningStep.Connecting(progress = null)),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisorId = null,
          supervisor = controlStatus(SupervisorStatusStage.CapabilitiesReady),
          gatewayConnected = true,
          aiSetup = AiSetupState.Ready("openai/gpt", 42.0),
        ),
      ),
    )
    assertEquals(
      FirstRunState.LinuxProvisioning(LinuxProvisioningStep.Connecting(progress = null)),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor =
            SupervisorControlState.Status(
              supervisorStatus(
                stage = SupervisorStatusStage.CapabilitiesReady,
                resolvedComponents = requiredSetupComponents().dropLast(1),
              ),
            ),
          gatewayConnected = true,
          aiSetup = AiSetupState.Ready("openai/gpt", 42.0),
        ),
      ),
    )
  }

  @Test
  fun optionalExtensionSelectionDoesNotAddAnOnboardingGate() {
    val ready =
      controlStatus(
        stage = SupervisorStatusStage.CapabilitiesReady,
        selectedCapabilities = listOf(DevelopmentCapability.Flutter),
        resolvedComponents = resolveComponents(listOf(DevelopmentCapability.Flutter)),
      )
    assertTrue(
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = ready,
          gatewayConnected = true,
          aiSetup = AiSetupState.Ready("openai/gpt", 42.0),
        ),
      ) is FirstRunState.Finalizing,
    )
  }

  @Test
  fun gatewayPairingStagesAndFailureRemainGatewayOwned() {
    assertEquals(
      FirstRunState.EnvironmentSetup(EnvironmentSetupStep.ConnectingGateway(starting = true)),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor =
            SupervisorControlState.Waiting(
              command = SupervisorCommand.RequestGatewayPairing,
              sequence = 2,
              latestStatus = supervisorStatus(SupervisorStatusStage.OpenClawReady),
            ),
        ),
      ),
    )
    assertEquals(
      FirstRunState.Failed(FirstRunFailure.GatewayPortForwarding),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = controlStatus(SupervisorStatusStage.GatewayReady),
          gatewayFailure = FirstRunFailure.GatewayPortForwarding,
        ),
      ),
    )
    assertEquals(
      FirstRunState.Failed(FirstRunFailure.GatewayPairing(exitCode = 40)),
      resolveFirstRunState(
        snapshot(
          supervisorPresent = true,
          supervisor = controlStatus(SupervisorStatusStage.GatewayPairingFailed, exitCode = 40),
        ),
      ),
    )
  }

  private fun snapshot(
    onboardingReceipt: OnboardingReceipt? = null,
    deviceEligibility: DeviceEligibility = DeviceEligibility.Ready,
    bootstrapDelivery: BootstrapDeliveryState = BootstrapDeliveryState.NotPrepared,
    bootstrapHandoff: BootstrapHandoffState = BootstrapHandoffState.Stopped,
    supervisorPresent: Boolean = false,
    supervisorId: String? = SUPERVISOR_ID,
    supervisor: SupervisorControlState = SupervisorControlState.Stopped,
    gatewayConnected: Boolean = false,
    aiSetup: AiSetupState = AiSetupState.Disconnected,
    gatewayFailure: FirstRunFailure? = null,
  ) = FirstRunSnapshot(
    onboardingReceipt = onboardingReceipt,
    deviceEligibility = deviceEligibility,
    bootstrapDelivery = bootstrapDelivery,
    bootstrapHandoff = bootstrapHandoff,
    supervisorPresent = supervisorPresent,
    supervisorId = supervisorId,
    supervisor = supervisor,
    gatewayConnected = gatewayConnected,
    aiSetup = aiSetup,
    gatewayFailure = gatewayFailure,
  )

  private fun controlStatus(
    stage: SupervisorStatusStage,
    exitCode: Int = 0,
    selectedCapabilities: List<DevelopmentCapability> = emptyList(),
    resolvedComponents: List<CapabilityComponent> = resolveComponents(selectedCapabilities),
  ) = SupervisorControlState.Status(
    supervisorStatus(
      stage = stage,
      exitCode = exitCode,
      selectedCapabilities = selectedCapabilities,
      resolvedComponents = resolvedComponents,
    ),
  )

  private fun supervisorStatus(
    stage: SupervisorStatusStage,
    exitCode: Int = 0,
    currentComponent: CapabilityComponent? = null,
    completedBytes: Long? = null,
    totalBytes: Long? = null,
    selectedCapabilities: List<DevelopmentCapability> = emptyList(),
    resolvedComponents: List<CapabilityComponent> = resolveComponents(selectedCapabilities),
  ): SupervisorStatus {
    val requiresPlan =
      stage !in setOf(SupervisorStatusStage.SupervisorReady, SupervisorStatusStage.CapabilitiesRequired)
    return SupervisorStatus(
      supervisorBootId = "d".repeat(32),
      eventSequence = 7,
      commandSequence = 1,
      timestampEpochSeconds = 1_788_400_000,
      stage = stage,
      gatewayGeneration =
        "e".repeat(32).takeIf {
          stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted
        },
      ensureAttemptId =
        1L.takeIf {
          stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted
        },
      exitCode = exitCode,
      planId = PLAN_ID.takeIf { requiresPlan },
      selectedCapabilities = selectedCapabilities,
      resolvedComponents = resolvedComponents,
      readyCapabilities =
        selectedCapabilities.takeIf { stage >= SupervisorStatusStage.CapabilitiesReady }.orEmpty(),
      currentComponent = currentComponent,
      completedBytes = completedBytes,
      totalBytes = totalBytes,
    )
  }

  private fun receipt() =
    OnboardingReceipt.create(
      RequiredSetupEvidence(SUPERVISOR_ID, PLAN_ID, eventSequence = 7),
      completedAtEpochSeconds = 1,
    )

  private companion object {
    const val SUPERVISOR_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val PLAN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
