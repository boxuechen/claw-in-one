package ai.openclaw.app.ui.extensions

import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginDetailScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun availablePluginLeadsWithValueAndKeepsInstallActionVisible() {
    RuntimeEnvironment.getApplication()
    var installs = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginDetailScreen(
          state =
            PluginDetailUiState(
              plugin = plugin(status = PluginInstallStatus.Available),
              connected = true,
              inspectionAvailable = true,
              loading = false,
              inspection = null,
              inspectionErrorText = null,
            ),
          contentPadding = PaddingValues(),
          onRetryInspection = {},
          lifecycle = PluginLifecyclePresentation(canInstall = true),
          onInstall = { installs += 1 },
          onRefreshLifecycle = {},
          onDismissLifecycle = {},
        )
      }
    }

    composeRule.onNodeWithText("Calendar").assertIsDisplayed()
    composeRule.onNodeWithText("Plan and review events.").assertIsDisplayed()
    composeRule.onNodeWithText("What it adds").assertIsDisplayed()
    composeRule.onNodeWithText("Tools OpenClaw can use in Chat").assertIsDisplayed()
    composeRule.onNodeWithText("Install Plugin").assertIsDisplayed().performClick()
    assertEquals(1, installs)
  }

  @Test
  fun capabilityReviewContentHasOneConfirmAction() {
    RuntimeEnvironment.getApplication()
    var confirmations = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        ExtensionReviewSheetContent(
          title = "Review Calendar",
          intro = "Review what this Plugin can access before continuing.",
          confirmLabel = "Allow and install",
          onConfirm = { confirmations += 1 },
          onDismiss = {},
        ) {
          Text("calendar.events")
        }
      }
    }

    composeRule.onNodeWithText("Review Calendar").assertIsDisplayed()
    composeRule.onNodeWithText("calendar.events").assertIsDisplayed()
    composeRule.onNodeWithText("Allow and install").performClick()
    assertEquals(1, confirmations)
  }

  @Test
  fun managementDetailOwnsEnablementAndRemoval() {
    RuntimeEnvironment.getApplication()
    var enabled: Boolean? = null
    var removals = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginDetailScreen(
          state =
            PluginDetailUiState(
              plugin = plugin(status = PluginInstallStatus.Installed),
              connected = true,
              inspectionAvailable = true,
              loading = false,
              inspection = null,
              inspectionErrorText = null,
            ),
          contentPadding = PaddingValues(),
          onRetryInspection = {},
          lifecycle = PluginLifecyclePresentation(canSetEnabled = true, canUninstall = true),
          management = true,
          onInstall = {},
          onSetEnabled = { enabled = it },
          onUninstall = { removals += 1 },
          onRefreshLifecycle = {},
          onDismissLifecycle = {},
        )
      }
    }

    composeRule.onNodeWithText("Plugin controls").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Allow OpenClaw to load this Plugin.").performClick()
    assertEquals(true, enabled)
    composeRule.onNodeWithText("Remove Plugin").performClick()
    assertEquals(1, removals)
  }

  private fun plugin(status: PluginInstallStatus): PluginCatalogItem =
    PluginCatalogItem(
      pluginId = "calendar",
      displayName = "Calendar",
      summary = "Plan and review events.",
      origin = "official",
      categories = listOf("tool"),
      packageName = "@openclaw/calendar",
      version = "1.0.0",
      kinds = listOf("tool"),
      status = status,
      enabled = false,
      installSource = "official",
      installReference = "calendar",
      removable = true,
      error = null,
    )
}
