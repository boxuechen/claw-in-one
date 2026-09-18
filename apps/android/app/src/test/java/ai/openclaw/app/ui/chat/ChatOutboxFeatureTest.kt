package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatOutboxFeature
import ai.openclaw.app.chat.ChatOutboxItem
import ai.openclaw.app.chat.ChatOutboxStatus
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
class ChatOutboxFeatureTest {
  @get:Rule val composeRule = createComposeRule()

  @Test fun absentFeatureHasInertDefaultsWithoutConstructingARuntime() {
    composeRule.setContent { OutboxProbe(null) }
    composeRule.onNodeWithText("empty / false").assertIsDisplayed()
    composeRule.onNodeWithText("Retry").performClick()
    composeRule.onNodeWithText("Delete").performClick()
  }

  @Test fun replacementMovesRowsRestoreStateAndActionsTogether() {
    val first = Outbox("first", restored = false)
    val second = Outbox("second", restored = true)
    var source by mutableStateOf<ChatOutboxFeature?>(first.feature)
    composeRule.setContent { OutboxProbe(source) }
    composeRule.onNodeWithText("first / false").assertIsDisplayed()
    composeRule.onNodeWithText("Retry").performClick()
    assertEquals(listOf("retry:first"), first.actions)

    composeRule.runOnIdle { source = second.feature }
    composeRule.onNodeWithText("second / true").assertIsDisplayed()
    composeRule.runOnIdle {
      first.items.value = listOf(item("late-old"))
      first.restored.value = true
    }
    composeRule.onNodeWithText("late-old / true").assertDoesNotExist()
    composeRule.onNodeWithText("Retry").performClick()
    composeRule.onNodeWithText("Delete").performClick()
    assertEquals(listOf("retry:first"), first.actions)
    assertEquals(listOf("retry:second", "delete:second"), second.actions)
  }

  @Composable
  private fun OutboxProbe(feature: ChatOutboxFeature?) {
    val state = feature.collectOutboxPresentation()
    val id = state.items.firstOrNull()?.id ?: "empty"
    Column {
      Text("$id / ${state.restored}")
      Button(onClick = { feature?.retry?.invoke(id) }) { Text("Retry") }
      Button(onClick = { feature?.delete?.invoke(id) }) { Text("Delete") }
    }
  }

  private class Outbox(
    id: String,
    restored: Boolean,
  ) {
    val items = MutableStateFlow(listOf(item(id)))
    val restored = MutableStateFlow(restored)
    val actions = mutableListOf<String>()
    val feature =
      ChatOutboxFeature(
        items = items,
        presentationRestored = this.restored,
        retry = { actions += "retry:$it" },
        delete = { actions += "delete:$it" },
      )
  }

  private companion object {
    fun item(id: String) =
      ChatOutboxItem(
        id = id,
        sessionKey = "agent:main:main",
        text = id,
        thinkingLevel = "off",
        createdAtMs = 1,
        status = ChatOutboxStatus.Failed,
        retryCount = 0,
        lastError = "test",
        ownerAgentId = "main",
      )
  }
}
