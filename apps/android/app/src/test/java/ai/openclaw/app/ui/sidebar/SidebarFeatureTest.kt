package ai.openclaw.app.ui.sidebar

import ai.openclaw.app.approval.ApprovalInboxState
import ai.openclaw.app.chat.ChatSessionCatalogFeature
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.project.DevelopmentCapabilitiesState
import ai.openclaw.app.project.ProjectActions
import ai.openclaw.app.project.ProjectCatalogState
import ai.openclaw.app.project.ProjectDestinationState
import ai.openclaw.app.project.ProjectFeature
import ai.openclaw.app.project.ProjectRecord
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SidebarFeatureTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun routeReplacesItsCatalogAndBottomActionCreatesAProject() {
    val first = catalog(listOf(chat("First chat", "project-1", 1)))
    val second = catalog(listOf(chat("Second chat", "project-1", 2)))
    var source by mutableStateOf<ChatSessionCatalogFeature?>(first)
    val selected = mutableListOf<String>()
    var newProjects = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        Box(Modifier.size(360.dp, 800.dp)) {
          SidebarRoute(
            catalog = source,
            projects = projectFeature(),
            approvals = ApprovalInboxState(),
            activeSessionKey = "",
            onOpenSettings = {},
            onNewProject = { newProjects++ },
            onNewChatInProject = {},
            onSelectSession = { selected += it.key },
            onSelectDestination = {},
          )
        }
      }
    }

    composeRule.onNodeWithText("First chat").performClick()
    composeRule.onNodeWithTag("drawer-new-project").performClick()
    assertEquals(listOf("First chat"), selected)
    assertEquals(1, newProjects)

    composeRule.runOnIdle { source = second }
    composeRule.onNodeWithText("First chat").assertDoesNotExist()
    composeRule.onNodeWithText("Second chat").assertIsDisplayed()
    composeRule.runOnIdle { source = null }
    composeRule.onNodeWithText("Second chat").assertDoesNotExist()
  }

  @Test
  fun projectHeaderOnlyTogglesItsChatsAndNewChatKeepsExactProject() {
    val catalog = catalog(listOf(chat("First chat", "project-1", 1)))
    val projects = projectFeature()
    val projectChats = mutableListOf<String>()
    val selected = mutableListOf<String>()
    composeRule.setContent {
      ProvideClawDesignSystem {
        Box(Modifier.size(360.dp, 800.dp)) {
          SidebarRoute(
            catalog = catalog,
            projects = projects,
            approvals = ApprovalInboxState(),
            activeSessionKey = "",
            onOpenSettings = {},
            onNewProject = {},
            onNewChatInProject = { projectChats += it },
            onSelectSession = { selected += it.key },
            onSelectDestination = {},
          )
        }
      }
    }

    composeRule.onNodeWithText("First chat").assertIsDisplayed()
    composeRule.onNodeWithTag("project-row-project-1").performClick()
    composeRule.onNodeWithText("First chat").assertDoesNotExist()
    assertEquals(emptyList<String>(), selected)
    composeRule.onNodeWithContentDescription("New Chat in Demo Project").performClick()
    assertEquals(listOf("project-1"), projectChats)
  }

  @Test
  fun openingAndClosingSearchPreservesTheCollapseChoice() {
    val catalog = catalog(listOf(chat("Fix keyboard", "project-1", 2)))
    val projects = projectFeature(project())
    composeRule.setContent {
      ProvideClawDesignSystem {
        Box(Modifier.size(360.dp, 1_200.dp)) {
          SidebarRoute(
            catalog = catalog,
            projects = projects,
            approvals = ApprovalInboxState(),
            activeSessionKey = "",
            onOpenSettings = {},
            onNewProject = {},
            onNewChatInProject = {},
            onSelectSession = {},
            onSelectDestination = {},
          )
        }
      }
    }

    composeRule.onNodeWithTag("project-row-project-1").performClick()
    composeRule.onNodeWithText("Fix keyboard").assertDoesNotExist()
    composeRule.onNodeWithTag("drawer-search").performClick()
    composeRule.onNodeWithTag("sidebar-search").assertExists()
    composeRule.onNodeWithTag("drawer-search").performClick()
    composeRule.onNodeWithText("Fix keyboard").assertDoesNotExist()
  }

  @Test
  fun largeTextKeepsFooterActionsAccessible() {
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
        ProvideClawDesignSystem {
          Box(Modifier.size(360.dp, 800.dp)) {
            SidebarScreen(
              state = SidebarUiState(searchOpen = false, searchQuery = ""),
              onAction = {},
            )
          }
        }
      }
    }

    composeRule.onNodeWithTag("drawer-new-project").assertIsDisplayed()
    composeRule.onNodeWithTag("drawer-open-settings").assertIsDisplayed()
  }

  private fun catalog(entries: List<ChatSessionEntry>) = ChatSessionCatalogFeature(MutableStateFlow(entries), { _, _ -> }) { _, _ -> emptyList() }

  private companion object {
    fun chat(
      title: String,
      projectId: String,
      activity: Long,
    ) = ChatSessionEntry(
      key = title,
      projectId = projectId,
      updatedAtMs = activity,
      lastActivityAt = activity,
      displayName = title,
    )

    fun project(
      id: String = "project-1",
      name: String = "Demo Project",
    ) = ProjectRecord(id, name, "/tmp/$id", "registered")

    fun projectFeature(vararg projects: ProjectRecord): ProjectFeature =
      ProjectFeature(
        catalog = MutableStateFlow(ProjectCatalogState.Ready(1, projects.toList().ifEmpty { listOf(project()) })),
        capabilities = MutableStateFlow(DevelopmentCapabilitiesState.Loading),
        destination = MutableStateFlow(ProjectDestinationState()),
        actions =
          ProjectActions(
            newProject = { true },
            newChat = { true },
            selectProject = {},
            requestComposer = { true },
            updateName = {},
            confirmName = {},
            dismissName = {},
            finishSetup = {},
            consumeActivation = {},
            returnToRunOwner = {},
            refreshCatalog = {},
          ),
      )
  }
}
