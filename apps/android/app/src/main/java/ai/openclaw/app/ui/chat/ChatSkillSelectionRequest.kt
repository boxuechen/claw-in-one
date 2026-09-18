package ai.openclaw.app.ui.chat

/** One explicit navigation result; Chat owns committing it into the current draft. */
internal data class ChatSkillSelectionRequest(
  val id: Long,
  val reference: String,
)
