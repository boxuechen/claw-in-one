package ai.openclaw.app.ui.sidebar

import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class SidebarLayoutTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun headerDestinationsProjectListAndFooterKeepTheirVerticalRegions() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        Box(Modifier.size(width = 360.dp, height = 800.dp).testTag("drawer-viewport")) {
          SidebarScreen(state = populatedState(), onAction = {})
        }
      }
    }

    val viewport = composeRule.onNodeWithTag("drawer-viewport").getUnclippedBoundsInRoot()
    val search = composeRule.onNodeWithTag("drawer-search").getUnclippedBoundsInRoot()
    val devKit = composeRule.onNodeWithTag("drawer-destination-devkit").getUnclippedBoundsInRoot()
    val projects = composeRule.onNodeWithTag("drawer-project-list").getUnclippedBoundsInRoot()
    val newProject = composeRule.onNodeWithTag("drawer-new-project").getUnclippedBoundsInRoot()
    val settings = composeRule.onNodeWithTag("drawer-open-settings").getUnclippedBoundsInRoot()

    assertTrue(search.top < viewport.top + 80.dp)
    assertTrue(devKit.top < projects.top)
    assertTrue(newProject.bottom > viewport.bottom - 80.dp)
    assertTrue(settings.bottom > viewport.bottom - 80.dp)
    assertTrue(newProject.left < settings.left)
  }

  @Test
  fun compactNewChatGlyphRetainsAFullTouchTarget() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        Box(Modifier.size(width = 360.dp, height = 800.dp)) {
          SidebarScreen(state = populatedState(), onAction = {})
        }
      }
    }

    val bounds = composeRule.onNodeWithTag("project-new-chat-project-1").getUnclippedBoundsInRoot()
    assertEquals(sidebarActionTouchTarget, bounds.right - bounds.left)
    assertEquals(sidebarActionTouchTarget, bounds.bottom - bounds.top)
    assertEquals(20.dp, sidebarNewChatIconSize)
    assertEquals(26.dp, sidebarProjectFolderIconSize)
  }

  private fun populatedState() =
    SidebarUiState(
      searchOpen = false,
      searchQuery = "",
      projects =
        listOf(
          SidebarProjectUi(
            id = "project-1",
            displayName = "Demo Project",
            expanded = true,
            chats =
              listOf(
                SidebarChatUi(
                  session = ChatSessionEntry(key = "chat-1", displayName = "First chat", updatedAtMs = 0),
                  selected = true,
                  approvalCount = 0,
                ),
              ),
          ),
        ),
    )
}
