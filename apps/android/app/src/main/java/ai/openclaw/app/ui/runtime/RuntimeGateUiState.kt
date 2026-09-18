package ai.openclaw.app.ui.runtime

import ai.openclaw.app.eligibility.DeviceBlockReason
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.runtime.RuntimeAction
import ai.openclaw.app.runtime.RuntimeGateIssue
import ai.openclaw.app.runtime.RuntimeGatePhase
import ai.openclaw.app.runtime.RuntimeGatePresentation
import ai.openclaw.app.runtime.RuntimeGateStep
import ai.openclaw.app.runtime.RuntimeState

internal enum class RuntimeGateUiAction {
  Confirm,
  Refresh,
  Reconnect,
  EnsureGateway,
  RequestGatewayPairing,
  RepairSupervisor,
  RefreshDeviceEligibility,
  OpenDeviceInfo,
  OpenDeveloperSettings,
  OpenSystemUpdate,
  OpenSystemTerminal,
}

internal enum class RuntimeGateActionEmphasis { Primary, Secondary, Tertiary }

internal data class RuntimeGateActionUiState(
  val action: RuntimeGateUiAction,
  val label: String,
  val emphasis: RuntimeGateActionEmphasis,
  val enabled: Boolean = true,
)

internal data class RuntimeGateUiState(
  val presentation: RuntimeGatePresentation,
  val eyebrow: String,
  val title: String,
  val body: String,
  val step: RuntimeGateStep,
  val showProgress: Boolean,
  val actions: List<RuntimeGateActionUiState>,
  val error: String? = null,
)

private data class RuntimeGateCopy(
  val eyebrow: String,
  val title: String,
  val body: String,
  val primaryAction: RuntimeGateUiAction? = null,
  val primaryLabel: String? = null,
)

internal fun RuntimeState.toRuntimeGateUiState(
  repairPreparing: Boolean = false,
  repairFailed: Boolean = false,
): RuntimeGateUiState {
  val copy = runtimeGateCopy()
  val actions =
    buildList {
      copy.primaryLabel?.let { label ->
        val action = checkNotNull(copy.primaryAction)
        add(
          RuntimeGateActionUiState(
            action = action,
            label = label,
            emphasis = RuntimeGateActionEmphasis.Primary,
            enabled = action == RuntimeGateUiAction.Confirm || action.runtimeAction() in this@toRuntimeGateUiState.actions,
          ),
        )
      }
      if (gate.issue == RuntimeGateIssue.SupervisorUnavailable) {
        add(
          RuntimeGateActionUiState(
            action = RuntimeGateUiAction.RepairSupervisor,
            label = nativeString(if (repairPreparing) "Preparing repair command…" else "Copy repair command"),
            emphasis = RuntimeGateActionEmphasis.Secondary,
            enabled = !repairPreparing,
          ),
        )
      }
      if (gate.phase == RuntimeGatePhase.ActionRequired && RuntimeAction.OpenSystemTerminal in this@toRuntimeGateUiState.actions) {
        add(
          RuntimeGateActionUiState(
            action = RuntimeGateUiAction.OpenSystemTerminal,
            label = nativeString("Open system Terminal"),
            emphasis = RuntimeGateActionEmphasis.Tertiary,
          ),
        )
      }
    }
  return RuntimeGateUiState(
    presentation = gate.presentation,
    eyebrow = copy.eyebrow,
    title = copy.title,
    body = copy.body,
    step = gate.step,
    showProgress =
      gate.presentation in setOf(RuntimeGatePresentation.Startup, RuntimeGatePresentation.Recovery) &&
        gate.phase != RuntimeGatePhase.CheckingDevice &&
        gate.issue !is RuntimeGateIssue.DevicePreparation &&
        gate.issue !is RuntimeGateIssue.DeviceBlocked &&
        gate.issue !is RuntimeGateIssue.DeviceUnknown,
    actions = actions,
    error =
      nativeString("The repair command could not be prepared or system Terminal could not be opened.")
        .takeIf { repairFailed },
  )
}

