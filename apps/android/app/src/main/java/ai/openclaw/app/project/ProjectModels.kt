package ai.openclaw.app.project

import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.supervisor.DevelopmentCapability
import kotlinx.coroutines.flow.StateFlow

internal const val CLAW_PROJECT_SESSION_KIND = "claw-in-one-project"

internal enum class ProjectNameOrigin { Default, Custom }

internal data class ProjectRecord(
  val id: String,
  val displayName: String,
  val repoRoot: String,
  val source: String,
  val agentId: String? = null,
)

internal data class ProjectCreationResult(
  val intentId: String,
  val nameRevision: String,
  val phase: String,
  val naming: ProjectNameOrigin,
  val displayName: String,
  val repoRoot: String,
  val project: ProjectRecord? = null,
)

internal data class ProjectCreateIntent(
  val intentId: String,
  val nameRevision: String,
  val origin: ProjectNameOrigin,
  val requestedName: String,
)

internal data class ProjectGroup(
  val project: ProjectRecord,
  val chats: List<ChatSessionEntry>,
)

internal sealed interface ProjectCatalogState {
  data object Loading : ProjectCatalogState

  data class Ready(
    val revision: Long,
    val projects: List<ProjectRecord>,
    val sessionBindings: Map<String, String> = emptyMap(),
  ) : ProjectCatalogState

  data class Unavailable(
    val retainedProjects: List<ProjectRecord>,
    val sessionBindings: Map<String, String> = emptyMap(),
  ) : ProjectCatalogState
}

internal sealed interface DevelopmentCapabilitiesState {
  data object Loading : DevelopmentCapabilitiesState

  data class Ready(
    val revision: String,
    val capabilities: List<DevelopmentCapability>,
  ) : DevelopmentCapabilitiesState

  data class Unavailable(
    val retainedCapabilities: List<DevelopmentCapability> = emptyList(),
  ) : DevelopmentCapabilitiesState
}

internal data class ProjectDraft(
  val draftId: String,
  val sessionKey: String,
)

internal sealed interface ProjectNameDialogState {
  data object Hidden : ProjectNameDialogState

  data class Editing(
    val draft: ProjectDraft,
    val value: String,
    val origin: ProjectNameOrigin,
    val nameRevision: String,
    val inlineError: String? = null,
    val errorRevision: Long = 0,
  ) : ProjectNameDialogState

  data class Submitting(
    val draft: ProjectDraft,
    val value: String,
    val origin: ProjectNameOrigin,
    val nameRevision: String,
  ) : ProjectNameDialogState

  data class FinishSetup(
    val draft: ProjectDraft,
    val result: ProjectCreationResult,
    val message: String,
  ) : ProjectNameDialogState
}

internal data class ProjectDestinationState(
  val draft: ProjectDraft? = null,
  val dialog: ProjectNameDialogState = ProjectNameDialogState.Hidden,
  val activeProjectId: String? = null,
  val activation: ProjectSendActivation? = null,
  val runConflict: ProjectRunConflict? = null,
)

internal data class ProjectSendActivation(
  val id: String,
  val sessionKey: String,
)

internal data class ProjectRunConflict(
  val projectId: String,
  val ownerSessionKey: String,
  val ownerSessionId: String,
)

internal data class ProjectConnection(
  val gatewayId: String,
  val generation: Long,
  val methods: Set<String>,
  val admin: Boolean,
)

internal interface ProjectTransport {
  fun capture(): ProjectConnection?

  suspend fun request(
    connection: ProjectConnection,
    method: String,
    params: String,
  ): String
}

internal class ProjectActions(
  val newProject: () -> Boolean,
  val newChat: (projectId: String) -> Boolean,
  val selectProject: (projectId: String) -> Unit,
  val requestComposer: () -> Boolean,
  val updateName: (String) -> Unit,
  val confirmName: () -> Unit,
  val dismissName: () -> Unit,
  val finishSetup: () -> Unit,
  val consumeActivation: (String) -> Unit,
  val returnToRunOwner: () -> Unit,
  val refreshCatalog: () -> Unit,
)

internal class ProjectFeature(
  val catalog: StateFlow<ProjectCatalogState>,
  val capabilities: StateFlow<DevelopmentCapabilitiesState>,
  val destination: StateFlow<ProjectDestinationState>,
  val actions: ProjectActions,
)

internal fun projectIdFromSessionKey(key: String): String? {
  val parts = key.split(':')
  val kind = parts.indexOf(CLAW_PROJECT_SESSION_KIND)
  return parts.getOrNull(kind + 1)?.trim()?.takeIf { kind >= 2 && it.isNotEmpty() }
}

internal fun groupProjectChats(
  projects: List<ProjectRecord>,
  sessions: List<ChatSessionEntry>,
  sessionBindings: Map<String, String> = emptyMap(),
): List<ProjectGroup> {
  val chats =
    sessions
      .map { session ->
        val projectId = session.projectId ?: sessionBindings[session.key]
        if (projectId == session.projectId) session else session.copy(projectId = projectId)
      }.filter { !it.projectId.isNullOrBlank() && it.archived != true }
  return projects.map { project ->
    ProjectGroup(
      project = project,
      chats =
        chats
          .filter { it.projectId == project.id }
          .sortedByDescending { it.lastActivityAt ?: it.updatedAtMs ?: 0L },
    )
  }
}
