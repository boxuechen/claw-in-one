package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatOutboxFeature
import ai.openclaw.app.chat.ChatOutboxItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key

/** UI projection only; replacement cannot pair old durable rows with new recovery actions. */
internal data class ChatOutboxPresentation(
  val items: List<ChatOutboxItem> = emptyList(),
  val restored: Boolean = false,
)

@Composable
internal fun ChatOutboxFeature?.collectOutboxPresentation(): ChatOutboxPresentation =
  key(this) {
    val source = this
    ChatOutboxPresentation(
      items =
        source
          ?.items
          ?.collectAsState()
          ?.value
          .orEmpty(),
      restored = source?.presentationRestored?.collectAsState()?.value ?: false,
    )
  }
