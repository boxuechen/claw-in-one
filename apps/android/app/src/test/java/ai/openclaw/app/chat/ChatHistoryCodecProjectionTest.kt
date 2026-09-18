package ai.openclaw.app.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryCodecProjectionTest {
  private val codec = ChatHistoryCodec(Json, ChatSessionCatalogCodec(Json))

  @Test
  fun gateway94CommentaryAndToolMirrorsNeverBecomeTranscriptMessages() {
    val history =
      codec.parseHistory(
        historyJson =
          """
          {
            "sessionId": "session-1",
            "messages": [
              {
                "role": "user",
                "content": [{"type": "text", "text": "Build it"}],
                "timestamp": 10,
                "idempotencyKey": "run-7:user"
              },
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "Inspecting the project"}],
                "timestamp": 20,
                "idempotencyKey": "run-7:user",
                "openclawStreamFallback": {"source": "segment", "itemId": "preamble-1"},
                "__openclaw": {"id": "fallback-1", "runId": "run-7", "seq": 12}
              },
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "Finished answer"}],
                "timestamp": 30,
                "__openclaw": {"id": "answer-1"}
              },
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "Tool mirror"}],
                "timestamp": 40,
                "openclawMessageToolMirror": {"toolCallId": "tool-1"}
              }
            ]
          }
          """.trimIndent(),
        sessionKey = "main",
        previousMessages = emptyList(),
      )

    assertEquals(listOf("user", "assistant"), history.messages.map { it.role })
    assertEquals(listOf("Build it", "Finished answer"), history.messages.map { it.content.single().text })
    assertEquals(
      ChatCommentarySegment(
        runId = "run-7",
        itemId = "preamble-1",
        text = "Inspecting the project",
        timestampMs = 20,
        sequence = 12,
      ),
      history.commentary.single(),
    )
  }

  @Test
  fun malformedDisplayProjectionsAreDroppedInsteadOfFallingBackToMessages() {
    val history =
      codec.parseHistory(
        historyJson =
          """
          {
            "messages": [
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "Missing item identity"}],
                "openclawStreamFallback": {"source": "segment"}
              },
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "Unknown fallback source"}],
                "openclawStreamFallback": {"source": "other", "itemId": "other-1"}
              },
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "Missing mirror details"}],
                "openclawMessageToolMirror": {}
              }
            ]
          }
          """.trimIndent(),
        sessionKey = "main",
        previousMessages = emptyList(),
      )

    assertEquals(emptyList<ChatMessage>(), history.messages)
    assertEquals(emptyList<ChatCommentarySegment>(), history.commentary)
    assertNull(history.inFlightRun)
  }

  @Test
  fun gateway94ReasoningAndToolActivityDoNotLeaveEmptyTranscriptMessages() {
    val history =
      codec.parseHistory(
        historyJson =
          """
          {
            "messages": [
              {
                "role": "user",
                "content": [{"type": "text", "text": "Present the app"}]
              },
              {
                "role": "assistant",
                "content": [
                  {"type": "thinking", "thinking": "Inspecting"},
                  {"type": "toolCall", "id": "tool-1", "name": "exec"}
                ],
                "__openclaw": {"id": "activity-1"}
              },
              {
                "role": "assistant",
                "content": [{"type": "toolResult", "toolCallId": "tool-1", "content": "done"}],
                "__openclaw": {"id": "activity-2"}
              },
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "  "}],
                "__openclaw": {"id": "blank-activity"}
              },
              {
                "role": "assistant",
                "content": [{"type": "text", "text": "The app is ready"}],
                "__openclaw": {"id": "answer-1"}
              }
            ]
          }
          """.trimIndent(),
        sessionKey = "main",
        previousMessages = emptyList(),
      )

    assertEquals(listOf("user", "assistant"), history.messages.map { it.role })
    assertEquals(listOf("Present the app", "The app is ready"), history.messages.map { it.content.single().text })
  }
}
