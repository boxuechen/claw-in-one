package ai.openclaw.app.ui.chat

import ai.openclaw.app.ai.ModelCatalogState
import ai.openclaw.app.chat.ChatCommandEntry
import ai.openclaw.app.chat.ChatSessionOptionsFeature
import ai.openclaw.app.chat.ChatThinkingLevelSelection
import ai.openclaw.app.chat.defaultChatThinkingLevelSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key

/** UI projection only; replacing the feature retires every old subscription together. */
internal data class ChatSessionOptionsPresentation(
  val thinkingLevel: String = "off",
  val thinkingSelection: ChatThinkingLevelSelection = defaultChatThinkingLevelSelection,
  val selectedModelRef: String? = null,
  val modelCatalog: ModelCatalogState = ModelCatalogState(),
  val commands: List<ChatCommandEntry> = emptyList(),
  val favorites: List<String> = emptyList(),
  val recents: List<String> = emptyList(),
)

@Composable
internal fun ChatSessionOptionsFeature?.collectPresentation(): ChatSessionOptionsPresentation =
  key(this) {
    val source = this
    ChatSessionOptionsPresentation(
      thinkingLevel = source?.thinkingLevel?.collectAsState()?.value ?: "off",
      thinkingSelection = source?.thinkingSelection?.collectAsState()?.value ?: defaultChatThinkingLevelSelection,
      selectedModelRef = source?.selectedModelRef?.collectAsState()?.value,
      modelCatalog =
        source
          ?.modelCatalog
          ?.collectAsState()
          ?.value
          ?: ModelCatalogState(),
      commands =
        source
          ?.commands
          ?.collectAsState()
          ?.value
          .orEmpty(),
      favorites =
        source
          ?.favorites
          ?.collectAsState()
          ?.value
          .orEmpty(),
      recents =
        source
          ?.recents
          ?.collectAsState()
          ?.value
          .orEmpty(),
    )
  }
