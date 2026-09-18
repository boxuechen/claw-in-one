package ai.openclaw.app.ui.setup

import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.onboarding.EnvironmentSetupStep
import ai.openclaw.app.onboarding.FirstRunState
import ai.openclaw.app.onboarding.LinuxProvisioningStep
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class SetupScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun requiredEnvironmentShowsOneInstallActionWithoutOptionalSelections() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        SetupScreen(
          state =
            SetupUiState(
              copy =
                firstRunCopy(
                  FirstRunState.EnvironmentSetup(EnvironmentSetupStep.InstallReady),
                ),
              launchFailed = false,
            ),
          actions =
            SetupActions(
              runPrimaryAction = {},
              copyTerminalCommand = {},
              openTerminal = {},
              openTerminalSource = {},
              refresh = {},
            ),
        )
      }
    }

    composeRule.onNodeWithText("Prepare OpenClaw").assertIsDisplayed()
    composeRule.onNodeWithText("Install environment").assertIsDisplayed()
    composeRule.onAllNodesWithText("Web development").assertCountEquals(0)
    composeRule.onAllNodesWithText("Dev").assertCountEquals(0)
  }

  @Test
  fun terminalHandoffShowsBrandCommandAndThreeExplicitActions() {
    var copies = 0
    var terminalOpens = 0
    var sourceOpens = 0
    val command =
      "CLAW_IN_ONE_CONFIG_FILE='/mnt/shared/Download/ClawInOne/handoff-review/bootstrap.env' " +
        "bash '/mnt/shared/Download/ClawInOne/bootstrap-review/bootstrap.sh'"

    composeRule.setContent {
      ProvideClawDesignSystem {
        SetupScreen(
          state =
            SetupUiState(
              copy =
                firstRunCopy(
                  FirstRunState.LinuxProvisioning(
                    LinuxProvisioningStep.SupervisorRequired(commandReady = true),
                  ),
                ),
              launchFailed = false,
              showDevelopmentBadge = true,
              terminalCommand =
                TerminalCommandUiState(
                  command = command,
                  sourceUrl = "https://github.com/boxuechen/claw-in-one/tree/main/bootstrap",
                  copyState = TerminalCommandCopyState.Copied,
                ),
            ),
          actions =
            emptyActions(
              copyTerminalCommand = { copies++ },
              openTerminal = { terminalOpens++ },
              openTerminalSource = { sourceOpens++ },
            ),
        )
      }
    }

    composeRule.onNodeWithTag("onboarding-brand-logo").assertIsDisplayed()
    composeRule.onNodeWithText("Dev").assertIsDisplayed()
    composeRule.onNodeWithTag("bootstrap-command").assertIsDisplayed()
    composeRule.onNodeWithText("Command copied").assertIsDisplayed()
    composeRule.onNodeWithTag("open-terminal").performClick()
    composeRule.runOnIdle {
      assertEquals(0, copies)
      assertEquals(1, terminalOpens)
      assertEquals(0, sourceOpens)
    }

    composeRule.onNodeWithTag("copy-bootstrap-command").performClick()
    composeRule.onNodeWithTag("bootstrap-source").performClick()
    composeRule.runOnIdle {
      assertEquals(1, copies)
      assertEquals(1, terminalOpens)
      assertEquals(1, sourceOpens)
    }
  }

  private fun emptyActions(
    copyTerminalCommand: () -> Unit = {},
    openTerminal: () -> Unit = {},
    openTerminalSource: () -> Unit = {},
  ) = SetupActions(
    runPrimaryAction = {},
    copyTerminalCommand = copyTerminalCommand,
    openTerminal = openTerminal,
    openTerminalSource = openTerminalSource,
    refresh = {},
  )
}
