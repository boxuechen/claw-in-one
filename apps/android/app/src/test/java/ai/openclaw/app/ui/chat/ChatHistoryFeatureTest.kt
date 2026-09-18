package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatFullMessageRead
import ai.openclaw.app.chat.ChatFullMessageSource
import ai.openclaw.app.chat.ChatFullMessageState
import ai.openclaw.app.chat.ChatHistoryFeature
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatMessageContent
import ai.openclaw.app.chat.ChatSelectionFeature
import ai.openclaw.app.chat.ChatTranscriptAnchorState
import ai.openclaw.app.chat.GatewayDefaultAgentOwner
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatHistoryFeatureTest {
  @get:Rule val composeRule = createComposeRule()

  @Before fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test fun absentAndLiveProjectionNeedNoRuntimeAndNeverStartWork() {
    val h = History()
    var feature by mutableStateOf<ChatHistoryFeature?>(null)
    val observations = mutableListOf<ChatHistoryState>()
    composeRule.setContent {
      val state = feature.collectHistory()
      SideEffect { observations += state }
      Text("Rows: ${state.messages.size}, healthy: ${state.healthy}")
    }
    composeRule.onNodeWithText("Rows: 0, healthy: false").assertIsDisplayed()
    composeRule.runOnIdle { feature = h.feature }
    composeRule.onNodeWithText("Rows: 1, healthy: true").assertIsDisplayed()
    composeRule.runOnIdle {
      h.selection.value = "agent:main:other"
      h.generation.value = 2L
      h.loading.value = true
      h.error.value = "Read unavailable"
    }
    composeRule.runOnIdle {
      assertEquals("agent:main:other", observations.last().sessionKey)
      assertEquals(2L, observations.last().selectionGeneration)
      assertEquals("Read unavailable", observations.last().error)
      assertTrue(observations.last().loading)
      assertEquals(0, h.operations)
    }
  }

  @Test fun replacementNeverProjectsOldMessagesUnderNewFeatureIdentity() {
    val first = History()
    val second = History().apply { messages.value = emptyList() }
    var feature by mutableStateOf<ChatHistoryFeature?>(first.feature)
    val observations = mutableListOf<Pair<ChatHistoryFeature?, Int>>()
    composeRule.setContent {
      val current = feature
      val state = current.collectHistory()
      SideEffect { observations += current to state.messages.size }
      Text("Rows: ${state.messages.size}")
    }
    composeRule.onNodeWithText("Rows: 1").assertIsDisplayed()
    composeRule.runOnIdle { feature = second.feature }
    composeRule.onNodeWithText("Rows: 0").assertIsDisplayed()
    composeRule.runOnIdle { first.messages.value = listOf(preview.copy(id = "old-change"), preview) }
    composeRule.onNodeWithText("Rows: 0").assertIsDisplayed()
    composeRule.runOnIdle {
      assertTrue(observations.any { it.first === second.feature })
      assertTrue(observations.filter { it.first === second.feature }.all { it.second == 0 })
    }
  }

  @Test fun replacingReadSourceCancelsOldReadEvenWhenAllSessionIdentitiesMatch() {
    val old = Read()
    val replacement = Read().apply { reply.complete(loaded("New full text")) }
    var source by mutableStateOf<ChatFullMessageSource?>(readSource(old))
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatMessageDisclosure(listOf(preview), owner, 1L, 1L, source) { content, action ->
          Column {
            Text(content(preview).mapNotNull { it.text }.joinToString())
            action(preview)
          }
        }
      }
    }
    composeRule.onNodeWithText("View all").performClick()
    composeRule.onNodeWithText("Loading full message…").assertIsDisplayed()
    composeRule.runOnIdle { source = readSource(replacement) }
    composeRule.onNodeWithText("View all").assertIsDisplayed()
    assertTrue(old.cancelled)
    composeRule.runOnIdle { old.result.value = loaded("Late old text") }
    composeRule.onNodeWithText("Late old text").assertDoesNotExist()
    composeRule.onNodeWithText("View all").performClick()
    composeRule.onNodeWithText("New full text").assertIsDisplayed()
    composeRule.runOnIdle { source = null }
    composeRule.onNodeWithText("New full text").assertDoesNotExist()
    composeRule.onNodeWithText("Preview").assertIsDisplayed()
    assertEquals(1, old.executions)
    assertEquals(1, replacement.executions)
  }

  private fun readSource(read: Read) =
    ChatFullMessageSource { capturedOwner, generation, catalog, message ->
      assertEquals(owner, capturedOwner)
      assertEquals(1L, generation)
      assertEquals(1L, catalog)
      assertEquals(preview, message)
      read
    }

  private class Read : ChatFullMessageRead {
    val result = MutableStateFlow<ChatFullMessageState>(ChatFullMessageState.Loading)
    override val state = result.asStateFlow()
    val reply = CompletableDeferred<ChatFullMessageState>()
    var cancelled = false
    var executions = 0

    override suspend fun execute() {
      executions++
      try {
        result.value = reply.await()
      } catch (cancel: CancellationException) {
        cancelled = true
        throw cancel
      }
    }
  }

  private class History {
    val selection = MutableStateFlow(owner.sessionKey)
    val generation = MutableStateFlow(1L)
    val messages = MutableStateFlow(listOf(preview))
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    var operations = 0
    val feature =
      ChatHistoryFeature(
        selection = ChatSelectionFeature(selection, MutableStateFlow("main"), generation, MutableStateFlow<GatewayDefaultAgentOwner?>(null)),
        messages = messages,
        anchor = MutableStateFlow<ChatTranscriptAnchorState?>(null),
        loading = loading,
        error = error,
        healthy = MutableStateFlow(true),
        load = { _, _ -> operations++ },
        refresh = { operations++ },
        fullMessages =
          ChatFullMessageSource { _, _, _, _ ->
            operations++
            null
          },
        loadImage = {
          operations++
          null
        },
      )
  }

  private companion object {
    val owner = ChatComposerOwner("gateway", "main", "agent:main:topic")
    val preview = ChatMessage("display", "assistant", listOf(ChatMessageContent(text = "Preview")), null, entryId = "entry", truncated = true)

    fun loaded(text: String) = ChatFullMessageState.Loaded(listOf(ChatMessageContent(text = text)))
  }
}
