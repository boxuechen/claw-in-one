package ai.openclaw.app.ui.chat

internal enum class ChatDestinationKind {
  NewProjectDraft,
  ProjectChatDraft,
  MaterializedChat,
  None,
}

internal sealed interface ChatBlankContent {
  data object Conversation : ChatBlankContent

  data class ProjectBootstrap(
    val starters: List<ProjectStarter>,
    val enabled: Boolean,
  ) : ChatBlankContent
}

internal fun resolveChatBlankContent(
  starters: List<ProjectStarter>,
  destinationKind: ChatDestinationKind,
  presentationState: ChatPresentationState,
  historyLoading: Boolean,
  composerPristine: Boolean = true,
  startersEnabled: Boolean = false,
): ChatBlankContent =
  if (
    destinationKind == ChatDestinationKind.NewProjectDraft &&
    !historyLoading &&
    composerPristine &&
    presentationState == ChatPresentationState.BlankHealthy &&
    starters.isNotEmpty()
  ) {
    ChatBlankContent.ProjectBootstrap(starters, startersEnabled)
  } else {
    ChatBlankContent.Conversation
  }
