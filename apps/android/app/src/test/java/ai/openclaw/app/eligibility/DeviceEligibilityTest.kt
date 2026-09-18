package ai.openclaw.app.eligibility

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceEligibilityTest {
  @Test
  fun missingEvidenceIsUnknownBeforeAnyProductClaim() {
    assertEquals(
      DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.EvidenceUnavailable),
      resolveDeviceEligibility(readyEvidence().copy(avfAvailable = null)),
    )
  }

  @Test
  fun contradictoryTerminalEvidenceIsUnknown() {
    assertEquals(
      DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.ContradictoryEvidence),
      resolveDeviceEligibility(
        readyEvidence().copy(
          terminalPackage = TerminalPackageEvidence.UserInstalled,
          terminalLaunchable = true,
        ),
      ),
    )
  }

  @Test
  fun productRequirementsBlockInStablePriorityOrder() {
    assertEquals(
      DeviceEligibility.Blocked(DeviceBlockReason.UnsupportedAndroidVersion),
      resolveDeviceEligibility(readyEvidence().copy(androidApi = MINIMUM_ANDROID_API - 1)),
    )
    assertEquals(
      DeviceEligibility.Blocked(DeviceBlockReason.UnsupportedAbi),
      resolveDeviceEligibility(readyEvidence().copy(supportedAbis = listOf("x86_64"))),
    )
    assertEquals(
      DeviceEligibility.Blocked(DeviceBlockReason.AvfUnavailable),
      resolveDeviceEligibility(readyEvidence().copy(avfAvailable = false, terminalLaunchable = false)),
    )
    assertEquals(
      DeviceEligibility.Blocked(DeviceBlockReason.TerminalComponentMissing),
      resolveDeviceEligibility(
        readyEvidence().copy(
          terminalPackage = TerminalPackageEvidence.Missing,
          terminalLaunchable = false,
        ),
      ),
    )
  }

  @Test
  fun onboardingOwnsOnlySettingsThatTheUserCanResolve() {
    assertEquals(
      DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableDeveloperOptions),
      resolveDeviceEligibility(
        readyEvidence().copy(
          developerOptionsEnabled = false,
          terminalLaunchable = false,
        ),
      ),
    )
    assertEquals(
      DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableLinuxEnvironment),
      resolveDeviceEligibility(readyEvidence().copy(terminalLaunchable = false)),
    )
    assertEquals(DeviceEligibility.Ready, resolveDeviceEligibility(readyEvidence()))
  }

  private fun readyEvidence() =
    DeviceEligibilityEvidence(
      androidApi = MINIMUM_ANDROID_API,
      supportedAbis = listOf(REQUIRED_DEVICE_ABI),
      avfAvailable = true,
      terminalPackage = TerminalPackageEvidence.System,
      developerOptionsEnabled = true,
      terminalLaunchable = true,
    )
}
