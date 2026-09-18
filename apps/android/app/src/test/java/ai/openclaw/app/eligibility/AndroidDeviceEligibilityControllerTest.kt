package ai.openclaw.app.eligibility

import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDeviceEligibilityControllerTest {
  @Test
  fun setupActionsUsePublicAndroidContracts() {
    assertEquals(
      Settings.ACTION_DEVICE_INFO_SETTINGS,
      deviceEligibilityIntent(DeviceEligibilityAction.OpenDeviceInfo).action,
    )
    assertEquals(
      Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
      deviceEligibilityIntent(DeviceEligibilityAction.OpenDeveloperSettings).action,
    )
    assertEquals(
      SYSTEM_UPDATE_SETTINGS_ACTION,
      deviceEligibilityIntent(DeviceEligibilityAction.OpenSystemUpdate).action,
    )

    val terminalIntent = deviceEligibilityIntent(DeviceEligibilityAction.OpenTerminal)
    assertEquals(Intent.ACTION_MAIN, terminalIntent.action)
    assertTrue(terminalIntent.hasCategory(Intent.CATEGORY_LAUNCHER))
    assertEquals(LINUX_TERMINAL_PACKAGE, terminalIntent.`package`)
  }

  @Test
  fun controllerStartsCheckingAndPublishesEachFreshProbe() {
    var current = readyEvidence().copy(developerOptionsEnabled = false)
    val controller = AndroidDeviceEligibilityController { current }

    assertEquals(DeviceEligibility.Checking, controller.state.value.eligibility)
    controller.refresh()
    assertEquals(
      DeviceEligibility.OnboardingResolvable(DevicePreparationRequirement.EnableDeveloperOptions),
      controller.state.value.eligibility,
    )

    current = readyEvidence()
    controller.refresh()
    assertEquals(DeviceEligibility.Ready, controller.state.value.eligibility)
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
