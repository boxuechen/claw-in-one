package ai.openclaw.app.chat

import ai.openclaw.app.permissions.SessionPermissionTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTitlePreparationTest {
  private val target = SessionPermissionTarget("phone", "agent:main:claw-in-one-project:one", "main")

  @Test
  fun preparedTitleIsBoundToTheExactCreationCandidateAndConsumedAtAdmission() =
    runTest {
      val requests = mutableListOf<JsonObject>()
      val controller =
        ChatSessionTitlePreparationController(
          scope = backgroundScope,
          canPrepare = { true },
          request = { _, params ->
            requests += Json.parseToJsonElement(params) as JsonObject
            """{"title":"Release checklist"}"""
          },
          delayMs = 50,
        )
      val candidate =
        ChatSessionTitleCandidate(
          target = target,
          catalogRevision = 1,
          message = "Prepare a release checklist",
          modelRef = "openai/gpt-test",
        )

      controller.stage(candidate)
      advanceTimeBy(50)
      runCurrent()

      assertEquals(
        "main",
        requests
          .single()
          .getValue("agentId")
          .jsonPrimitive.content,
      )
      assertEquals(
        "Prepare a release checklist",
        requests
          .single()
          .getValue("message")
          .jsonPrimitive.content,
      )
      assertEquals(
        "openai/gpt-test",
        requests
          .single()
          .getValue("model")
          .jsonPrimitive.content,
      )
      assertEquals("Release checklist", controller.take(candidate))
      assertEquals(1, requests.size)
      assertNull(controller.take(candidate))
    }

  @Test
  fun changedPromptCancelsStalePreparationAndFailuresStayDisposable() =
    runTest {
      val requestedMessages = mutableListOf<String>()
      var fail = false
      val controller =
        ChatSessionTitlePreparationController(
          scope = backgroundScope,
          canPrepare = { true },
          request = { _, params ->
            val message =
              (Json.parseToJsonElement(params) as JsonObject)
                .getValue("message")
                .jsonPrimitive
                .content
            requestedMessages += message
            if (fail) error("offline")
            """{"title":"Current title"}"""
          },
          delayMs = 50,
        )
      val stale = ChatSessionTitleCandidate(target, 1, "This prompt is stale")
      val current = ChatSessionTitleCandidate(target, 1, "This prompt is current")

      controller.stage(stale)
      controller.stage(current)
      advanceTimeBy(50)
      runCurrent()
      assertEquals(listOf("This prompt is current"), requestedMessages)
      assertEquals("Current title", controller.take(current))

      fail = true
      controller.stage(stale)
      advanceTimeBy(50)
      runCurrent()
      assertNull(controller.take(stale))
    }

  @Test
  fun unsupportedShortAndCommandInputsNeverRequestGateway() =
    runTest {
      var supported = false
      var requestCount = 0
      val controller =
        ChatSessionTitlePreparationController(
          scope = backgroundScope,
          canPrepare = { supported },
          request = { _, _ ->
            requestCount += 1
            """{"title":"Ignored"}"""
          },
          delayMs = 0,
        )

      controller.stage(ChatSessionTitleCandidate(target, 1, "A sufficiently long prompt"))
      supported = true
      controller.stage(ChatSessionTitleCandidate(target, 1, "short"))
      controller.stage(ChatSessionTitleCandidate(target, 1, "/command with enough content"))
      runCurrent()

      assertEquals(0, requestCount)
    }
}
