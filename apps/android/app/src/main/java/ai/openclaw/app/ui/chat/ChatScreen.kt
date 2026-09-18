package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatDraftPhase
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.chat.ChatProgressCard
import ai.openclaw.app.chat.ChatRunActivity
import ai.openclaw.app.chat.ChatStopPhase
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.permissions.SessionPermissionFailure
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionPhase
import ai.openclaw.app.permissions.SessionPermissionRef
import ai.openclaw.app.permissions.SessionPermissionsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class ChatScreenState(
  val header: ChatHeaderState,
  val timeline: ChatTimelineSupplement = ChatTimelineSupplement(),
)

internal data class ChatPermissionPickerState(
  val confirmedMode: SessionPermissionMode,
  val requestedMode: SessionPermissionMode? = null,
  val enabledModes: Set<SessionPermissionMode> = emptySet(),
  val canOpen: Boolean = false,
  val fullRequiresAdmin: Boolean = false,
  val statusMessage: String? = null,
  val recoveryLabel: String? = null,
) {
  val displayedMode get() = requestedMode ?: confirmedMode
  val applying get() = requestedMode != null
}

internal data class ChatTimelineSupplement(
  val attentions: List<ChatAttentionState> = emptyList(),
  val currentWork: ChatCurrentWorkUiState? = null,
  val results: List<ChatResultState> = emptyList(),
)

internal data class ChatCurrentWorkUiState(
  val progress: ChatProgressCard?,
  val tools: List<ChatPendingToolCall>,
  val commentary: List<String> = emptyList(),
)

internal enum class ChatAttentionTone {
  Blocking,
  Warning,
  Status,
}

internal enum class ChatAttentionAction {
  RefreshChat,
  ReturnToRunOwner,
  CheckPermissions,
  StopForPermissions,
  CheckStop,
  StopChat,
}

internal data class ChatAttentionButton(
  val label: String,
  val action: ChatAttentionAction,
)

internal data class ChatAttentionState(
  val key: String,
  val title: String,
  val body: String,
  val tone: ChatAttentionTone,
  val primaryAction: ChatAttentionButton? = null,
  val secondaryAction: ChatAttentionButton? = null,
)

internal enum class ChatResultKind {
  AndroidApp,
  WebApp,
}

internal data class ChatResultState(
  val key: String,
  val kind: ChatResultKind,
  val label: String,
  val opening: Boolean,
  val error: String? = null,
  val warning: String? = null,
)

internal data class ChatScreenActions(
  val openSidebar: () -> Unit,
  val openModelPicker: () -> Unit,
  val openPermissionPicker: () -> Unit,
  val openRenameDialog: () -> Unit,
  val selectThinkingLevel: (String) -> Unit,
  val refreshChat: () -> Unit,
  val handleAttention: (ChatAttentionAction) -> Unit,
  val openResult: (ChatResultState) -> Unit,
)

@Composable
internal fun ChatScreen(
  state: ChatScreenState,
  actions: ChatScreenActions,
  timeline: @Composable (Modifier, ChatTimelineSupplement, ChatScreenActions) -> Unit,
  composer: @Composable () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ChatHeader(
      state = state.header,
      onOpenSidebar = actions.openSidebar,
      onOpenModelPicker = actions.openModelPicker,
      onOpenPermissionPicker = actions.openPermissionPicker,
      onOpenRenameDialog = actions.openRenameDialog,
      onThinkingLevelChange = actions.selectThinkingLevel,
      onRefresh = actions.refreshChat,
    )
    timeline(Modifier.weight(1f), state.timeline, actions)
    composer()
  }
}

internal fun resolveChatCurrentWorkUiState(
  pendingRunCount: Int,
  progress: ChatProgressCard?,
  tools: List<ChatPendingToolCall>,
  activeRunId: String? = null,
  runActivity: ChatRunActivity? = null,
): ChatCurrentWorkUiState? =
  if (pendingRunCount > 0) {
    ChatCurrentWorkUiState(
      progress = progress,
      tools = tools,
      commentary =
        runActivity
          ?.takeIf { it.runId == activeRunId }
          ?.commentary
          ?.map { it.text }
          .orEmpty(),
    )
  } else {
    null
  }

internal fun chatHistoryAttention(body: String): ChatAttentionState =
  ChatAttentionState(
    key = "history",
    title = nativeString("Chat needs attention"),
    body = body,
    tone = ChatAttentionTone.Blocking,
    primaryAction = ChatAttentionButton(nativeString("Try again"), ChatAttentionAction.RefreshChat),
  )

internal fun chatTaskWarning(body: String): ChatAttentionState =
  ChatAttentionState(
    key = "task-warning",
    title = nativeString("Optional presentation unavailable"),
    body = body,
    tone = ChatAttentionTone.Warning,
  )

internal fun chatRunConflictAttention(): ChatAttentionState =
  ChatAttentionState(
    key = "project-run-conflict",
    title = nativeString("This project is active in another Chat"),
    body = nativeString("Your message was not sent."),
    tone = ChatAttentionTone.Blocking,
    primaryAction = ChatAttentionButton(nativeString("Return to active Chat"), ChatAttentionAction.ReturnToRunOwner),
  )

