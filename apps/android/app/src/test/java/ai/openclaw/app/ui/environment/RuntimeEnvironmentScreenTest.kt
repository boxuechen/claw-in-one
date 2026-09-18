package ai.openclaw.app.ui.environment

import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.runtime.LocalServiceState
import ai.openclaw.app.runtime.RuntimeAction
import ai.openclaw.app.runtime.RuntimeSetup
import ai.openclaw.app.runtime.RuntimeState
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class RuntimeEnvironmentScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun offlineGatewayDoesNotBlockSupervisorRecovery() {
    val actions = mutableListOf<RuntimeAction>()
    show(RuntimeState(localService = LocalServiceState.Responding, actions = setOf(RuntimeAction.EnsureGateway, RuntimeAction.Reconnect, RuntimeAction.OpenSystemTerminal)), actions::add)
    composeRule.onNodeWithText("Start OpenClaw").performScrollTo().performClick()
    composeRule.onNodeWithText("Reconnect").assertDoesNotExist()
    composeRule.onNodeWithText("Open system Terminal").performScrollTo().performClick()
    assertEquals(listOf(RuntimeAction.EnsureGateway, RuntimeAction.OpenSystemTerminal), actions)
  }

  @Test
  fun closeRemainsFixedWhileFailedInstallationScrolls() {
    var closed = false
    show(
      RuntimeState(
        localService = LocalServiceState.Responding,
        setup =
          RuntimeSetup(
            stage = SupervisorStatusStage.CapabilityFailed,
            planId = "existing-plan",
            selectedCapabilities = DevelopmentCapability.entries,
            resolvedComponents = CapabilityComponent.entries,
            readyCapabilities = listOf(DevelopmentCapability.AndroidKotlin),
            currentComponent = CapabilityComponent.Flutter,
            completedBytes = 5242880,
            totalBytes = 10485760,
            exitCode = 2,
          ),
        lastResponseEpochSeconds = 1,
        actions = setOf(RuntimeAction.RetryCapabilityPlan, RuntimeAction.SkipOptionalCapability, RuntimeAction.OpenSystemTerminal),
      ),
      onClose = { closed = true },
    )
    val before = composeRule.onNodeWithTag("environment-close").getUnclippedBoundsInRoot()
    composeRule.onNodeWithText("Current component").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("5.0 / 10.0 MB · 50%").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("Last response").performScrollTo().assertIsDisplayed()
    assertEquals(before, composeRule.onNodeWithTag("environment-close").getUnclippedBoundsInRoot())
    composeRule.onNodeWithTag("environment-close").assertIsDisplayed().performClick()
    assertEquals(true, closed)
  }

  @Test
  fun unreachableServiceDoesNotInventStoppedLinuxOrOfferMutations() {
    show(RuntimeState(localService = LocalServiceState.Unreachable, gatewayConnected = true))
    composeRule.onNodeWithText("Connected").assertIsDisplayed()
    composeRule.onNodeWithText("Unable to reach local service").assertIsDisplayed()
    composeRule.onNodeWithText("Start OpenClaw").assertDoesNotExist()
    composeRule.onNodeWithText("Retry installation").assertDoesNotExist()
  }

  @Test
  fun healthyEnvironmentOffersAnExplicitOwnerScopedRepair() {
    val actions = mutableListOf<RuntimeAction>()
    show(
      RuntimeState(
        localService = LocalServiceState.Responding,
        gatewayConnected = true,
        actions = setOf(RuntimeAction.RepairCapabilityPlan),
      ),
      actions::add,
    )

    composeRule.onNodeWithText("Repair environment").performScrollTo().performClick()

    assertEquals(listOf(RuntimeAction.RepairCapabilityPlan), actions)
  }

  private fun show(
    state: RuntimeState,
    onAction: (RuntimeAction) -> Unit = {},
    onClose: () -> Unit = {},
  ) {
    composeRule.setContent { ProvideClawDesignSystem { RuntimeEnvironmentScreen(state, false, onAction, onClose) } }
  }
}
