package ai.openclaw.app.chat

internal const val CHAT_SESSION_LABEL_MAX_CHARS = 512

internal enum class ChatSessionTitleSource {
  Manual,
  DisplayName,
  Derived,
  Fallback,
}

internal data class ChatSessionTitle(
  val text: String,
  val source: ChatSessionTitleSource,
)

/** One presentation rule shared by every surface that names a Chat Session. */
internal fun resolveChatSessionTitle(
  session: ChatSessionEntry,
  unnamedTitle: () -> String,
): ChatSessionTitle {
  session.label.normalizedSessionTitle()?.let {
    return ChatSessionTitle(it, ChatSessionTitleSource.Manual)
  }
  session.displayName.normalizedGeneratedTitle(session.key)?.let {
    return ChatSessionTitle(it, ChatSessionTitleSource.DisplayName)
  }
  session.derivedTitle.normalizedGeneratedTitle(session.key)?.let {
    return ChatSessionTitle(it, ChatSessionTitleSource.Derived)
  }
  return ChatSessionTitle(
    text = unnamedTitle(),
    source = ChatSessionTitleSource.Fallback,
  )
}

internal fun sessionPresentationTitle(
  session: ChatSessionEntry,
  unnamedTitle: () -> String,
): String = resolveChatSessionTitle(session, unnamedTitle).text

private fun String?.normalizedSessionTitle(): String? =
  this
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun String?.normalizedGeneratedTitle(sessionKey: String): String? = normalizedSessionTitle()?.takeIf { it != sessionKey }

internal fun ChatSessionEntry.isClawInOneConversation(): Boolean {
  if (classification == "dashboard") return true
  val parts = key.split(':', limit = 4)
  return parts.size == 4 &&
    parts[0] == "agent" &&
    parts[2] in setOf("dashboard", "claw-in-one", "claw-in-one-project", "claw-in-one-project-draft")
}
