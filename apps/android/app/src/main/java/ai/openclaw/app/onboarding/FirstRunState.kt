package ai.openclaw.app.onboarding

import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.bootstrap.BootstrapDeliveryError
import ai.openclaw.app.bootstrap.BootstrapDeliveryState
import ai.openclaw.app.bootstrap.BootstrapHandoffError
import ai.openclaw.app.bootstrap.BootstrapHandoffState
import ai.openclaw.app.bootstrap.SupervisorProgress
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.SupervisorControlError
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.supervisor.SupervisorOperation
import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage

internal data class FirstRunSnapshot(
  val onboardingReceipt: OnboardingReceipt?,
  val deviceEligibility: DeviceEligibility,
  val bootstrapDelivery: BootstrapDeliveryState,
  val bootstrapHandoff: BootstrapHandoffState,
  val supervisorPresent: Boolean,
  val supervisorId: String?,
  val supervisor: SupervisorControlState,
  val gatewayConnected: Boolean,
  val aiSetup: AiSetupState,
  val gatewayFailure: FirstRunFailure? = null,
)

internal sealed interface FirstRunFailure {
  data class BootstrapDelivery(
    val error: BootstrapDeliveryError,
  ) : FirstRunFailure

  data class BootstrapHandoff(
    val error: BootstrapHandoffError,
  ) : FirstRunFailure

  data class Supervisor(
    val error: SupervisorControlError,
  ) : FirstRunFailure

  data class Setup(
    val status: SupervisorStatus,
  ) : FirstRunFailure {
    init {
      require(status.stage == SupervisorStatusStage.CapabilityFailed)
      require(status.planId != null && status.currentComponent != null)
    }

    val exitCode: Int get() = status.exitCode
    val planId: String get() = checkNotNull(status.planId)
    val component: CapabilityComponent get() = checkNotNull(status.currentComponent)
  }

  data class SetupPlanRejected(
    val status: SupervisorStatus,
  ) : FirstRunFailure {
    init {
      require(status.stage == SupervisorStatusStage.CapabilityPlanRejected)
    }

    val exitCode: Int get() = status.exitCode
  }

  data class GatewayPairing(
    val exitCode: Int?,
  ) : FirstRunFailure

  data object GatewayPortForwarding : FirstRunFailure
}

internal sealed interface FirstRunState {
  data object Completed : FirstRunState

  /** App entry owns the corresponding checking/blocked/unknown UI. */
  data object AwaitingDeviceEligibility : FirstRunState

  data class DevicePreparation(
    val requirement: DevicePreparationRequirement,
  ) : FirstRunState

  data class LinuxProvisioning(
    val step: LinuxProvisioningStep,
  ) : FirstRunState

  data class EnvironmentSetup(
    val step: EnvironmentSetupStep,
  ) : FirstRunState

  data class AiSetup(
    val state: AiSetupState,
  ) : FirstRunState

  data class Finalizing(
    val evidence: RequiredSetupEvidence,
  ) : FirstRunState

  data class Failed(
    val failure: FirstRunFailure,
  ) : FirstRunState
}

internal sealed interface LinuxProvisioningStep {
  data class SupervisorRequired(
    val commandReady: Boolean,
  ) : LinuxProvisioningStep

  data class Connecting(
    val progress: SupervisorProgress?,
  ) : LinuxProvisioningStep
}

internal sealed interface EnvironmentSetupStep {
  data object InstallReady : EnvironmentSetupStep

  data class Installing(
    val status: SupervisorStatus?,
    val resolvedComponents: List<CapabilityComponent>,
  ) : EnvironmentSetupStep {
    init {
      require(resolvedComponents.contains(CapabilityComponent.OpenClaw))
      require(resolvedComponents.distinct() == resolvedComponents)
    }
  }

  data class ConnectingGateway(
    val starting: Boolean,
  ) : EnvironmentSetupStep
}

internal data class InstallationProgress(
  val completedBytes: Long,
  val totalBytes: Long,
) {
  init {
    require(completedBytes in 0..totalBytes)
    require(totalBytes > 0)
  }
}

internal fun resolveFirstRunState(snapshot: FirstRunSnapshot): FirstRunState {
  if (snapshot.onboardingReceipt != null) return FirstRunState.Completed
  when (val eligibility = snapshot.deviceEligibility) {
    DeviceEligibility.Checking,
    is DeviceEligibility.Blocked,
    is DeviceEligibility.Unknown,
    -> return FirstRunState.AwaitingDeviceEligibility
    is DeviceEligibility.OnboardingResolvable ->
      return FirstRunState.DevicePreparation(eligibility.requirement)
    DeviceEligibility.Ready -> Unit
  }
  snapshot.gatewayFailure?.let { return FirstRunState.Failed(it) }
  if (!snapshot.supervisorPresent) return resolveSupervisorBootstrapState(snapshot)
  return when (val supervisor = snapshot.supervisor) {
    SupervisorControlState.Stopped -> linuxProvisioning(LinuxProvisioningStep.Connecting(progress = null))
    SupervisorControlState.Missing -> linuxProvisioning(LinuxProvisioningStep.SupervisorRequired(commandReady = false))
    is SupervisorControlState.Failed ->
      FirstRunState.Failed(FirstRunFailure.Supervisor(supervisor.error))
    is SupervisorControlState.Waiting ->
      when (supervisor.command.operation) {
        SupervisorOperation.Probe ->
          if (snapshot.gatewayConnected) {
            resolveGatewayAndAiState(snapshot, supervisor.latestStatus)
          } else {
            linuxProvisioning(LinuxProvisioningStep.Connecting(progress = null))
          }
        SupervisorOperation.ApplyCapabilities,
        SupervisorOperation.RetryCapabilities,
        SupervisorOperation.SkipCapability,
        ->
          environmentSetup(
            EnvironmentSetupStep.Installing(
              status = supervisor.latestStatus,
              resolvedComponents =
                supervisor.latestStatus
                  ?.resolvedComponents
                  ?.takeIf { it.isNotEmpty() }
                  ?: ai.openclaw.app.supervisor.resolveComponents(
                    supervisor.command.selectedCapabilities()
                      ?: emptyList(),
                  ),
            ),
          )
        SupervisorOperation.EnsureGateway,
        SupervisorOperation.RequestGatewayPairing,
        -> environmentSetup(EnvironmentSetupStep.ConnectingGateway(starting = true))
      }
    is SupervisorControlState.Status -> resolveSupervisorStatus(snapshot, supervisor.value)
  }
}