internal fun resolveChatStopAttention(
  phase: ChatStopPhase?,
  connected: Boolean,
): ChatAttentionState? =
  when (phase) {
    ChatStopPhase.Stopping ->
      ChatAttentionState(
        key = "stop-status",
        title = nativeString("Stopping this chat…"),
        body = nativeString("OpenClaw is releasing the current task."),
        tone = ChatAttentionTone.Status,
      )
    ChatStopPhase.Unconfirmed ->
      ChatAttentionState(
        key = "stop-status",
        title = nativeString("Stop is not confirmed"),
        body = nativeString("Check the task state before continuing."),
        tone = ChatAttentionTone.Blocking,
        primaryAction =
          ChatAttentionButton(nativeString("Check stop status"), ChatAttentionAction.CheckStop)
            .takeIf { connected },
        secondaryAction =
          ChatAttentionButton(nativeString("Stop this chat"), ChatAttentionAction.StopChat)
            .takeIf { connected },
      )
    else -> null
  }

internal fun resolveChatPermissionAttention(
  localDraftPhase: ChatDraftPhase?,
  permissionState: SessionPermissionsState?,
  connected: Boolean,
): ChatAttentionState? {
  if (localDraftPhase == ChatDraftPhase.Unconfirmed || permissionState?.phase == SessionPermissionPhase.Unconfirmed) {
    return ChatAttentionState(
      key = "chat-permission",
      title = nativeString("Chat permissions"),
      body = nativeString("Permissions are not confirmed. Stop the task before checking again."),
      tone = ChatAttentionTone.Blocking,
      primaryAction =
        ChatAttentionButton(nativeString("Stop and check permissions"), ChatAttentionAction.StopForPermissions)
          .takeIf { connected },
    )
  }

  if (permissionState?.phase == SessionPermissionPhase.Applying) {
    return ChatAttentionState(
      key = "chat-permission",
      title = nativeString("Applying permissions…"),
      body = nativeString("Choose once for this chat. The choice stays in effect for follow-up work until you change it."),
      tone = ChatAttentionTone.Status,
    )
  }

  if (permissionState?.phase == SessionPermissionPhase.Unavailable) {
    return ChatAttentionState(
      key = "chat-permission",
      title = nativeString("Chat permissions"),
      body = chatPermissionFailureBody(permissionState.failure),
      tone = ChatAttentionTone.Blocking,
      primaryAction =
        ChatAttentionButton(nativeString("Check status"), ChatAttentionAction.CheckPermissions)
          .takeIf { connected },
    )
  }

  if (permissionState?.failure != null) {
    return ChatAttentionState(
      key = "chat-permission",
      title = nativeString("Chat permissions"),
      body = chatPermissionFailureBody(permissionState.failure),
      tone = ChatAttentionTone.Blocking,
      primaryAction =
        ChatAttentionButton(nativeString("Check status"), ChatAttentionAction.CheckPermissions)
          .takeIf { connected },
    )
  }

  return null
}

internal fun resolveChatPermissionPickerState(
  localDraftMode: SessionPermissionMode?,
  permissionState: SessionPermissionsState?,
  connected: Boolean,
): ChatPermissionPickerState? {
  if (localDraftMode != null) {
    return ChatPermissionPickerState(confirmedMode = localDraftMode)
  }
  val confirmedMode = permissionState?.confirmedMode ?: return null
  val ref: SessionPermissionRef? = permissionState.ref
  val connection = ref?.connection
  val canMutate =
    permissionState.phase == SessionPermissionPhase.Ready &&
      permissionState.requestedMode == null &&
      permissionState.failure == null &&
      connection != null &&
      "sessions.patch" in connection.methods
  val statusMessage =
    when {
      permissionState.phase == SessionPermissionPhase.Unconfirmed ->
        nativeString("Permissions are not confirmed. Stop the task before checking again.")
      permissionState.phase == SessionPermissionPhase.Unavailable || permissionState.failure != null ->
        chatPermissionFailureBody(permissionState.failure)
      ref == null -> nativeString("Permissions unavailable. Check the connection.")
      else -> null
    }
  return ChatPermissionPickerState(
    confirmedMode = confirmedMode,
    requestedMode = permissionState.requestedMode,
    canOpen = true,
    fullRequiresAdmin = connection != null && "operator.admin" !in connection.scopes,
    statusMessage = statusMessage,
    recoveryLabel =
      if (statusMessage != null && connected) {
        if (permissionState.phase == SessionPermissionPhase.Unconfirmed) {
          nativeString("Stop and check permissions")
        } else {
          nativeString("Check status")
        }
      } else {
        null
      },
    enabledModes =
      connection
        ?.takeIf { canMutate }
        ?.let { current -> SessionPermissionMode.entries.filterTo(linkedSetOf()) { current.canChoose(it) } }
        .orEmpty(),
  )
}

internal fun chatPermissionFailureBody(failure: SessionPermissionFailure?): String =
  nativeString(
    when (failure) {
      SessionPermissionFailure.IdentityChanged -> "This chat changed. Review its permissions before choosing again."
      SessionPermissionFailure.MissingAuthority -> "This connection cannot change the requested permissions."
      SessionPermissionFailure.Unsupported -> "This runtime does not expose chat permissions."
      SessionPermissionFailure.Disconnected -> "Permissions unavailable. Check the connection."
      SessionPermissionFailure.ChangeRejected -> "The permission change was rejected. Current permissions are unchanged."
      SessionPermissionFailure.ChangeUnconfirmed -> "Permissions are not confirmed. Stop the task before checking again."
      SessionPermissionFailure.ReadFailed -> "Permissions unavailable. Check the connection."
      null -> "Check the connection, then try again."
    },
  )
