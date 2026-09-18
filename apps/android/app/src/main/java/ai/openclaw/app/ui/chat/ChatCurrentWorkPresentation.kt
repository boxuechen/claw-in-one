package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatActiveRunPresentation
import ai.openclaw.app.chat.ChatAssistantAnswerDraft
import ai.openclaw.app.chat.ChatCurrentWorkFeature
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.chat.ChatProgressCard
import ai.openclaw.app.chat.ChatQuestionPrompt
import ai.openclaw.app.chat.ChatRunActivity
import ai.openclaw.app.chat.ChatTaskNotice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key

/** UI projection only; replacing the feature retires all current-work subscriptions together. */
internal data class ChatCurrentWorkPresentation(
  val pendingRunCount: Int = 0,
  val activeRun: ChatActiveRunPresentation = ChatActiveRunPresentation(),
  val answerDraft: ChatAssistantAnswerDraft? = null,
  val runActivity: ChatRunActivity? = null,
  val pendingToolCalls: List<ChatPendingToolCall> = emptyList(),
  val questions: List<ChatQuestionPrompt> = emptyList(),
  val progressCard: ChatProgressCard? = null,
  val taskNotices: Map<String, ChatTaskNotice> = emptyMap(),
)

@Composable
internal fun ChatCurrentWorkFeature?.collectCurrentWorkPresentation(): ChatCurrentWorkPresentation =
  key(this) {
    val source = this
    ChatCurrentWorkPresentation(
      pendingRunCount = source?.pendingRunCount?.collectAsState()?.value ?: 0,
      activeRun = source?.activeRun?.collectAsState()?.value ?: ChatActiveRunPresentation(),
      answerDraft = source?.answerDraft?.collectAsState()?.value,
      runActivity = source?.runActivity?.collectAsState()?.value,
      pendingToolCalls =
        source
          ?.pendingToolCalls
          ?.collectAsState()
          ?.value
          .orEmpty(),
      questions =
        source
          ?.questions
          ?.collectAsState()
          ?.value
          .orEmpty(),
      progressCard = source?.progressCard?.collectAsState()?.value,
      taskNotices =
        source
          ?.taskNotices
          ?.collectAsState()
          ?.value
          .orEmpty(),
    )
  }
