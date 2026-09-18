package ai.openclaw.app.runtime

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.eligibility.DeviceBlockReason
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilityUnknownReason
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.uniqueActiveCapabilityConsumer

internal enum class LocalServiceState { Unchecked, Checking, Responding, LastKnown, Unreachable, NotPaired }

internal enum class RuntimeAction {
  Refresh,
  Reconnect,
  EnsureGateway,
  RequestGatewayPairing,
  RepairCapabilityPlan,
  RetryCapabilityPlan,
  SkipOptionalCapability,
  RefreshDeviceEligibility,
  OpenDeviceInfo,
  OpenDeveloperSettings,
  OpenSystemUpdate,
  OpenSystemTerminal,
}

internal data class RuntimeGeneration(
  val supervisorBootId: String,
  val gatewayGeneration: String,
)

/** Safe projection: never carries Supervisor authentication material or Gateway setup codes. */
internal data class RuntimeSetup(
  val stage: SupervisorStatusStage,
  val planId: String?,
  val selectedCapabilities: List<DevelopmentCapability>,
  val resolvedComponents: List<CapabilityComponent>,
  val readyCapabilities: List<DevelopmentCapability>,
  val currentComponent: CapabilityComponent?,
  val completedBytes: Long?,
  val totalBytes: Long?,
  val exitCode: Int,
)

internal enum class RuntimeGatePresentation { Hidden, Startup, TransientNotice, Recovery }

internal enum class RuntimeGatePhase {
  CheckingDevice,
  CheckingSupervisor,
  PreparingGateway,
  PlannedGatewayRestart,
  ConnectingLocalPort,
  ReadyToEnter,
  Healthy,
  ActionRequired,
  ReadyToResume,
}

internal sealed interface RuntimeGateIssue {
  data class DevicePreparation(
    val requirement: DevicePreparationRequirement,
  ) : RuntimeGateIssue

  data class DeviceBlocked(
    val reason: DeviceBlockReason,
  ) : RuntimeGateIssue

  data class DeviceUnknown(
    val reason: DeviceEligibilityUnknownReason,
  ) : RuntimeGateIssue

  data object SupervisorUnavailable : RuntimeGateIssue

  data object GatewayFailed : RuntimeGateIssue

  data object PairingRequired : RuntimeGateIssue

  data object LocalPortUnavailable : RuntimeGateIssue
}

internal enum class RuntimeGateStep { Supervisor, Gateway, AppConnection, Ready }

internal data class RuntimeGateState(
  val presentation: RuntimeGatePresentation = RuntimeGatePresentation.Startup,
  val phase: RuntimeGatePhase = RuntimeGatePhase.CheckingSupervisor,
  val issue: RuntimeGateIssue? = null,
  val step: RuntimeGateStep = RuntimeGateStep.Supervisor,
  val generation: RuntimeGeneration? = null,
)

internal data class RuntimeState(
  val localService: LocalServiceState = LocalServiceState.Unchecked,
  val gatewayConnected: Boolean = false,
  val gatewayVersion: String? = null,
  val supervisorBootId: String? = null,
  val gatewayGeneration: String? = null,
  val setup: RuntimeSetup? = null,
  val lastResponseEpochSeconds: Long? = null,
  val operationInProgress: Boolean = false,
  val actions: Set<RuntimeAction> = setOf(RuntimeAction.Refresh, RuntimeAction.OpenSystemTerminal),
  val gate: RuntimeGateState = RuntimeGateState(),
)

