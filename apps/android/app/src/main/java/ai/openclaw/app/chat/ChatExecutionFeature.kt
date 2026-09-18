package ai.openclaw.app.chat

import ai.openclaw.app.permissions.SessionPermissionDraft
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionRef
import ai.openclaw.app.permissions.SessionPermissionTarget
import kotlinx.coroutines.flow.StateFlow

/** Runtime-bound local Session creation and exact-Run Stop controls. */
internal class ChatExecutionFeature(
  val localDrafts: ChatLocalDraftFeature,
  val stops: ChatStopFeature,
)

internal class ChatLocalDraftFeature(
  val states: StateFlow<Map<SessionPermissionTarget, ChatLocalDraft>>,
  private val chooseAction: (SessionPermissionTarget, SessionPermissionMode) -> Boolean,
  private val confirmAction: (SessionPermissionDraft, SessionPermissionRef) -> Boolean,
) {
  fun choose(
    target: SessionPermissionTarget,
    mode: SessionPermissionMode,
  ) = chooseAction(target, mode)

  fun confirm(
    draft: SessionPermissionDraft,
    ref: SessionPermissionRef,
  ) = confirmAction(draft, ref)
}

internal class ChatStopFeature(
  val states: StateFlow<Map<ChatComposerOwner, ChatStopState>>,
  private val abortCurrentAction: () -> Unit,
  private val reconcileAction: (ChatComposerOwner) -> Boolean,
) {
  fun abortCurrent() = abortCurrentAction()

  fun reconcile(owner: ChatComposerOwner) = reconcileAction(owner)
}
