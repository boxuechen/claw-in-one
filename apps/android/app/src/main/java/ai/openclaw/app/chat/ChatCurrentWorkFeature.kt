package ai.openclaw.app.chat

import kotlinx.coroutines.flow.StateFlow

internal data class ChatTaskNotice(
  val sessionKey: String,
  val message: String,
) {
  init {
    require(sessionKey.isNotBlank())
    require(message.isNotBlank())
  }
}

/**
 * Runtime-bound presentation and interactions for work currently occurring in Chat.
 *
 * The underlying run, stream, question, and tool-activity owners retain their state. This
 * contract only keeps one Runtime's read sources and question actions from being
 * paired with a replacement Runtime at composition.
 */
internal class ChatCurrentWorkFeature(
  val pendingRunCount: StateFlow<Int>,
  val activeRun: StateFlow<ChatActiveRunPresentation>,
  val answerDraft: StateFlow<ChatAssistantAnswerDraft?>,
  val runActivity: StateFlow<ChatRunActivity?>,
  val pendingToolCalls: StateFlow<List<ChatPendingToolCall>>,
  val questions: StateFlow<List<ChatQuestionPrompt>>,
  val progressCard: StateFlow<ChatProgressCard?>,
  val taskNotices: StateFlow<Map<String, ChatTaskNotice>>,
  val updateQuestionDraft: (prompt: ChatQuestionPrompt, update: (ChatQuestionDraft) -> ChatQuestionDraft) -> Unit,
  val resolveQuestion: (prompt: ChatQuestionPrompt, answers: Map<String, List<String>>) -> Unit,
  val skipQuestion: (prompt: ChatQuestionPrompt) -> Unit,
)