internal fun resolveRuntimeState(
  supervisor: SupervisorControlState,
  monitoring: Boolean,
  gateway: GatewayConnectionDisplay,
  gatewayVersion: String?,
  acknowledgedGeneration: RuntimeGeneration?,
  sessionEntered: Boolean,
  nowEpochSeconds: Long,
  unhealthyDurationSeconds: Long,
  deviceEligibility: DeviceEligibility,
  plannedGatewayRestart: Boolean = false,
): RuntimeState {
  val status =
    when (supervisor) {
      is SupervisorControlState.Status -> supervisor.value
      is SupervisorControlState.Waiting -> supervisor.latestStatus
      else -> null
    }
  val fresh = status != null && nowEpochSeconds - status.timestampEpochSeconds in -5..15
  val service =
    when (supervisor) {
      SupervisorControlState.Missing -> LocalServiceState.NotPaired
      SupervisorControlState.Stopped -> if (monitoring) LocalServiceState.Checking else LocalServiceState.Unchecked
      is SupervisorControlState.Failed -> LocalServiceState.Unreachable
      is SupervisorControlState.Waiting -> if (fresh) LocalServiceState.Responding else LocalServiceState.Checking
      is SupervisorControlState.Status -> if (fresh) LocalServiceState.Responding else LocalServiceState.LastKnown
    }
  val generation = operationalGatewayGeneration(status, gateway.isConnected, acknowledgedGeneration)
  val ready = fresh && generation != null
  val eligibilityIssue =
    when (deviceEligibility) {
      DeviceEligibility.Checking,
      DeviceEligibility.Ready,
      -> null
      is DeviceEligibility.OnboardingResolvable -> RuntimeGateIssue.DevicePreparation(deviceEligibility.requirement)
      is DeviceEligibility.Blocked -> RuntimeGateIssue.DeviceBlocked(deviceEligibility.reason)
      is DeviceEligibility.Unknown -> RuntimeGateIssue.DeviceUnknown(deviceEligibility.reason)
    }
  val actions =
    buildSet {
      when {
        deviceEligibility == DeviceEligibility.Checking -> Unit
        eligibilityIssue != null ->
          when (val issue = eligibilityIssue) {
            is RuntimeGateIssue.DevicePreparation ->
              add(
                when (issue.requirement) {
                  DevicePreparationRequirement.EnableDeveloperOptions -> RuntimeAction.OpenDeviceInfo
                  DevicePreparationRequirement.EnableLinuxEnvironment -> RuntimeAction.OpenDeveloperSettings
                },
              )
            is RuntimeGateIssue.DeviceBlocked ->
              if (
                issue.reason == DeviceBlockReason.UnsupportedAndroidVersion ||
                issue.reason == DeviceBlockReason.TerminalComponentMissing
              ) {
                add(RuntimeAction.OpenSystemUpdate)
              }
            is RuntimeGateIssue.DeviceUnknown -> add(RuntimeAction.RefreshDeviceEligibility)
            RuntimeGateIssue.GatewayFailed,
            RuntimeGateIssue.LocalPortUnavailable,
            RuntimeGateIssue.PairingRequired,
            RuntimeGateIssue.SupervisorUnavailable,
            -> error("Runtime issue cannot be produced by device eligibility")
          }
        else -> add(RuntimeAction.OpenSystemTerminal)
      }
      if (deviceEligibility == DeviceEligibility.Ready && !monitoring) add(RuntimeAction.Refresh)
      if (deviceEligibility == DeviceEligibility.Ready && !gateway.isConnected) add(RuntimeAction.Reconnect)
      if (deviceEligibility == DeviceEligibility.Ready && !monitoring && service == LocalServiceState.Responding) {
        if (status?.stage in REPAIRABLE_CAPABILITY_STAGES && status?.planId != null) {
          add(RuntimeAction.RepairCapabilityPlan)
        }
        if (
          !gateway.isConnected &&
          status?.stage in
          setOf(
            SupervisorStatusStage.CapabilitiesReady,
            SupervisorStatusStage.GatewayNotStarted,
            SupervisorStatusStage.GatewayFailed,
          )
        ) {
          add(RuntimeAction.EnsureGateway)
        }
        if (
          status?.stage == SupervisorStatusStage.GatewayPairingFailed ||
          gateway.problem?.isPairingRequired == true
        ) {
          add(RuntimeAction.RequestGatewayPairing)
        }
        if (status?.stage == SupervisorStatusStage.CapabilityFailed && status.planId != null) {
          add(RuntimeAction.RetryCapabilityPlan)
          if (status.currentComponent?.let { uniqueActiveCapabilityConsumer(it, status.selectedCapabilities) } != null) {
            add(RuntimeAction.SkipOptionalCapability)
          }
        }
      }
    }
  val generationChanged = acknowledgedGeneration != null && generation != null && generation != acknowledgedGeneration
  val definitiveIssue =
    when {
      eligibilityIssue != null -> eligibilityIssue
      plannedGatewayRestart -> null
      service in setOf(LocalServiceState.NotPaired, LocalServiceState.Unreachable) -> RuntimeGateIssue.SupervisorUnavailable
      status?.stage == SupervisorStatusStage.GatewayFailed -> RuntimeGateIssue.GatewayFailed
      status?.stage == SupervisorStatusStage.GatewayPairingFailed || gateway.problem?.isPairingRequired == true -> RuntimeGateIssue.PairingRequired
      status?.stage in GATEWAY_READY_STAGES && !gateway.isConnected && unhealthyDurationSeconds >= 10 ->
        RuntimeGateIssue.LocalPortUnavailable
      else -> null
    }
  val phase =
    when {
      deviceEligibility == DeviceEligibility.Checking -> RuntimeGatePhase.CheckingDevice
      eligibilityIssue != null -> RuntimeGatePhase.ActionRequired
      plannedGatewayRestart -> RuntimeGatePhase.PlannedGatewayRestart
      ready && !sessionEntered -> RuntimeGatePhase.ReadyToEnter
      ready && generationChanged -> RuntimeGatePhase.ReadyToResume
      ready -> RuntimeGatePhase.Healthy
      definitiveIssue != null -> RuntimeGatePhase.ActionRequired
      service != LocalServiceState.Responding -> RuntimeGatePhase.CheckingSupervisor
      status?.stage in
        setOf(
          SupervisorStatusStage.CapabilitiesReady,
          SupervisorStatusStage.GatewayNotStarted,
          SupervisorStatusStage.GatewayConfiguring,
          SupervisorStatusStage.GatewayStarting,
          SupervisorStatusStage.GatewayHealthy,
        ) -> RuntimeGatePhase.PreparingGateway
      status?.stage in GATEWAY_READY_STAGES -> RuntimeGatePhase.ConnectingLocalPort
      else -> RuntimeGatePhase.CheckingSupervisor
    }
  val presentation =
    when {
      phase == RuntimeGatePhase.Healthy -> RuntimeGatePresentation.Hidden
      phase == RuntimeGatePhase.PlannedGatewayRestart -> RuntimeGatePresentation.Recovery
      !sessionEntered -> RuntimeGatePresentation.Startup
      phase == RuntimeGatePhase.ReadyToResume || generationChanged || definitiveIssue != null || unhealthyDurationSeconds >= 10 ->
        RuntimeGatePresentation.Recovery
      else -> RuntimeGatePresentation.TransientNotice
    }
  val step =
    when (phase) {
      RuntimeGatePhase.CheckingDevice -> RuntimeGateStep.Supervisor
      RuntimeGatePhase.CheckingSupervisor ->
        if (service == LocalServiceState.Responding) RuntimeGateStep.Gateway else RuntimeGateStep.Supervisor
      RuntimeGatePhase.ActionRequired ->
        when (definitiveIssue) {
          is RuntimeGateIssue.DevicePreparation,
          is RuntimeGateIssue.DeviceBlocked,
          is RuntimeGateIssue.DeviceUnknown,
          RuntimeGateIssue.SupervisorUnavailable,
          null,
          -> RuntimeGateStep.Supervisor
          RuntimeGateIssue.GatewayFailed -> RuntimeGateStep.Gateway
          RuntimeGateIssue.PairingRequired,
          RuntimeGateIssue.LocalPortUnavailable,
          -> RuntimeGateStep.AppConnection
        }
      RuntimeGatePhase.PreparingGateway -> RuntimeGateStep.Gateway
      RuntimeGatePhase.PlannedGatewayRestart -> RuntimeGateStep.Gateway
      RuntimeGatePhase.ConnectingLocalPort -> RuntimeGateStep.AppConnection
      RuntimeGatePhase.ReadyToEnter,
      RuntimeGatePhase.Healthy,
      RuntimeGatePhase.ReadyToResume,
      -> RuntimeGateStep.Ready
    }
  return RuntimeState(
    localService = service,
    gatewayConnected = gateway.isConnected,
    gatewayVersion = gatewayVersion.takeIf { gateway.isConnected },
    supervisorBootId = status?.supervisorBootId,
    gatewayGeneration = generation?.gatewayGeneration ?: status?.gatewayGeneration,
    setup =
      status?.let {
        RuntimeSetup(
          it.stage,
          it.planId,
          it.selectedCapabilities,
          it.resolvedComponents,
          it.readyCapabilities,
          it.currentComponent,
          it.completedBytes,
          it.totalBytes,
          it.exitCode,
        )
      },
    lastResponseEpochSeconds = status?.timestampEpochSeconds,
    operationInProgress = monitoring,
    actions = actions,
    gate = RuntimeGateState(presentation, phase, definitiveIssue, step, generation),
  )
}

