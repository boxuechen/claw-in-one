package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayLoadedImage
import kotlinx.coroutines.flow.StateFlow

/** Transcript reading, not session mutation, message delivery, or execution policy. */
internal class ChatHistoryFeature(
  val selection: ChatSelectionFeature,
  val messages: StateFlow<List<ChatMessage>>,
  val anchor: StateFlow<ChatTranscriptAnchorState?>,
  val loading: StateFlow<Boolean>,
  val error: StateFlow<String?>,
  val healthy: StateFlow<Boolean>,
  val load: (sessionKey: String, ownerAgentId: String?) -> Unit,
  val refresh: () -> Unit,
  val fullMessages: ChatFullMessageSource,
  val loadImage: suspend (artifactId: String) -> GatewayLoadedImage?,
)

/** Identity and admission for message disclosure; replacement retires its UI read lifetime. */
internal class ChatFullMessageSource(
  val prepare: (ChatComposerOwner, selectionGeneration: Long, catalogRevision: Long, ChatMessage) -> ChatFullMessageRead?,
)

/** One admitted read with its own outcome. No Controller or mutable presentation callback. */
internal interface ChatFullMessageRead {
  val state: StateFlow<ChatFullMessageState>

  suspend fun execute()
}
