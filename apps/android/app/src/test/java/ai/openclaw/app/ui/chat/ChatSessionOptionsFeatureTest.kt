package ai.openclaw.app.ui.chat

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.ai.ModelCatalogSnapshot
import ai.openclaw.app.ai.ModelCatalogState
import ai.openclaw.app.ai.ModelCatalogStatus
import ai.openclaw.app.chat.ChatSessionOptionsFeature
import ai.openclaw.app.chat.defaultChatThinkingLevelSelection
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
class ChatSessionOptionsFeatureTest {
  @get:Rule val composeRule = createComposeRule()

  @Test fun absentFeatureHasInertDefaultsWithoutConstructingARuntime() {
    composeRule.setContent { OptionsProbe(null) }
    composeRule.onNodeWithText("off / none").assertIsDisplayed()
    composeRule.onNodeWithText("Select model").performClick()
  }

  @Test fun replacementMovesStateAndActionsTogether() {
    val first = Options("First")
    val second = Options("Second")
    var source by mutableStateOf<ChatSessionOptionsFeature?>(first.feature)
    composeRule.setContent { OptionsProbe(source) }
    composeRule.onNodeWithText("off / First").assertIsDisplayed()
    composeRule.onNodeWithText("Select model").performClick()
    assertEquals(listOf("model:chosen"), first.actions)

    composeRule.runOnIdle { source = second.feature }
    composeRule.onNodeWithText("off / Second").assertIsDisplayed()
    composeRule.runOnIdle { first.modelCatalog.value = catalog("Late old model") }
    composeRule.onNodeWithText("Late old model").assertDoesNotExist()
    composeRule.onNodeWithText("Select model").performClick()
    assertEquals(listOf("model:chosen"), first.actions)
    assertEquals(listOf("model:chosen"), second.actions)
  }

  @Composable
  private fun OptionsProbe(feature: ChatSessionOptionsFeature?) {
    val state = feature.collectPresentation()
    Column {
      val models = (state.modelCatalog.status as? ModelCatalogStatus.Ready)?.snapshot?.models.orEmpty()
      Text("${state.thinkingLevel} / ${models.firstOrNull()?.name ?: "none"}")
      Button(onClick = { feature?.selectModelRoute?.invoke("chosen") }) { Text("Select model") }
    }
  }

  private class Options(
    modelName: String,
  ) {
    val modelCatalog = MutableStateFlow(catalog(modelName))
    val actions = mutableListOf<String>()
    val feature =
      ChatSessionOptionsFeature(
        thinkingLevel = MutableStateFlow("off"),
        thinkingSelection = MutableStateFlow(defaultChatThinkingLevelSelection),
        selectedModelRef = MutableStateFlow(null),
        modelCatalog = modelCatalog,
        commands = MutableStateFlow(emptyList()),
        favorites = MutableStateFlow(emptyList()),
        recents = MutableStateFlow(emptyList()),
        refresh = { actions += "refresh" },
        selectThinkingLevel = { actions += "thinking:$it" },
        selectModelRoute = { model -> actions += "model:$model" },
        toggleFavorite = { actions += "favorite:$it" },
      )
  }

  private companion object {
    fun model(name: String) =
      AiModel(
        id = name.lowercase().replace(' ', '-'),
        name = name,
        provider = "test",
        alias = null,
        tags = emptyList(),
        available = true,
        unavailableReason = null,
        unavailableUntilEpochMs = null,
        supportsReasoning = true,
        contextTokens = null,
        supportsTools = null,
        apiKeySupported = null,
      )

    fun catalog(name: String) =
      ModelCatalogState(
        ModelCatalogStatus.Ready(
          ModelCatalogSnapshot(
            models = listOf(model(name)),
            refreshFailed = false,
            providerOutcomes = emptyList(),
          ),
        ),
      )
  }
}