private fun resolveSupervisorBootstrapState(snapshot: FirstRunSnapshot): FirstRunState {
  val handoff = snapshot.bootstrapHandoff
  if (handoff is BootstrapHandoffState.Failed) {
    return FirstRunState.Failed(FirstRunFailure.BootstrapHandoff(handoff.error))
  }
  val delivery = snapshot.bootstrapDelivery
  if (delivery is BootstrapDeliveryState.Failed) {
    return FirstRunState.Failed(FirstRunFailure.BootstrapDelivery(delivery.error))
  }
  return when (handoff) {
    BootstrapHandoffState.Waiting -> linuxProvisioning(LinuxProvisioningStep.SupervisorRequired(commandReady = true))
    is BootstrapHandoffState.Progress -> linuxProvisioning(LinuxProvisioningStep.Connecting(handoff.stage))
    BootstrapHandoffState.Stopped,
    BootstrapHandoffState.Starting,
    is BootstrapHandoffState.Ready,
    -> linuxProvisioning(LinuxProvisioningStep.Connecting(progress = null))
    is BootstrapHandoffState.Failed -> error("handled above")
  }
}

private fun resolveSupervisorStatus(
  snapshot: FirstRunSnapshot,
  status: ai.openclaw.app.supervisor.SupervisorStatus,
): FirstRunState =
  when (status.stage) {
    SupervisorStatusStage.SupervisorReady,
    SupervisorStatusStage.CapabilitiesRequired,
    -> environmentSetup(EnvironmentSetupStep.InstallReady)
    SupervisorStatusStage.CapabilityPlanAccepted,
    SupervisorStatusStage.DownloadingNode,
    SupervisorStatusStage.VerifyingNode,
    SupervisorStatusStage.InstallingNode,
    SupervisorStatusStage.DownloadingOpenClaw,
    SupervisorStatusStage.VerifyingOpenClaw,
    SupervisorStatusStage.InstallingOpenClaw,
    SupervisorStatusStage.VerifyingOpenClawInstallation,
    SupervisorStatusStage.OpenClawReady,
    SupervisorStatusStage.DownloadingComponent,
    SupervisorStatusStage.VerifyingComponent,
    SupervisorStatusStage.InstallingComponent,
    SupervisorStatusStage.ComponentReady,
    ->
      environmentSetup(
        EnvironmentSetupStep.Installing(
          status = status,
          resolvedComponents = status.resolvedComponents,
        ),
      )
    SupervisorStatusStage.CapabilityFailed ->
      FirstRunState.Failed(
        FirstRunFailure.Setup(status),
      )
    SupervisorStatusStage.CapabilityPlanRejected ->
      FirstRunState.Failed(FirstRunFailure.SetupPlanRejected(status))
    SupervisorStatusStage.CapabilitiesReady,
    SupervisorStatusStage.GatewayNotStarted,
    SupervisorStatusStage.GatewayReady,
    -> resolveGatewayAndAiState(snapshot, status)
    SupervisorStatusStage.GatewayConfiguring,
    SupervisorStatusStage.GatewayStarting,
    SupervisorStatusStage.GatewayHealthy,
    -> environmentSetup(EnvironmentSetupStep.ConnectingGateway(starting = true))
    SupervisorStatusStage.GatewayPairingReady -> resolveGatewayAndAiState(snapshot, status)
    SupervisorStatusStage.GatewayPairingFailed,
    SupervisorStatusStage.GatewayFailed,
    ->
      FirstRunState.Failed(
        FirstRunFailure.GatewayPairing(exitCode = status.exitCode),
      )
  }

private fun resolveGatewayAndAiState(
  snapshot: FirstRunSnapshot,
  status: SupervisorStatus?,
): FirstRunState {
  if (!snapshot.gatewayConnected) {
    return environmentSetup(EnvironmentSetupStep.ConnectingGateway(starting = false))
  }
  val evidence = status?.let { requiredSetupEvidence(snapshot.supervisorId, it) }
  if (evidence == null) {
    return linuxProvisioning(LinuxProvisioningStep.Connecting(progress = null))
  }
  return when (snapshot.aiSetup) {
    is AiSetupState.Ready -> FirstRunState.Finalizing(evidence)
    else -> FirstRunState.AiSetup(snapshot.aiSetup)
  }
}

private fun linuxProvisioning(step: LinuxProvisioningStep) = FirstRunState.LinuxProvisioning(step)

private fun environmentSetup(step: EnvironmentSetupStep) = FirstRunState.EnvironmentSetup(step)
