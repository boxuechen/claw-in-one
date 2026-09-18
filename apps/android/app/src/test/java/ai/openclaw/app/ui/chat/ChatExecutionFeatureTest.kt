package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatExecutionFeature
import ai.openclaw.app.chat.ChatLocalDraft
import ai.openclaw.app.chat.ChatLocalDraftFeature
import ai.openclaw.app.chat.ChatStopFeature
import ai.openclaw.app.chat.ChatStopState
import ai.openclaw.app.permissions.SessionPermissionDraft
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionTarget
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
class ChatExecutionFeatureTest {
  @get:Rule val composeRule = createComposeRule()

  @Test fun absentFeatureHasInertDefaultsWithoutConstructingARuntime() {
    composeRule.setContent { ExecutionProbe(null) }
    composeRule.onNodeWithText("none / 0").assertIsDisplayed()
    composeRule.onNodeWithText("Choose Full").performClick()
    composeRule.onNodeWithText("Stop").performClick()
  }

  @Test fun replacementMovesLocalDraftStopStateAndActionsTogether() {
    val first = Execution("first")
    val second = Execution("second")
    var source by mutableStateOf<ChatExecutionFeature?>(first.feature)
    composeRule.setContent { ExecutionProbe(source) }
    composeRule.onNodeWithText("first / 0").assertIsDisplayed()
    composeRule.onNodeWithText("Stop").performClick()
    assertEquals(listOf("stop"), first.actions)

    composeRule.runOnIdle { source = second.feature }
    composeRule.onNodeWithText("second / 0").assertIsDisplayed()
    composeRule.runOnIdle { first.localDrafts.value = mapOf(target("late-old") to draft("late-old")) }
    composeRule.onNodeWithText("late-old / 0").assertDoesNotExist()
    composeRule.onNodeWithText("Choose Full").performClick()
    composeRule.onNodeWithText("Stop").performClick()
    assertEquals(listOf("stop"), first.actions)
    assertEquals(listOf("choose:second:Full", "stop"), second.actions)
  }

  @Composable
  private fun ExecutionProbe(feature: ChatExecutionFeature?) {
    val state = feature.collectExecutionPresentation()
    val target = state.localDrafts.keys.firstOrNull()
    Column {
      Text("${target?.gatewayId ?: "none"} / ${state.stops.size}")
      Button(
        onClick = {
          target?.let { feature?.localDrafts?.choose(it, SessionPermissionMode.Full) }
        },
      ) {
        Text("Choose Full")
      }
      Button(onClick = { feature?.stops?.abortCurrent() }) { Text("Stop") }
    }
  }

  private class Execution(
    id: String,
  ) {
    val localDrafts = MutableStateFlow(mapOf(target(id) to draft(id)))
    val actions = mutableListOf<String>()
    val feature =
      ChatExecutionFeature(
        localDrafts =
          ChatLocalDraftFeature(
            states = localDrafts,
            chooseAction = { target, mode ->
              actions += "choose:${target.gatewayId}:$mode"
              true
            },
            confirmAction = { _, _ -> true },
          ),
        stops =
          ChatStopFeature(
            states = MutableStateFlow<Map<ChatComposerOwner, ChatStopState>>(emptyMap()),
            abortCurrentAction = { actions += "stop" },
            reconcileAction = { false },
          ),
      )
  }

  private companion object {
    fun target(id: String) = SessionPermissionTarget(id, "agent:main:$id", "main")

    fun draft(id: String) = ChatLocalDraft(SessionPermissionDraft(target(id), "create-$id"))
  }
}
