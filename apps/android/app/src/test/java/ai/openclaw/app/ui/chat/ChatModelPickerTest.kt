package ai.openclaw.app.ui.chat

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.chat.providerQualifiedRef
import ai.openclaw.app.chat.thinkingSupportedForAiSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelPickerTest {
  @Test
  fun providerQualifiedRefAddsProviderOnlyWhenNeeded() {
    assertEquals("anthropic/claude-opus-4", model(id = "claude-opus-4", provider = "anthropic").providerQualifiedRef())
    assertEquals("anthropic/claude-opus-4", model(id = "anthropic/claude-opus-4", provider = "anthropic").providerQualifiedRef())
  }

  @Test
  fun sectionsPutCurrentFirstThenPreservePinRecentAndCatalogOrder() {
    val catalog =
      listOf(
        model(id = "a", provider = "one"),
        model(id = "b", provider = "two"),
        model(id = "c", provider = "one"),
        model(id = "d", provider = "three"),
      )

    val sections =
      chatModelPickerSections(
        catalog = catalog,
        selectedModelRef = "two/b",
        favorites = listOf("one/c", "missing/model", "one/a"),
        recents = listOf("one/a", "three/d", "missing/recent"),
      )

    assertEquals("two/b", sections.current?.providerQualifiedRef())
    assertEquals(listOf("one/c", "one/a"), sections.pinned.map { it.providerQualifiedRef() })
    assertEquals(listOf("three/d"), sections.recent.map { it.providerQualifiedRef() })
    assertTrue(sections.remaining.isEmpty())
  }

  @Test
  fun thinkingSupportFailsOpenUnlessMatchedModelDisablesReasoning() {
    val catalog =
      listOf(
        model(id = "reasoning", provider = "openai", supportsReasoning = true),
        model(id = "plain", provider = "openai", supportsReasoning = false),
      )

    assertTrue(thinkingSupportedForAiSelection(selectedModelRef = null, catalog = catalog))
    assertTrue(thinkingSupportedForAiSelection(selectedModelRef = "openai/unknown", catalog = catalog))
    assertTrue(thinkingSupportedForAiSelection(selectedModelRef = "openai/reasoning", catalog = catalog))
    assertFalse(thinkingSupportedForAiSelection(selectedModelRef = "openai/plain", catalog = catalog))
  }

  private fun model(
    id: String,
    provider: String,
    supportsReasoning: Boolean = false,
  ): AiModel =
    AiModel(
      id = id,
      name = id.substringAfterLast('/'),
      provider = provider,
      alias = null,
      tags = emptyList(),
      available = true,
      unavailableReason = null,
      unavailableUntilEpochMs = null,
      contextTokens = null,
      supportsReasoning = supportsReasoning,
      supportsTools = null,
      apiKeySupported = null,
    )
}
