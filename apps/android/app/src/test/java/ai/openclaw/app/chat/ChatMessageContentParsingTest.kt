package ai.openclaw.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageContentParsingTest {
  @Test
  fun parsesInlineAndManagedImagesIntoTheRetainedFields() {
    val inline =
      Json.parseToJsonElement(
        """{"type":"image","mimeType":"image/png","fileName":"chart.png","content":"abc123"}""",
      )
    val managed =
      Json.parseToJsonElement(
        """{"type":"image","artifactId":"artifact_managed_image_11111111-1111-4111-8111-111111111111","mimeType":"image/png","alt":"Chart"}""",
      )

    assertEquals(
      ChatMessageContent(type = "image", mimeType = "image/png", fileName = "chart.png", base64 = "abc123"),
      parseChatMessageContent(inline),
    )
    assertEquals(
      ChatMessageContent(
        type = "image",
        mimeType = "image/png",
        fileName = "Chart",
        artifactId = "artifact_managed_image_11111111-1111-4111-8111-111111111111",
      ),
      parseChatMessageContent(managed),
    )
  }

  @Test
  fun derivesArtifactIdentityFromTheAuthenticatedManagedImagePath() {
    val image =
      Json.parseToJsonElement(
        """{"type":"image","mimeType":"image/png","url":"/api/chat/media/outgoing/main/11111111-1111-4111-8111-111111111111/full"}""",
      )

    assertEquals(
      "artifact_managed_image_11111111-1111-4111-8111-111111111111",
      parseChatMessageContent(image)?.artifactId,
    )
  }

  @Test
  fun dropsOversizedInlineImageContentBeforeRendering() {
    val oversized = "A".repeat(CHAT_IMAGE_MAX_BASE64_CHARS + 1)
    val image =
      Json.parseToJsonElement(
        """{"type":"image","mimeType":"image/png","fileName":"large.png","content":"$oversized"}""",
      )

    assertEquals(
      ChatMessageContent(type = "image", mimeType = "image/png", fileName = "large.png"),
      parseChatMessageContent(image),
    )
  }

  @Test
  fun keepsOnlyInertDocumentMetadata() {
    val flat =
      Json.parseToJsonElement(
        """{"type":"file","name":"report.txt","mimeType":"text/plain","url":"https://example.test/report.txt"}""",
      )
    val nested =
      Json.parseToJsonElement(
        """{"type":"attachment","attachment":{"kind":"document","label":"proposal.pdf","mimeType":"application/pdf","artifactId":"unused","url":"/files/proposal.pdf"}}""",
      )

    assertEquals(
      ChatMessageContent(type = "file", mimeType = "text/plain", fileName = "report.txt"),
      parseChatMessageContent(flat),
    )
    assertEquals(
      ChatMessageContent(type = "file", mimeType = "application/pdf", fileName = "proposal.pdf"),
      parseChatMessageContent(nested),
    )
  }

  @Test
  fun unknownDirectAndNestedContentBecomeInertMarkers() {
    val direct =
      Json.parseToJsonElement(
        """{"type":"future_surface","title":"Status","url":"https://attacker.example/active"}""",
      )
    val nested =
      Json.parseToJsonElement(
        """{"type":"attachment","attachment":{"kind":"future_binary","label":"Result.bin","mimeType":"application/octet-stream","artifactId":"unused","url":"https://attacker.example/file"}}""",
      )

    assertEquals(
      ChatMessageContent(type = "unsupported", fileName = "Status"),
      parseChatMessageContent(direct),
    )
    assertEquals(
      ChatMessageContent(type = "unsupported", mimeType = "application/octet-stream", fileName = "Result.bin"),
      parseChatMessageContent(nested),
    )
  }

  @Test
  fun omitsReasoningAndToolProtocolBlocksOwnedByDedicatedActivityUi() {
    val blocks =
      listOf(
        """{"type":"thinking","thinking":"private"}""",
        """{"type":"reasoning","text":"private"}""",
        """{"type":"redacted_thinking","data":"private"}""",
        """{"type":"toolCall","id":"call-1","name":"exec","arguments":{}}""",
        """{"type":"tool_call","id":"call-2","name":"exec","arguments":{}}""",
        """{"type":"toolUse","id":"call-3","name":"exec","input":{}}""",
        """{"type":"tool_use","id":"call-4","name":"exec","input":{}}""",
        """{"type":"toolResult","toolCallId":"call-5","content":"done"}""",
        """{"type":"tool_result","tool_use_id":"call-6","content":"done"}""",
      )

    assertEquals(
      emptyList<ChatMessageContent>(),
      blocks.mapNotNull { parseChatMessageContent(Json.parseToJsonElement(it)) },
    )
  }

  @Test
  fun readsTextFromContentOrTextFallback() {
    val content = Json.parseToJsonElement("""{"content":"From content"}""").jsonObject
    val text = Json.parseToJsonElement("""{"text":"From text"}""").jsonObject

    assertEquals(listOf(ChatMessageContent(text = "From content")), parseChatMessageContents(content))
    assertEquals(listOf(ChatMessageContent(text = "From text")), parseChatMessageContents(text))
  }
}
