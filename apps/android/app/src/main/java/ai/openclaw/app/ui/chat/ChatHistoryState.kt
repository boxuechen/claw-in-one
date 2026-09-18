package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatHistoryFeature
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatTranscriptAnchorState
import ai.openclaw.app.chat.GatewayDefaultAgentOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key

/** Render projection only; reading and admission always return to the captured feature. */
internal data class ChatHistoryState(
  val sessionKey: String = "main",
  val ownerAgentId: String? = null,
  val selectionGeneration: Long = 0,
  val defaultOwner: GatewayDefaultAgentOwner? = null,
  val messages: List<ChatMessage> = emptyList(),
  val anchor: ChatTranscriptAnchorState? = null,
  val loading: Boolean = false,
  val error: String? = null,
  val healthy: Boolean = false,
)

@Composable
internal fun ChatHistoryFeature?.collectHistory(): ChatHistoryState =
  key(this) {
    if (this == null) {
      ChatHistoryState()
    } else {
      ChatHistoryState(
        sessionKey = selection.key.collectAsState().value,
        ownerAgentId = selection.ownerAgentId.collectAsState().value,
        selectionGeneration = selection.generation.collectAsState().value,
        defaultOwner = selection.defaultOwner.collectAsState().value,
        messages = messages.collectAsState().value,
        anchor = anchor.collectAsState().value,
        loading = loading.collectAsState().value,
        error = error.collectAsState().value,
        healthy = healthy.collectAsState().value,
      )
    }
  }
