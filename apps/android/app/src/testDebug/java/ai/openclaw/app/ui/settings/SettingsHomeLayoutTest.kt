package ai.openclaw.app.ui.settings

import ai.openclaw.app.i18n.verbatimText
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h600dp-420dpi")
class SettingsHomeLayoutTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun closeControlStaysFixedWhileSettingsContentScrolls() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        Box(Modifier.size(width = 360.dp, height = 600.dp)) {
          SettingsHomeScreen(
            state =
              SettingsOverviewUiState(
                sections =
                  listOf(
                    SettingsSection(
                      title = "Connection",
                      rows =
                        List(14) { index ->
                          SettingsRow(
                            title = verbatimText("Item $index"),
                            value = verbatimText("Value"),
                            icon = Icons.Default.Settings,
                            route = SettingsDestination.Appearance,
                          )
                        },
                    ),
                  ),
                versionLabel = "version",
                statusLabel = "status",
                statusNeedsAttention = false,
              ),
            onRouteChange = {},
            onClose = {},
          )
        }
      }
    }

    composeRule.onNodeWithText("Settings").assertIsDisplayed()
    val close = composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    val initialBounds = close.getUnclippedBoundsInRoot()

    repeat(3) {
      composeRule.onNodeWithTag("settings-list").performTouchInput { swipeUp(durationMillis = 400) }
    }

    close.assertIsDisplayed()
    assertEquals(initialBounds, close.getUnclippedBoundsInRoot())
  }
}
