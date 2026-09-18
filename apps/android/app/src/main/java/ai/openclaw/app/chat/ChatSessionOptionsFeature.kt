package ai.openclaw.app.chat

import ai.openclaw.app.ai.ModelCatalogState
import kotlinx.coroutines.flow.StateFlow

/** Selected-Chat model/thinking availability and choices; it owns no duplicate state. */
internal class ChatSessionOptionsFeature(
  val thinkingLevel: StateFlow<String>,
  val thinkingSelection: StateFlow<ChatThinkingLevelSelection>,
  val selectedModelRef: StateFlow<String?>,
  val modelCatalog: StateFlow<ModelCatalogState>,
  val commands: StateFlow<List<ChatCommandEntry>>,
  val favorites: StateFlow<List<String>>,
  val recents: StateFlow<List<String>>,
  val refresh: () -> Unit,
  val selectThinkingLevel: (String) -> Unit,
  val selectModelRoute: (String?) -> Unit,
  val toggleFavorite: (String) -> Unit,
)
