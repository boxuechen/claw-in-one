package ai.openclaw.app.entry

import ai.openclaw.app.eligibility.DeviceBlockReason
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilitySnapshot
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import org.junit.Assert.assertEquals
import org.junit.Test

class AppEntryStateTest {
  @Test
  fun firstInstallKeepsCompatibilityOutsideOnboarding() {
    val blocked =
      DeviceEligibilitySnapshot(
        eligibility = DeviceEligibility.Blocked(DeviceBlockReason.AvfUnavailable),
      )
    assertEquals(AppEntryState.Compatibility(blocked), resolveAppEntryState(false, blocked))
  }

  @Test
  fun actionableAndReadyDevicesEnterFirstRun() {
    assertEquals(
      AppEntryState.FirstRun,
      resolveAppEntryState(
        false,
        DeviceEligibilitySnapshot(
          eligibility =
            DeviceEligibility.OnboardingResolvable(
              DevicePreparationRequirement.EnableLinuxEnvironment,
            ),
        ),
      ),
    )
    assertEquals(
      AppEntryState.FirstRun,
      resolveAppEntryState(false, DeviceEligibilitySnapshot(eligibility = DeviceEligibility.Ready)),
    )
  }

  @Test
  fun completedUserNeverReentersOnboarding() {
    assertEquals(
      AppEntryState.Product,
      resolveAppEntryState(
        true,
        DeviceEligibilitySnapshot(
          eligibility = DeviceEligibility.Blocked(DeviceBlockReason.TerminalComponentMissing),
        ),
      ),
    )
  }
}
