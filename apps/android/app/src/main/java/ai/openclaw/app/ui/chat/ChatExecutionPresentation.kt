package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatExecutionFeature
import ai.openclaw.app.chat.ChatLocalDraft
import ai.openclaw.app.chat.ChatStopState
import ai.openclaw.app.permissions.SessionPermissionTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key

/** UI projection only; Runtime replacement retires local-draft and Stop state together. */
internal data class ChatExecutionPresentation(
  val localDrafts: Map<SessionPermissionTarget, ChatLocalDraft> = emptyMap(),
  val stops: Map<ChatComposerOwner, ChatStopState> = emptyMap(),
)

@Composable
internal fun ChatExecutionFeature?.collectExecutionPresentation(): ChatExecutionPresentation =
  key(this) {
    val source = this
    ChatExecutionPresentation(
      localDrafts =
        source
          ?.localDrafts
          ?.states
          ?.collectAsState()
          ?.value
          .orEmpty(),
      stops =
        source
          ?.stops
          ?.states
          ?.collectAsState()
          ?.value
          .orEmpty(),
    )
  }
