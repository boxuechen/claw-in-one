package ai.openclaw.app.ui.eligibility

import ai.openclaw.app.eligibility.DeviceBlockReason
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilitySnapshot
import ai.openclaw.app.eligibility.DeviceEligibilityUnknownReason
import ai.openclaw.app.i18n.nativeString

internal enum class CompatibilityAction {
  Refresh,
  OpenSystemUpdate,
  CopyDiagnostics,
}

internal data class CompatibilityUiState(
  val title: String,
  val body: String,
  val checking: Boolean,
  val primaryAction: CompatibilityAction? = null,
  val primaryLabel: String? = null,
  val secondaryAction: CompatibilityAction? = null,
  val secondaryLabel: String? = null,
  val diagnostics: String,
)

internal fun DeviceEligibilitySnapshot.toCompatibilityUiState(): CompatibilityUiState =
  when (val verdict = eligibility) {
    DeviceEligibility.Checking ->
      CompatibilityUiState(
        title = nativeString("Checking this device"),
        body = nativeString("ClawInOne is confirming that Android's Linux environment is available."),
        checking = true,
        diagnostics = diagnosticText(),
      )
    is DeviceEligibility.Blocked -> blockedUiState(verdict.reason)
    is DeviceEligibility.Unknown -> unknownUiState(verdict.reason)
    is DeviceEligibility.OnboardingResolvable,
    DeviceEligibility.Ready,
    -> error("Compatibility UI cannot present an eligible device")
  }

private fun DeviceEligibilitySnapshot.blockedUiState(reason: DeviceBlockReason): CompatibilityUiState {
  val (body, action) =
    when (reason) {
      DeviceBlockReason.UnsupportedAndroidVersion ->
        nativeString("ClawInOne requires Android 15 or newer with the system Linux environment.") to
          CompatibilityAction.OpenSystemUpdate
      DeviceBlockReason.UnsupportedAbi ->
        nativeString("ClawInOne requires an ARM64 device for its local Linux development environment.") to null
      DeviceBlockReason.AvfUnavailable ->
        nativeString("This device does not provide Android's required virtualization capability.") to null
      DeviceBlockReason.TerminalComponentMissing ->
        nativeString("The system Linux Terminal component is unavailable. A system update may be required.") to
          CompatibilityAction.OpenSystemUpdate
    }
  return CompatibilityUiState(
    title = nativeString("This device cannot run ClawInOne"),
    body = body,
    checking = false,
    primaryAction = action,
    primaryLabel = nativeString("Open system update").takeIf { action != null },
    secondaryAction = CompatibilityAction.CopyDiagnostics,
    secondaryLabel = nativeString("Copy diagnostic info"),
    diagnostics = diagnosticText(),
  )
}

private fun DeviceEligibilitySnapshot.unknownUiState(
  reason: DeviceEligibilityUnknownReason,
): CompatibilityUiState =
  CompatibilityUiState(
    title = nativeString("Device compatibility could not be confirmed"),
    body =
      when (reason) {
        DeviceEligibilityUnknownReason.EvidenceUnavailable ->
          nativeString("Android did not provide enough information. Check again after returning to the app.")
        DeviceEligibilityUnknownReason.ContradictoryEvidence ->
          nativeString("Android reported inconsistent Linux environment information. Check again after a restart or system update.")
      },
    checking = false,
    primaryAction = CompatibilityAction.Refresh,
    primaryLabel = nativeString("Check again"),
    secondaryAction = CompatibilityAction.CopyDiagnostics,
    secondaryLabel = nativeString("Copy diagnostic info"),
    diagnostics = diagnosticText(),
  )

private fun DeviceEligibilitySnapshot.diagnosticText(): String =
  buildString {
    appendLine("ClawInOne device eligibility")
    appendLine("result=${eligibility.diagnosticLabel()}")
    appendLine("androidApi=${evidence.androidApi ?: "unknown"}")
    appendLine("abis=${evidence.supportedAbis?.joinToString().orEmpty().ifBlank { "unknown" }}")
    appendLine("avf=${evidence.avfAvailable ?: "unknown"}")
    appendLine("terminalPackage=${evidence.terminalPackage.name}")
    appendLine("developerOptions=${evidence.developerOptionsEnabled ?: "unknown"}")
    append("terminalLaunchable=${evidence.terminalLaunchable ?: "unknown"}")
  }

private fun DeviceEligibility.diagnosticLabel(): String =
  when (this) {
    DeviceEligibility.Checking -> "Checking"
    is DeviceEligibility.OnboardingResolvable -> "OnboardingResolvable:${requirement.name}"
    DeviceEligibility.Ready -> "Ready"
    is DeviceEligibility.Blocked -> "Blocked:${reason.name}"
    is DeviceEligibility.Unknown -> "Unknown:${reason.name}"
  }