internal fun operationalGatewayGeneration(
  status: ai.openclaw.app.supervisor.SupervisorStatus?,
  gatewayConnected: Boolean,
  acknowledgedGeneration: RuntimeGeneration?,
): RuntimeGeneration? {
  if (!gatewayConnected || status == null) return null
  if (status.stage in GATEWAY_READY_STAGES) {
    return status.gatewayGeneration?.let { RuntimeGeneration(status.supervisorBootId, it) }
  }
  return acknowledgedGeneration?.takeIf {
    status.stage in GATEWAY_CONTINUITY_STAGES && it.supervisorBootId == status.supervisorBootId
  }
}

private val GATEWAY_READY_STAGES =
  setOf(
    SupervisorStatusStage.GatewayReady,
    SupervisorStatusStage.GatewayPairingReady,
  )

private val GATEWAY_CONTINUITY_STAGES =
  setOf(
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
    SupervisorStatusStage.CapabilitiesReady,
    SupervisorStatusStage.CapabilityFailed,
    SupervisorStatusStage.CapabilityPlanRejected,
  )

private val REPAIRABLE_CAPABILITY_STAGES =
  setOf(
    SupervisorStatusStage.CapabilitiesReady,
    SupervisorStatusStage.GatewayNotStarted,
    SupervisorStatusStage.GatewayReady,
    SupervisorStatusStage.GatewayPairingReady,
  )
