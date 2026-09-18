package ai.openclaw.app.ui.onboardingreview

import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.ui.OpenClawTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h840dp-420dpi")
class AndroidOnboardingReviewRouteTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun installEnglishResources() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun startsAtCheckingAndMovesThroughReadOnlyProductionScreens() {
    show()

    composeRule.onNodeWithTag("onboarding-review").assertIsDisplayed()
    composeRule.onNodeWithText("Checking this device").assertIsDisplayed()
    composeRule.onNodeWithText("1 / 11").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Next preview").performClick()
    composeRule.onNodeWithText("This device cannot run ClawInOne").assertIsDisplayed()
    composeRule.onNodeWithText("2 / 11").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Next preview").performClick()
    composeRule.onNodeWithText("Device compatibility could not be confirmed").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Next preview").performClick()
    composeRule.onNodeWithText("Turn on developer options").assertIsDisplayed()
  }

  @Test
  fun adbSceneStartsAtAiSetupWithoutRunningAGatewayOwner() {
    show(OnboardingReviewScene.Ai.rawValue)

    composeRule.onNodeWithText("Choose how to use AI").assertIsDisplayed()
    composeRule.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
    composeRule.onNodeWithText("10 / 11").assertIsDisplayed()
  }

  @Test
  fun setupReviewShowsTheConciseLinuxTerminalAndEnvironmentSurfaces() {
    show(OnboardingReviewScene.LinuxEnvironment.rawValue)
    composeRule.onNodeWithTag("onboarding-brand-logo").assertIsDisplayed()
    composeRule.onNodeWithText("Dev").assertIsDisplayed()
    composeRule
      .onNodeWithText("Enable Linux development environment. USB debugging is not needed; Wireless debugging comes later.")
      .assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Next preview").performClick()
    composeRule.onNodeWithTag("bootstrap-command").assertIsDisplayed()
    composeRule.onNodeWithText("Copy command").performClick()
    composeRule.onNodeWithText("Command copied").assertIsDisplayed()
    composeRule.onNodeWithText("Open Terminal").assertIsDisplayed()
    composeRule.onNodeWithText("GitHub source").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Next preview").performClick()
    composeRule.onNodeWithText("Prepare OpenClaw").assertIsDisplayed()
    composeRule.onNodeWithText("Install environment").assertIsDisplayed()
  }

  @Test
  fun closeLeavesTheEphemeralReview() {
    var closed = false
    show(onClose = { closed = true })

    composeRule.onNodeWithContentDescription("Close preview").performClick()

    composeRule.runOnIdle { assertTrue(closed) }
  }

  @Test
  fun previousReturnsToThePriorScene() {
    show(OnboardingReviewScene.Unknown.rawValue)

    composeRule.onNodeWithContentDescription("Previous preview").performClick()

    composeRule.onNodeWithText("This device cannot run ClawInOne").assertIsDisplayed()
    composeRule.onNodeWithText("2 / 11").assertIsDisplayed()
  }

  @Test
  fun everySceneRendersAndTheFinalActionCloses() {
    var closed = false
    show(onClose = { closed = true })

    repeat(OnboardingReviewScene.entries.lastIndex) { index ->
      composeRule.onNodeWithContentDescription("Next preview").performClick()
      composeRule.onNodeWithText("${index + 2} / ${OnboardingReviewScene.entries.size}").assertIsDisplayed()
    }
    composeRule.onNodeWithText("ClawInOne is ready").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Finish preview").performClick()

    composeRule.runOnIdle { assertTrue(closed) }
  }

  private fun show(
    initialScene: String? = null,
    onClose: () -> Unit = {},
  ) {
    composeRule.setContent {
      OpenClawTheme {
        AndroidOnboardingReviewRoute(initialScene = initialScene, onClose = onClose)
      }
    }
  }
}