private fun RuntimeState.runtimeGateCopy(): RuntimeGateCopy =
  when (gate.phase) {
    RuntimeGatePhase.CheckingDevice ->
      RuntimeGateCopy(
        eyebrow = nativeString("Device"),
        title = nativeString("Checking this device"),
        body = nativeString("ClawInOne is confirming that Android's Linux environment is available."),
      )
    RuntimeGatePhase.CheckingSupervisor ->
      RuntimeGateCopy(
        eyebrow = nativeString("Local runtime"),
        title = nativeString("Checking Supervisor"),
        body = nativeString("ClawInOne is checking the phone's local service before opening Chat."),
      )
    RuntimeGatePhase.PreparingGateway ->
      RuntimeGateCopy(
        eyebrow = nativeString("Local runtime"),
        title = nativeString("Starting OpenClaw"),
        body = nativeString("The Supervisor is preparing the Gateway. You can follow each startup stage here."),
      )
    RuntimeGatePhase.PlannedGatewayRestart ->
      RuntimeGateCopy(
        eyebrow = nativeString("Applying AI configuration"),
        title = nativeString("Reconnecting to OpenClaw"),
        body = nativeString("ClawInOne will resume this setup after the new Gateway generation is ready."),
      )
    RuntimeGatePhase.ConnectingLocalPort ->
      RuntimeGateCopy(
        eyebrow = nativeString("Local runtime"),
        title = nativeString("Connecting the app"),
        body = nativeString("OpenClaw is ready inside Linux. ClawInOne is waiting for the local forwarded port."),
      )
    RuntimeGatePhase.ReadyToEnter ->
      RuntimeGateCopy(
        eyebrow = nativeString("Startup complete"),
        title = nativeString("ClawInOne is ready"),
        body = nativeString("Supervisor, OpenClaw Gateway, and the app connection are all available."),
        primaryAction = RuntimeGateUiAction.Confirm,
        primaryLabel = nativeString("Enter Chat"),
      )
    RuntimeGatePhase.ReadyToResume ->
      RuntimeGateCopy(
        eyebrow = nativeString("Runtime restored"),
        title = nativeString("OpenClaw is ready again"),
        body = nativeString("The local runtime restarted with a new generation. Existing Chat state stayed in the app."),
        primaryAction = RuntimeGateUiAction.Confirm,
        primaryLabel = nativeString("Continue"),
      )
    RuntimeGatePhase.Healthy ->
      RuntimeGateCopy(
        eyebrow = nativeString("Local runtime"),
        title = nativeString("Ready"),
        body = nativeString("The local runtime is available."),
      )
    RuntimeGatePhase.ActionRequired -> actionRequiredCopy(gate.issue)
  }

