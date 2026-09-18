package ai.openclaw.app.ui.settings

import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.settings.AboutSettingsFeature
import ai.openclaw.app.ui.OpenClawTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
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
class AboutOnboardingReviewEntryTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun installEnglishResources() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun debugAboutEntryExplainsAndOpensTheReadOnlyReview() {
    var opened = false
    composeRule.setContent {
      OpenClawTheme {
        AboutSettingsRoute(
          feature =
            AboutSettingsFeature(
              updateAvailable = MutableStateFlow(null),
              gatewayVersion = MutableStateFlow("2026.9.4"),
            ),
          onOpenLicenses = {},
          onPreviewOnboarding = { opened = true },
          onBack = {},
        )
      }
    }

    composeRule
      .onNodeWithText("Preview first setup")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    composeRule.onNodeWithText("Preview only. Your device will not be changed.").assertIsDisplayed()
    composeRule.runOnIdle { assertTrue(opened) }
  }
}
