package ai.openclaw.app.ui.ai

import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.ai.GatewayWizardDeviceCode
import ai.openclaw.app.ai.GatewayWizardStep
import ai.openclaw.app.ai.GatewayWizardStepType
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h700dp-420dpi")
class AiSetupScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun deviceCodeNoteOffersCopyAndBrowserAsTheSinglePrimaryContinuation() {
    var opened: Pair<String, String>? = null
    var copied: String? = null
    var answered: Pair<String, Any?>? = null
    composeRule.setContent {
      ProvideClawDesignSystem {
        AiSetupScreen(
          state =
            AiSetupState.Wizard(
              label = "Continue with ChatGPT",
              step =
                GatewayWizardStep(
                  id = "device",
                  type = GatewayWizardStepType.Note,
                  title = "OpenAI device code",
                  message = "Sign in with the code below.",
                  options = emptyList(),
                  initialValue = null,
                  placeholder = null,
                  sensitive = false,
                  executor = "client",
                  externalUrl = "https://auth.example/device",
                  deviceCode = GatewayWizardDeviceCode("ABCD-EFGH", 10, null),
                ),
              submitting = false,
            ),
          onRefresh = {},
          onShowSignIn = {},
          onShowApiKeys = {},
          onChooseAuth = {},
          onEnterApiKey = {},
          onSubmitApiKey = {},
          onChooseModel = {},
          onAnswer = { id, value -> answered = id to value },
          onOpenExternal = { url ->
            opened = "device" to url
            true
          },
          onCopyCode = { copied = it },
          onReconcile = {},
          onCancel = {},
        )
      }
    }

    composeRule.onNodeWithText("ABCD-EFGH").assertIsDisplayed()
    composeRule.onNodeWithText("Copy code").performClick()
    composeRule.onNodeWithText("Open browser").performClick()

    assertEquals("ABCD-EFGH", copied)
    assertEquals("device" to "https://auth.example/device", opened)
    assertEquals("device" to null, answered)
    composeRule.onAllNodesWithText("Continue").assertCountEquals(0)
  }

  @Test
  fun progressCanBeCancelledWhileGatewayLongPollIsActive() {
    var cancelled = false
    composeRule.setContent {
      ProvideClawDesignSystem {
        AiSetupScreen(
          state =
            AiSetupState.Wizard(
              label = "Continue with ChatGPT",
              step =
                GatewayWizardStep(
                  id = "progress",
                  type = GatewayWizardStepType.Progress,
                  title = "Waiting for sign-in",
                  message = null,
                  options = emptyList(),
                  initialValue = null,
                  placeholder = null,
                  sensitive = false,
                  executor = "gateway",
                  externalUrl = null,
                  deviceCode = null,
                ),
              submitting = true,
            ),
          onRefresh = {},
          onShowSignIn = {},
          onShowApiKeys = {},
          onChooseAuth = {},
          onEnterApiKey = {},
          onSubmitApiKey = {},
          onChooseModel = {},
          onAnswer = { _, _ -> },
          onReconcile = {},
          onCancel = { cancelled = true },
        )
      }
    }

    composeRule.onNodeWithText("Cancel").performClick()
    composeRule.waitForIdle()

    assertEquals(true, cancelled)
  }

  @Test
  fun recoveringSessionCanBeCheckedOrCancelled() {
    var checked = false
    var cancelled = false
    composeRule.setContent {
      ProvideClawDesignSystem {
        AiSetupScreen(
          state = AiSetupState.WizardUnknown("Continue with ChatGPT"),
          onRefresh = {},
          onShowSignIn = {},
          onShowApiKeys = {},
          onChooseAuth = {},
          onEnterApiKey = {},
          onSubmitApiKey = {},
          onChooseModel = {},
          onAnswer = { _, _ -> },
          onReconcile = { checked = true },
          onCancel = { cancelled = true },
        )
      }
    }

    composeRule.onNodeWithText("Check again").performClick()
    composeRule.onNodeWithText("Cancel").performClick()
    composeRule.waitForIdle()

    assertTrue(checked)
    assertTrue(cancelled)
  }
}
