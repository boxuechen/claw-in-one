package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatActiveRunPresentation
import ai.openclaw.app.chat.ChatAssistantAnswerDraft
import ai.openclaw.app.chat.ChatCurrentWorkFeature
import ai.openclaw.app.chat.ChatQuestionDraft
import ai.openclaw.app.chat.ChatQuestionPrompt
import ai.openclaw.app.gateway.QuestionRecord
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatCurrentWorkFeatureTest {
  @get:Rule val composeRule = createComposeRule()

  @Test fun absentFeatureHasInertDefaultsWithoutConstructingARuntime() {
    composeRule.setContent { CurrentWorkProbe(null) }
    composeRule.onNodeWithText("0 / idle").assertIsDisplayed()
    composeRule.onNodeWithText("Resolve").performClick()
  }

  @Test fun replacementMovesEverySubscriptionAndQuestionActionTogether() {
    val first = CurrentWork(1, "First")
    val second = CurrentWork(2, "Second")
    var source by mutableStateOf<ChatCurrentWorkFeature?>(first.feature)
    composeRule.setContent { CurrentWorkProbe(source) }
    composeRule.onNodeWithText("1 / First").assertIsDisplayed()
    composeRule.onNodeWithText("Resolve").performClick()
    assertEquals(listOf("resolve"), first.actions)

    composeRule.runOnIdle { source = second.feature }
    composeRule.onNodeWithText("2 / Second").assertIsDisplayed()
    composeRule.runOnIdle {
      first.pendingRunCount.value = 9
      first.answerDraft.value = ChatAssistantAnswerDraft("first", "Late old work")
    }
    composeRule.onNodeWithText("9 / Late old work").assertDoesNotExist()
    composeRule.onNodeWithText("Resolve").performClick()
    assertEquals(listOf("resolve"), first.actions)
    assertEquals(listOf("resolve"), second.actions)
  }

  @Composable
  private fun CurrentWorkProbe(feature: ChatCurrentWorkFeature?) {
    val state = feature.collectCurrentWorkPresentation()
    Column {
      Text("${state.pendingRunCount} / ${state.answerDraft?.text ?: "idle"}")
      Button(onClick = { feature?.resolveQuestion?.invoke(questionPrompt, emptyMap()) }) {
        Text("Resolve")
      }
    }
  }

  private class CurrentWork(
    count: Int,
    text: String,
  ) {
    val pendingRunCount = MutableStateFlow(count)
    val answerDraft = MutableStateFlow<ChatAssistantAnswerDraft?>(ChatAssistantAnswerDraft(text.lowercase(), text))
    val actions = mutableListOf<String>()
    val feature =
      ChatCurrentWorkFeature(
        pendingRunCount = pendingRunCount,
        activeRun = MutableStateFlow(ChatActiveRunPresentation(count = count)),
        answerDraft = answerDraft,
        runActivity = MutableStateFlow(null),
        pendingToolCalls = MutableStateFlow(emptyList()),
        questions = MutableStateFlow(listOf(questionPrompt)),
        progressCard = MutableStateFlow(null),
        taskNotices = MutableStateFlow(emptyMap()),
        updateQuestionDraft = { _, _ -> actions += "update" },
        resolveQuestion = { _, _ -> actions += "resolve" },
        skipQuestion = { actions += "skip" },
      )
  }

  private companion object {
    val questionPrompt =
      ChatQuestionPrompt(
        record =
          QuestionRecord(
            id = "question",
            questions = emptyList(),
            sessionKey = "agent:main:main",
            agentId = "main",
            createdAtMs = 1,
            expiresAtMs = Long.MAX_VALUE,
            status = "pending",
          ),
        draft = ChatQuestionDraft(),
      )
  }
}
