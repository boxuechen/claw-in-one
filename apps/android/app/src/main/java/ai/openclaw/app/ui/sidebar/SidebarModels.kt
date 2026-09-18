package ai.openclaw.app.ui.sidebar

import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.chat.isClawInOneConversation
import ai.openclaw.app.chat.sessionPresentationTitle
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.project.ProjectGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal enum class SidebarDestination(
  val icon: ImageVector,
) {
  DevKit(icon = Icons.Outlined.Build),
  Plugins(icon = Icons.Outlined.Extension),
  VScreen(icon = Icons.Outlined.PhoneAndroid),
  Terminal(icon = Icons.Outlined.Terminal),
}

internal fun SidebarDestination.localizedLabel(): String =
  when (this) {
    SidebarDestination.DevKit -> nativeString("DevKit")
    SidebarDestination.Plugins -> nativeString("Plugins")
    SidebarDestination.VScreen -> nativeString("VScreen")
    SidebarDestination.Terminal -> nativeString("Terminal")
  }

internal val sidebarPrimaryDestinations =
  listOf(
    SidebarDestination.DevKit,
    SidebarDestination.Plugins,
    SidebarDestination.VScreen,
    SidebarDestination.Terminal,
  )

internal val sidebarProjectFolderIconSize = 26.dp
internal val sidebarNewChatIconSize = 20.dp
internal val sidebarActionTouchTarget = 48.dp

internal data class SidebarChatUi(
  val session: ChatSessionEntry,
  val selected: Boolean,
  val approvalCount: Int,
)

internal data class SidebarProjectUi(
  val id: String,
  val displayName: String,
  val expanded: Boolean,
  val chats: List<SidebarChatUi>,
)

/** Complete immutable input for the presentation-only drawer. */
internal data class SidebarUiState(
  val searchOpen: Boolean,
  val searchQuery: String,
  val projects: List<SidebarProjectUi> = emptyList(),
  val projectsLoading: Boolean = false,
)

internal sealed interface SidebarAction {
  data object ToggleSearch : SidebarAction

  data class UpdateSearch(
    val query: String,
  ) : SidebarAction

  data class ToggleProjectChats(
    val projectId: String,
  ) : SidebarAction

  data class NewChatInProject(
    val projectId: String,
  ) : SidebarAction

  data class OpenChat(
    val session: ChatSessionEntry,
  ) : SidebarAction

  data class OpenDestination(
    val destination: SidebarDestination,
  ) : SidebarAction

  data object NewProject : SidebarAction

  data object OpenSettings : SidebarAction
}

internal fun sidebarProjects(
  groups: List<ProjectGroup>,
  activeSessionKey: String,
  collapsedProjectIds: Set<String>,
  query: String,
  approvalCounts: Map<ApprovalSession, Int>,
): List<SidebarProjectUi> {
  val term = query.trim()
  return groups
    .mapIndexed { sourceIndex, group -> IndexedProjectGroup(sourceIndex, group.withSortedChats()) }
    .sortedWith(
      compareByDescending<IndexedProjectGroup> { it.latestActivityAt }
        .thenBy { it.sourceIndex },
    ).mapNotNull { indexed ->
      val group = indexed.group
      val visibleChats =
        when {
          term.isEmpty() || group.project.displayName.contains(term, ignoreCase = true) -> group.chats
          else -> group.chats.filter { sidebarSessionTitle(it).contains(term, ignoreCase = true) }
        }
      if (term.isNotEmpty() && visibleChats.isEmpty() && !group.project.displayName.contains(term, ignoreCase = true)) {
        null
      } else {
        SidebarProjectUi(
          id = group.project.id,
          displayName = group.project.displayName,
          expanded = term.isNotEmpty() || group.project.id !in collapsedProjectIds,
          chats =
            visibleChats.map { session ->
              SidebarChatUi(
                session = session,
                selected = session.key == activeSessionKey,
                approvalCount = approvalCounts[ApprovalSession(session.key, session.ownerAgentId)] ?: 0,
              )
            },
        )
      }
    }
}

private data class IndexedProjectGroup(
  val sourceIndex: Int,
  val group: ProjectGroup,
) {
  val latestActivityAt: Long = group.chats.maxOfOrNull(ChatSessionEntry::sidebarActivityAt) ?: Long.MIN_VALUE
}

private fun ProjectGroup.withSortedChats(): ProjectGroup =
  copy(
    chats =
      chats.sortedWith(
        compareByDescending<ChatSessionEntry>(ChatSessionEntry::sidebarActivityAt)
          .thenBy(ChatSessionEntry::key),
      ),
  )

private fun ChatSessionEntry.sidebarActivityAt(): Long = lastActivityAt ?: updatedAtMs ?: 0L

internal fun sidebarSessionTitle(session: ChatSessionEntry): String =
  sessionPresentationTitle(session) {
    if (session.isClawInOneConversation()) nativeString("New chat") else session.key
  }

/** Shared priority rule for compact session status text outside the drawer row. */
internal fun sessionListSubtitle(
  session: ChatSessionEntry,
  fallback: String,
  nowMs: Long = System.currentTimeMillis(),
): String {
  val agentStatus = session.agentStatus?.takeIf { it.expiresAt > nowMs && it.note.isNotBlank() }
  val declaredAttention = agentStatus?.takeIf { it.attention != null }?.note
  val runStatus = session.status?.trim()?.lowercase()
  val failureAt = session.endedAt ?: session.updatedAtMs ?: 0L
  val failedAttention =
    session.lastRunError
      ?.trim()
      ?.takeIf { it.isNotEmpty() && (runStatus == "failed" || runStatus == "timeout") && (session.lastReadAt ?: 0L) < failureAt }
  val digest = session.observerDigest
  val running = session.hasActiveRun == true || runStatus == "running"
  val digestMatchesActiveRun =
    digest
      ?.runId
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?.let { runId -> session.activeRunIds.orEmpty().any { it.trim() == runId } } == true
  val finalDigestUnread =
    digest != null &&
      (digest.health == "done" || digest.health == "failed") &&
      (session.lastReadAt ?: 0L) < digest.updatedAt
  val observer = digest?.headline?.takeIf { (running && digestMatchesActiveRun) || (!running && finalDigestUnread) }
  val queued = nativeString("Waiting for a concurrency slot").takeIf { runStatus == "queued" }
  return declaredAttention ?: failedAttention ?: agentStatus?.note ?: queued ?: observer ?: fallback
}
