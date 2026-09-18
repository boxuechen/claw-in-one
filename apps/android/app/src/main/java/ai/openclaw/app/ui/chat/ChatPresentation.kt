package ai.openclaw.app.ui.chat

/** Closed presentation states for the chat surface. Runtime recovery is owned globally. */
internal sealed interface ChatPresentationState {
  val hasConversation: Boolean

  data object BlankHealthy : ChatPresentationState {
    override val hasConversation = false
  }

  data object ConversationIdle : ChatPresentationState {
    override val hasConversation = true
  }

  data object ConversationRunning : ChatPresentationState {
    override val hasConversation = true
  }
}

internal fun resolveChatPresentationState(
  hasContent: Boolean,
  runActive: Boolean,
): ChatPresentationState =
  when {
    hasContent && runActive -> ChatPresentationState.ConversationRunning
    hasContent -> ChatPresentationState.ConversationIdle
    runActive -> ChatPresentationState.ConversationRunning
    else -> ChatPresentationState.BlankHealthy
  }
