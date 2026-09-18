package ai.openclaw.app.entry

import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilitySnapshot
import kotlinx.coroutines.flow.StateFlow

internal sealed interface AppEntryState {
  data class Compatibility(
    val snapshot: DeviceEligibilitySnapshot,
  ) : AppEntryState {
    init {
      require(
        snapshot.eligibility is DeviceEligibility.Checking ||
          snapshot.eligibility is DeviceEligibility.Blocked ||
          snapshot.eligibility is DeviceEligibility.Unknown,
      )
    }
  }

  data object FirstRun : AppEntryState

  data object Product : AppEntryState
}

internal fun resolveAppEntryState(
  onboardingCompleted: Boolean,
  snapshot: DeviceEligibilitySnapshot,
): AppEntryState {
  if (onboardingCompleted) return AppEntryState.Product
  return when (snapshot.eligibility) {
    DeviceEligibility.Checking,
    is DeviceEligibility.Blocked,
    is DeviceEligibility.Unknown,
    -> AppEntryState.Compatibility(snapshot)
    is DeviceEligibility.OnboardingResolvable,
    DeviceEligibility.Ready,
    -> AppEntryState.FirstRun
  }
}

internal class AppEntryFeature(
  val state: StateFlow<AppEntryState>,
  val refreshEligibility: () -> Unit,
)
