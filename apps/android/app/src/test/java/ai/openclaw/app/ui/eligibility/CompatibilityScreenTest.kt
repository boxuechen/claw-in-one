package ai.openclaw.app.ui.eligibility

import ai.openclaw.app.eligibility.DeviceBlockReason
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilitySnapshot
import ai.openclaw.app.eligibility.DeviceEligibilityUnknownReason
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.os.LocaleListCompat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h700dp-420dpi")
class CompatibilityScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun checkingIsCenteredAndActionFree() {
    show(DeviceEligibilitySnapshot().toCompatibilityUiState())

    composeRule.onNodeWithTag("compatibility-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("compatibility-progress").assertIsDisplayed()
    composeRule.onNodeWithTag("compatibility-primary-action").assertDoesNotExist()
    composeRule.onNodeWithTag("compatibility-secondary-action").assertDoesNotExist()
  }

  @Test
  fun unknownEvidenceHasOnePrimaryDecisionAndOneQuietDiagnosticAction() {
    show(
      DeviceEligibilitySnapshot(
        eligibility = DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.EvidenceUnavailable),
      ).toCompatibilityUiState(),
    )

    composeRule.onNodeWithText("Check again").assertIsDisplayed()
    composeRule.onNodeWithText("Copy diagnostic info").assertIsDisplayed()
  }

  @Test
  fun permanentlyUnsupportedAbiDoesNotOfferAFalseRecoveryAction() {
    show(
      DeviceEligibilitySnapshot(
        eligibility = DeviceEligibility.Blocked(DeviceBlockReason.UnsupportedAbi),
      ).toCompatibilityUiState(),
    )

    composeRule.onNodeWithTag("compatibility-primary-action").assertDoesNotExist()
    composeRule.onNodeWithText("Copy diagnostic info").assertIsDisplayed()
  }

  private fun show(state: CompatibilityUiState) {
    composeRule.setContent {
      ProvideClawDesignSystem {
        CompatibilityScreen(state = state, onAction = {})
      }
    }
  }
}
