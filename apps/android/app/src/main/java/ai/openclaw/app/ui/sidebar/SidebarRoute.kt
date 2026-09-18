package ai.openclaw.app.ui.sidebar

import ai.openclaw.app.approval.ApprovalInboxState
import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.chat.ChatSessionCatalogFeature
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.project.ProjectCatalogState
import ai.openclaw.app.project.ProjectFeature
import ai.openclaw.app.project.ProjectRecord
import ai.openclaw.app.project.groupProjectChats
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Adapts runtime owners and drawer-only presentation state into one immutable UI contract. */
@Composable
internal fun SidebarRoute(
  catalog: ChatSessionCatalogFeature?,
  projects: ProjectFeature?,
  approvals: ApprovalInboxState,
  activeSessionKey: String,
  onOpenSettings: () -> Unit,
  onNewProject: () -> Unit,
  onNewChatInProject: (String) -> Unit,
  onSelectSession: (ChatSessionEntry) -> Unit,
  onSelectDestination: (SidebarDestination) -> Unit,
) {
  val sessions =
    key(catalog) {
      catalog
        ?.entries
        ?.collectAsState()
        ?.value
        .orEmpty()
    }
  val projectCatalog = projects?.catalog?.collectAsState()?.value
  var searchOpen by rememberSaveable { mutableStateOf(false) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var collapsedProjectIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

  val records =
    when (projectCatalog) {
      is ProjectCatalogState.Ready -> projectCatalog.projects to projectCatalog.sessionBindings
      is ProjectCatalogState.Unavailable -> projectCatalog.retainedProjects to projectCatalog.sessionBindings
      ProjectCatalogState.Loading,
      null,
      -> emptyList<ProjectRecord>() to emptyMap()
    }
  val groups =
    remember(records, sessions) {
      groupProjectChats(
        projects = records.first,
        sessions = sessions,
        sessionBindings = records.second,
      )
    }
  val approvalCounts =
    remember(sessions, approvals) {
      sessions.associate { session ->
        ApprovalSession(session.key, session.ownerAgentId).let { it to approvals.pendingFor(it).size }
      }
    }
  val state =
    SidebarUiState(
      searchOpen = searchOpen,
      searchQuery = searchQuery,
      projects =
        sidebarProjects(
          groups = groups,
          activeSessionKey = activeSessionKey,
          collapsedProjectIds = collapsedProjectIds.toSet(),
          query = searchQuery.takeIf { searchOpen }.orEmpty(),
          approvalCounts = approvalCounts,
        ),
      projectsLoading = projectCatalog is ProjectCatalogState.Loading,
    )

  SidebarScreen(state = state) { action ->
    when (action) {
      SidebarAction.ToggleSearch -> {
        searchOpen = !searchOpen
        if (!searchOpen) searchQuery = ""
      }
      is SidebarAction.UpdateSearch -> searchQuery = action.query
      is SidebarAction.ToggleProjectChats -> {
        collapsedProjectIds =
          if (action.projectId in collapsedProjectIds) {
            collapsedProjectIds - action.projectId
          } else {
            collapsedProjectIds + action.projectId
          }
      }
      is SidebarAction.NewChatInProject -> onNewChatInProject(action.projectId)
      is SidebarAction.OpenChat -> onSelectSession(action.session)
      is SidebarAction.OpenDestination -> onSelectDestination(action.destination)
      SidebarAction.NewProject -> onNewProject()
      SidebarAction.OpenSettings -> onOpenSettings()
    }
  }
}
