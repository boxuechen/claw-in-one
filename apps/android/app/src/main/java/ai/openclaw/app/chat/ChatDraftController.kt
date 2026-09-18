package ai.openclaw.app.chat

import ai.openclaw.app.permissions.SessionPermissionDraft
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionRef
import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.project.CLAW_PROJECT_SESSION_KIND
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal enum class ChatDraftPhase { Editing, Creating, Unconfirmed, Created }

internal data class ChatLocalDraft(
  val intent: SessionPermissionDraft,
  val phase: ChatDraftPhase = ChatDraftPhase.Editing,
  val createdRef: SessionPermissionRef? = null,
) {
  val isLocal get() = phase != ChatDraftPhase.Created
  val editable get() = phase == ChatDraftPhase.Editing
}

/** Local creation intent only. Composer owns input/attachments; Gateway owns applied permissions.
 * The reserved key is also the eventual composer owner, so first send never migrates attachments.
 */
internal class ChatDraftController(
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val lock = Any()
  private val mutableStates = MutableStateFlow<Map<SessionPermissionTarget, ChatLocalDraft>>(emptyMap())
  val states = mutableStates.asStateFlow()

  fun newDraft(
    gatewayId: String,
    agentId: String,
    projectId: String? = null,
    projectRoot: String? = null,
  ): ChatLocalDraft =
    synchronized(lock) {
      val namespace = projectId?.let { "$CLAW_PROJECT_SESSION_KIND:$it" } ?: "claw-in-one-project-draft"
      val target = SessionPermissionTarget(gatewayId, "agent:$agentId:$namespace:${newId()}", agentId)
      check(target !in mutableStates.value)
      ChatLocalDraft(
        SessionPermissionDraft(
          target = target,
          idempotencyKey = newId(),
          mode = if (projectId == null) SessionPermissionMode.Standard else SessionPermissionMode.Workspace,
          projectId = projectId,
          projectRoot = projectRoot,
        ),
      ).also { put(it) }
    }

  fun find(
    gatewayId: String?,
    key: String,
  ): ChatLocalDraft? = states.value.values.firstOrNull { it.intent.target.gatewayId == gatewayId && it.intent.target.key == key }

  fun pristineProjectDraft(
    gatewayId: String,
    projectId: String,
  ): ChatLocalDraft? =
    states.value.values.firstOrNull {
      it.intent.target.gatewayId == gatewayId &&
        it.intent.projectId == projectId &&
        it.isLocal
    }

  fun choose(
    target: SessionPermissionTarget,
    mode: SessionPermissionMode,
  ): Boolean {
    require(mode == SessionPermissionMode.Standard || mode == SessionPermissionMode.Full)
    return edit(target) { it.copy(mode = mode) }
  }

  fun bindProject(
    target: SessionPermissionTarget,
    projectId: String,
    projectRoot: String,
  ): Boolean {
    require(projectId.isNotBlank() && projectRoot.isNotBlank())
    return edit(target) {
      it.copy(
        mode = SessionPermissionMode.Workspace,
        projectId = projectId,
        projectRoot = projectRoot,
      )
    }
  }

  fun selectModel(
    target: SessionPermissionTarget,
    modelRef: String?,
  ): Boolean = edit(target) { it.copy(modelRef = modelRef?.trim()?.takeIf(String::isNotEmpty)) }

  fun selectThinking(
    target: SessionPermissionTarget,
    thinking: String,
  ): Boolean = edit(target) { it.copy(thinkingLevel = thinking) }

  private fun edit(
    target: SessionPermissionTarget,
    change: (SessionPermissionDraft) -> SessionPermissionDraft,
  ): Boolean =
    synchronized(lock) {
      val draft = mutableStates.value[target]?.takeIf { it.editable } ?: return false
      put(draft.copy(intent = change(draft.intent)))
      true
    }

  /** Called only from explicit first-send admission. Unknown attempts reuse the exact payload. */
  fun beginCreation(
    target: SessionPermissionTarget,
    preparedDisplayName: String? = null,
  ): SessionPermissionDraft? =
    synchronized(lock) {
      val draft = mutableStates.value[target] ?: return null
      if (draft.phase == ChatDraftPhase.Creating || draft.phase == ChatDraftPhase.Created) return null
      val intent =
        preparedDisplayName
          ?.trim()
          ?.takeIf(String::isNotEmpty)
          ?.let { draft.intent.copy(displayName = it) }
          ?: draft.intent
      put(draft.copy(intent = intent, phase = ChatDraftPhase.Creating))
      intent
    }

  fun unconfirmed(intent: SessionPermissionDraft): Unit =
    synchronized(lock) {
      val draft = mutableStates.value[intent.target] ?: return
      if (draft.intent == intent && draft.phase == ChatDraftPhase.Creating) put(draft.copy(phase = ChatDraftPhase.Unconfirmed))
    }

  /** A canonical creation result is not message acceptance and never clears the composer. */
  fun confirm(
    intent: SessionPermissionDraft,
    ref: SessionPermissionRef,
  ): Boolean =
    synchronized(lock) {
      val draft = mutableStates.value[intent.target] ?: return false
      if (draft.intent != intent || ref.target != intent.target || ref.connection.gatewayId != intent.target.gatewayId) return false
      if (draft.phase !in setOf(ChatDraftPhase.Creating, ChatDraftPhase.Unconfirmed)) return false
      put(draft.copy(phase = ChatDraftPhase.Created, createdRef = ref))
      true
    }

  private fun put(draft: ChatLocalDraft) {
    mutableStates.value = mutableStates.value + (draft.intent.target to draft)
  }
}
