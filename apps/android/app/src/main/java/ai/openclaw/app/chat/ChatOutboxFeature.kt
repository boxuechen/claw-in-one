package ai.openclaw.app.chat

import kotlinx.coroutines.flow.StateFlow

/** Runtime-bound durable-delivery presentation and explicit recovery actions. */
internal class ChatOutboxFeature(
  val items: StateFlow<List<ChatOutboxItem>>,
  val presentationRestored: StateFlow<Boolean>,
  val retry: (id: String) -> Unit,
  val delete: (id: String) -> Unit,
)
