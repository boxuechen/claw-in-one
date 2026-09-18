package ai.openclaw.app.chat

import kotlinx.coroutines.flow.StateFlow

/** Browse existing conversations. Mutation and delivery have separate owners. */
internal class ChatSessionCatalogFeature(
  val entries: StateFlow<List<ChatSessionEntry>>,
  val refresh: (limit: Int?, archived: Boolean) -> Unit,
  val search: suspend (query: String?, archived: Boolean) -> List<ChatSessionEntry>,
)

/** Mutations retained by the flat conversation model. */
internal class ChatConversationManagementFeature(
  val canSetLabel: () -> Boolean,
  val setLabel: suspend (session: ChatSessionEntry, label: String?) -> Boolean,
  val delete: suspend (session: ChatSessionEntry) -> Boolean,
)

/** One runtime-bound identity for session discovery and navigation. */
internal class ChatDirectoryFeature(
  val catalog: ChatSessionCatalogFeature,
  val navigation: ChatNavigationFeature,
  val management: ChatConversationManagementFeature,
  val titlePreparation: ChatSessionTitlePreparationFeature,
)
