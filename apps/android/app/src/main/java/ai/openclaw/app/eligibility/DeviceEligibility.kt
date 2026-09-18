package ai.openclaw.app.eligibility

import kotlinx.coroutines.flow.StateFlow

internal const val MINIMUM_ANDROID_API = 35
internal const val REQUIRED_DEVICE_ABI = "arm64-v8a"

internal enum class TerminalPackageEvidence {
  Unknown,
  Missing,
  UserInstalled,
  System,
}

/** Raw Android evidence. Nullable fields mean the corresponding query could not be trusted. */
internal data class DeviceEligibilityEvidence(
  val androidApi: Int? = null,
  val supportedAbis: List<String>? = null,
  val avfAvailable: Boolean? = null,
  val terminalPackage: TerminalPackageEvidence = TerminalPackageEvidence.Unknown,
  val developerOptionsEnabled: Boolean? = null,
  val terminalLaunchable: Boolean? = null,
)

internal enum class DevicePreparationRequirement {
  EnableDeveloperOptions,
  EnableLinuxEnvironment,
}

internal enum class DeviceBlockReason {
  UnsupportedAndroidVersion,
  UnsupportedAbi,
  AvfUnavailable,
  TerminalComponentMissing,
}

internal enum class DeviceEligibilityUnknownReason {
  EvidenceUnavailable,
  ContradictoryEvidence,
}

internal sealed interface DeviceEligibility {
  data object Checking : DeviceEligibility

  data class OnboardingResolvable(
    val requirement: DevicePreparationRequirement,
  ) : DeviceEligibility

  data object Ready : DeviceEligibility

  data class Blocked(
    val reason: DeviceBlockReason,
  ) : DeviceEligibility

  data class Unknown(
    val reason: DeviceEligibilityUnknownReason,
  ) : DeviceEligibility
}

internal data class DeviceEligibilitySnapshot(
  val evidence: DeviceEligibilityEvidence = DeviceEligibilityEvidence(),
  val eligibility: DeviceEligibility = DeviceEligibility.Checking,
)

internal fun resolveDeviceEligibility(evidence: DeviceEligibilityEvidence): DeviceEligibility {
  if (
    evidence.androidApi == null ||
    evidence.supportedAbis == null ||
    evidence.avfAvailable == null ||
    evidence.terminalPackage == TerminalPackageEvidence.Unknown ||
    evidence.developerOptionsEnabled == null ||
    evidence.terminalLaunchable == null
  ) {
    return DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.EvidenceUnavailable)
  }

  if (
    evidence.terminalLaunchable &&
    (evidence.terminalPackage != TerminalPackageEvidence.System || !evidence.avfAvailable)
  ) {
    return DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.ContradictoryEvidence)
  }
  if (evidence.terminalPackage == TerminalPackageEvidence.UserInstalled) {
    return DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.ContradictoryEvidence)
  }
  if (evidence.androidApi < MINIMUM_ANDROID_API) {
    return DeviceEligibility.Blocked(DeviceBlockReason.UnsupportedAndroidVersion)
  }
  if (REQUIRED_DEVICE_ABI !in evidence.supportedAbis) {
    return DeviceEligibility.Blocked(DeviceBlockReason.UnsupportedAbi)
  }
  if (!evidence.avfAvailable) {
    return DeviceEligibility.Blocked(DeviceBlockReason.AvfUnavailable)
  }
  if (evidence.terminalPackage == TerminalPackageEvidence.Missing) {
    return DeviceEligibility.Blocked(DeviceBlockReason.TerminalComponentMissing)
  }
  if (!evidence.developerOptionsEnabled) {
    return DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableDeveloperOptions)
  }
  if (!evidence.terminalLaunchable) {
    return DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableLinuxEnvironment)
  }
  return DeviceEligibility.Ready
}

internal class DeviceEligibilityFeature(
  val state: StateFlow<DeviceEligibilitySnapshot>,
  val refresh: () -> Unit,
)