private fun actionRequiredCopy(issue: RuntimeGateIssue?): RuntimeGateCopy =
  when (issue) {
    is RuntimeGateIssue.DevicePreparation ->
      when (issue.requirement) {
        DevicePreparationRequirement.EnableDeveloperOptions ->
          RuntimeGateCopy(
            eyebrow = nativeString("Device needs attention"),
            title = nativeString("Developer options are off"),
            body = nativeString("Turn developer options back on to restore the local environment. Your Projects and Chats are unchanged."),
            primaryAction = RuntimeGateUiAction.OpenDeviceInfo,
            primaryLabel = nativeString("Open About phone"),
          )
        DevicePreparationRequirement.EnableLinuxEnvironment ->
          RuntimeGateCopy(
            eyebrow = nativeString("Linux needs attention"),
            title = nativeString("Linux development environment is off"),
            body = nativeString("Turn the Linux development environment back on. Your Projects and Chats are unchanged."),
            primaryAction = RuntimeGateUiAction.OpenDeveloperSettings,
            primaryLabel = nativeString("Open developer options"),
          )
      }
    is RuntimeGateIssue.DeviceBlocked ->
      RuntimeGateCopy(
        eyebrow = nativeString("Device needs attention"),
        title = nativeString("This device cannot run ClawInOne"),
        body =
          when (issue.reason) {
            DeviceBlockReason.UnsupportedAndroidVersion ->
              nativeString("ClawInOne requires Android 15 or newer with the system Linux environment.")
            DeviceBlockReason.UnsupportedAbi ->
              nativeString("ClawInOne requires an ARM64 device for its local Linux development environment.")
            DeviceBlockReason.AvfUnavailable ->
              nativeString("This device does not provide Android's required virtualization capability.")
            DeviceBlockReason.TerminalComponentMissing ->
              nativeString("The system Linux Terminal component is unavailable. A system update may be required.")
          },
        primaryAction =
          RuntimeGateUiAction.OpenSystemUpdate.takeIf {
            issue.reason == DeviceBlockReason.UnsupportedAndroidVersion ||
              issue.reason == DeviceBlockReason.TerminalComponentMissing
          },
        primaryLabel =
          nativeString("Open system update").takeIf {
            issue.reason == DeviceBlockReason.UnsupportedAndroidVersion ||
              issue.reason == DeviceBlockReason.TerminalComponentMissing
          },
      )
    is RuntimeGateIssue.DeviceUnknown ->
      RuntimeGateCopy(
        eyebrow = nativeString("Device needs attention"),
        title = nativeString("Device compatibility could not be confirmed"),
        body = nativeString("Android did not provide consistent Linux environment information. Check again after returning to the app."),
        primaryAction = RuntimeGateUiAction.RefreshDeviceEligibility,
        primaryLabel = nativeString("Check again"),
      )
    RuntimeGateIssue.SupervisorUnavailable ->
      RuntimeGateCopy(
        eyebrow = nativeString("Supervisor needs attention"),
        title = nativeString("Local service is unavailable"),
        body = nativeString("ClawInOne cannot reach the Supervisor. Check again, or open system Terminal to inspect the Linux environment."),
        primaryAction = RuntimeGateUiAction.Refresh,
        primaryLabel = nativeString("Check again"),
      )
    RuntimeGateIssue.GatewayFailed ->
      RuntimeGateCopy(
        eyebrow = nativeString("OpenClaw needs attention"),
        title = nativeString("Gateway did not start"),
        body = nativeString("The Supervisor is connected, but the OpenClaw Gateway stopped during startup."),
        primaryAction = RuntimeGateUiAction.EnsureGateway,
        primaryLabel = nativeString("Retry OpenClaw"),
      )
    RuntimeGateIssue.PairingRequired ->
      RuntimeGateCopy(
        eyebrow = nativeString("App connection needs attention"),
        title = nativeString("Pair ClawInOne again"),
        body = nativeString("The Supervisor can prepare a new one-time setup code. ClawInOne will finish the Gateway pairing internally."),
        primaryAction = RuntimeGateUiAction.RequestGatewayPairing,
        primaryLabel = nativeString("Pair again"),
      )
    RuntimeGateIssue.LocalPortUnavailable ->
      RuntimeGateCopy(
        eyebrow = nativeString("App connection needs attention"),
        title = nativeString("Local port is unavailable"),
        body = nativeString("OpenClaw is healthy inside Linux, but Android cannot reach its forwarded port. Reconnect first; system Terminal is available for forwarding settings."),
        primaryAction = RuntimeGateUiAction.Reconnect,
        primaryLabel = nativeString("Reconnect"),
      )
    null ->
      RuntimeGateCopy(
        eyebrow = nativeString("Local runtime"),
        title = nativeString("Runtime needs attention"),
        body = nativeString("Check the local runtime state before continuing."),
        primaryAction = RuntimeGateUiAction.Refresh,
        primaryLabel = nativeString("Check again"),
      )
  }

private fun RuntimeGateUiAction.runtimeAction(): RuntimeAction? =
  when (this) {
    RuntimeGateUiAction.Refresh -> RuntimeAction.Refresh
    RuntimeGateUiAction.Reconnect -> RuntimeAction.Reconnect
    RuntimeGateUiAction.EnsureGateway -> RuntimeAction.EnsureGateway
    RuntimeGateUiAction.RequestGatewayPairing -> RuntimeAction.RequestGatewayPairing
    RuntimeGateUiAction.RefreshDeviceEligibility -> RuntimeAction.RefreshDeviceEligibility
    RuntimeGateUiAction.OpenDeviceInfo -> RuntimeAction.OpenDeviceInfo
    RuntimeGateUiAction.OpenDeveloperSettings -> RuntimeAction.OpenDeveloperSettings
    RuntimeGateUiAction.OpenSystemUpdate -> RuntimeAction.OpenSystemUpdate
    RuntimeGateUiAction.OpenSystemTerminal -> RuntimeAction.OpenSystemTerminal
    RuntimeGateUiAction.Confirm,
    RuntimeGateUiAction.RepairSupervisor,
    -> null
  }
