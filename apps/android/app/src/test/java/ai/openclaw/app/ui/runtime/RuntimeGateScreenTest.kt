package ai.openclaw.app.ui.runtime

import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.runtime.RuntimeAction
import ai.openclaw.app.runtime.RuntimeGateIssue
import ai.openclaw.app.runtime.RuntimeGatePhase
import ai.openclaw.app.runtime.RuntimeGatePresentation
import ai.openclaw.app.runtime.RuntimeGateState
import ai.openclaw.app.runtime.RuntimeGateStep
import ai.openclaw.app.runtime.RuntimeState
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class RuntimeGateScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun startupUsesOneBrandedProgressSurfaceWithoutActions() {
    show(
      RuntimeState(
        gate =
          RuntimeGateState(
            presentation = RuntimeGatePresentation.Startup,
            phase = RuntimeGatePhase.CheckingSupervisor,
            step = RuntimeGateStep.Supervisor,
          ),
      ).toRuntimeGateUiState(),
    )

    composeRule.onNodeWithTag("runtime-gate-logo").assertIsDisplayed()
    composeRule.onNodeWithTag("runtime-gate-progress").assertIsDisplayed()
    composeRule.onNodeWithText("Checking Supervisor").assertIsDisplayed()
    composeRule.onNodeWithText("Supervisor").assertIsDisplayed()
    composeRule.onNodeWithText("OpenClaw").assertIsDisplayed()
    composeRule.onNodeWithText("App").assertIsDisplayed()
    composeRule.onNodeWithText("Ready").assertIsDisplayed()
    composeRule.onNodeWithTag("runtime-gate-action-Confirm").assertDoesNotExist()
  }

  @Test
  fun supervisorFailureKeepsRecoveryActionsInsideTheSameSurface() {
    val performed = mutableListOf<RuntimeGateUiAction>()
    val uiState =
      RuntimeState(
        actions = setOf(RuntimeAction.Refresh, RuntimeAction.OpenSystemTerminal),
        gate =
          RuntimeGateState(
            presentation = RuntimeGatePresentation.Recovery,
            phase = RuntimeGatePhase.ActionRequired,
            issue = RuntimeGateIssue.SupervisorUnavailable,
            step = RuntimeGateStep.Supervisor,
          ),
      ).toRuntimeGateUiState()
    show(uiState, performed::add)

    composeRule.onNodeWithTag("runtime-gate-progress").assertIsDisplayed()
    composeRule.onNodeWithText("Check again").performClick()
    composeRule.onNodeWithText("Copy repair command").performClick()
    composeRule.onNodeWithText("Open system Terminal").performClick()

    assertEquals(
      listOf(
        RuntimeGateUiAction.Refresh,
        RuntimeGateUiAction.RepairSupervisor,
        RuntimeGateUiAction.OpenSystemTerminal,
      ),
      performed,
    )
  }

  @Test
  fun transientNoticeDoesNotRenderTheBlockingGate() {
    val state =
      RuntimeState(
        gate =
          RuntimeGateState(
            presentation = RuntimeGatePresentation.TransientNotice,
            phase = RuntimeGatePhase.ConnectingLocalPort,
            step = RuntimeGateStep.AppConnection,
          ),
      ).toRuntimeGateUiState()

    composeRule.setContent {
      ProvideClawDesignSystem {
        RuntimeTransientNotice(state)
      }
    }

    composeRule.onNodeWithTag("runtime-transient-notice").assertIsDisplayed()
    composeRule.onNodeWithTag("runtime-gate-logo").assertDoesNotExist()
  }

  @Test
  fun deviceRecoveryStaysGlobalWithoutPretendingRuntimeProgress() {
    val uiState =
      RuntimeState(
        actions = setOf(RuntimeAction.OpenDeveloperSettings),
        gate =
          RuntimeGateState(
            presentation = RuntimeGatePresentation.Recovery,
            phase = RuntimeGatePhase.ActionRequired,
            issue =
              RuntimeGateIssue.DevicePreparation(
                DevicePreparationRequirement.EnableLinuxEnvironment,
              ),
            step = RuntimeGateStep.Supervisor,
          ),
      ).toRuntimeGateUiState()

    show(uiState)

    composeRule.onNodeWithText("Linux development environment is off").assertIsDisplayed()
    composeRule.onNodeWithText("Open developer options").assertIsDisplayed()
    composeRule.onNodeWithTag("runtime-gate-progress").assertDoesNotExist()
  }

  private fun show(
    state: RuntimeGateUiState,
    onAction: (RuntimeGateUiAction) -> Unit = {},
  ) {
    composeRule.setContent {
      ProvideClawDesignSystem {
        RuntimeGateScreen(state = state, onAction = onAction)
      }
    }
  }
}
