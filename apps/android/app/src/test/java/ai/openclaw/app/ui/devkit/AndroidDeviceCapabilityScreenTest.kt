package ai.openclaw.app.ui.devkit

import ai.openclaw.app.androiddevice.AndroidDeviceAvailability
import ai.openclaw.app.androiddevice.AndroidDeviceConnectionState
import ai.openclaw.app.androiddevice.AndroidDeviceSnapshot
import ai.openclaw.app.androiddevice.AndroidDeviceStatus
import ai.openclaw.app.androiddevice.AndroidDeviceTarget
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h700dp-420dpi")
class AndroidDeviceCapabilityScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun setupStartsTheOneTimeSystemPairingFlowWithoutLocalCredentialFields() {
    var startPairingCalls = 0
    show(
      state =
        AndroidDeviceConnectionState(
          availability = AndroidDeviceAvailability.Available,
          snapshot = snapshot(AndroidDeviceStatus.SetupRequired, paired = false, connected = false),
        ),
      onStartPairing = { startPairingCalls += 1 },
    )

    composeRule.onNodeWithTag("deviceBridge-pair-endpoint").assertDoesNotExist()
    composeRule.onNodeWithTag("deviceBridge-pair-code").assertDoesNotExist()
    composeRule.onNodeWithText("Start pairing").performScrollTo().performClick()

    assertEquals(1, startPairingCalls)
  }

  @Test
  fun readyShowsOnlyThePairedTargetAndConfirmedForgetAction() {
    var forgetCalls = 0
    show(
      state =
        AndroidDeviceConnectionState(
          availability = AndroidDeviceAvailability.Available,
          snapshot = snapshot(AndroidDeviceStatus.Ready, paired = true, connected = true, target = target()),
        ),
      onForget = { forgetCalls += 1 },
    )

    composeRule.onNodeWithText("Pixel 8 · Android API 37").assertIsDisplayed()
    composeRule.onNodeWithText("Forget development connection").performScrollTo().performClick()
    composeRule.onNodeWithText("Forget development connection?").assertIsDisplayed()
    composeRule.onNodeWithText("Forget").performClick()

    assertEquals(1, forgetCalls)
  }

  @Test
  fun offlineRecoveryDefaultsToDiscoveryAndKeepsManualAddressAsFallback() {
    val reconnectCalls = mutableListOf<String?>()
    show(
      state =
        AndroidDeviceConnectionState(
          availability = AndroidDeviceAvailability.Available,
          snapshot = snapshot(AndroidDeviceStatus.Offline, paired = true, connected = false, target = target()),
        ),
      onReconnect = reconnectCalls::add,
    )

    composeRule.onNodeWithText("Start pairing").assertDoesNotExist()
    composeRule.onNodeWithTag("deviceBridge-connect-endpoint").assertDoesNotExist()
    composeRule.onNodeWithText("Reconnect").performScrollTo().performClick()
    assertEquals(listOf<String?>(null), reconnectCalls)

    composeRule.onNodeWithText("Enter address manually").performScrollTo().performClick()
    composeRule.onNodeWithTag("deviceBridge-connect-endpoint").performScrollTo().performTextInput("10.0.0.2:42002")
    composeRule.onNodeWithText("Connect to address").performScrollTo().performClick()
    composeRule.onNodeWithText("Hide manual connection").performScrollTo().performClick()
    composeRule.onNodeWithTag("deviceBridge-connect-endpoint").assertDoesNotExist()
    composeRule.onNodeWithText("Reconnect").performScrollTo().performClick()

    assertEquals(listOf(null, "10.0.0.2:42002", null), reconnectCalls)
  }

  private fun show(
    state: AndroidDeviceConnectionState,
    onStartPairing: () -> Unit = {},
    onReconnect: (String?) -> Unit = {},
    onForget: () -> Unit = {},
  ) {
    composeRule.setContent {
      ProvideClawDesignSystem {
        AndroidDeviceCapabilityScreen(
          state = state,
          pairingPromptIssue = null,
          onOpenDeveloperOptions = {},
          onStartPairing = onStartPairing,
          onOpenNotificationSettings = {},
          onRefresh = {},
          onReconnect = onReconnect,
          onForget = onForget,
          onDismissNotice = {},
          onBack = {},
        )
      }
    }
  }

  private fun snapshot(
    status: AndroidDeviceStatus,
    paired: Boolean,
    connected: Boolean,
    target: AndroidDeviceTarget? = null,
  ) = AndroidDeviceSnapshot(
    status = status,
    paired = paired,
    connected = connected,
    reasonCode = null,
    verificationId = null,
    target = target,
  )

  private fun target() =
    AndroidDeviceTarget(
      id = "d".repeat(64),
      product = "shiba",
      model = "Pixel 8",
      androidApi = 37,
    )
}
