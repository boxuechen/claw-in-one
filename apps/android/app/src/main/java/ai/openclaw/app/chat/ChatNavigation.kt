package ai.openclaw.app.chat

/** Persistable navigation evidence, captured by the existing selection owner. */
internal data class ChatNavigationSelection(
  val gatewayId: String,
  val sessionKey: String,
  val ownerAgentId: String?,
)

internal class ChatNavigationFeature(
  val select: (sessionKey: String, ownerAgentId: String?) -> Unit,
  val newChat: () -> Boolean,
)

/** Coordinates navigation effects without owning selection, Preview, drafts, or stored preferences. */
internal class ChatNavigation(
  private val selectSession: (String, String?) -> Unit,
  private val newDraft: () -> Boolean,
  private val captureSelection: () -> ChatNavigationSelection?,
  private val saveSelection: (ChatNavigationSelection) -> Unit,
) {
  val feature = ChatNavigationFeature(::select, ::newChat)

  private fun select(
    sessionKey: String,
    ownerAgentId: String?,
  ) {
    if (sessionKey.isBlank()) return
    selectSession(sessionKey, ownerAgentId)
    captureSelection()?.let(saveSelection)
  }

  private fun newChat(): Boolean = newDraft()
}
