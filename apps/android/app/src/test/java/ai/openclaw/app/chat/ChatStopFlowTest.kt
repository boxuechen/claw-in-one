package ai.openclaw.app.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStopFlowTest {
  private class Fixture(
    scope: TestScope,
  ) {
    val requests = mutableListOf<Pair<String, JsonObject>>()
    val reconciled = mutableListOf<ChatStopTarget>()
    var active = false
    val chat =
      ChatController(
        scope = scope.backgroundScope,
        json = chatControllerTestJson,
        cacheScope = { ChatCacheScope("phone", 1) },
        gatewayAdvertisesMethod = { it in setOf("sessions.list", "chat.history", "chat.send", "chat.abort", "health") },
        requestGateway = { method, payload ->
          val params = Json.parseToJsonElement(payload ?: "{}").jsonObject
          requests += method to params
          when (method) {
            "chat.history" ->
              buildJsonObject {
                val key = params.getValue("sessionKey").jsonPrimitive.content
                put("sessionId", JsonPrimitive("session-${key.substringAfterLast(':')}"))
                put("messages", JsonArray(emptyList()))
                put("sessionInfo", row(key))
              }.toString()
            "sessions.list" -> buildJsonObject { put("sessions", JsonArray(listOf(row("agent:main:a"), row("agent:main:b")))) }.toString()
            "chat.send" -> {
              active = true
              """{"runId":"run-a","status":"started"}"""
            }
            "chat.abort" -> {
              active = false
              """{"ok":true,"aborted":true,"runIds":["run-a"]}"""
            }
            else -> "{}"
          }
        },
        stopNativeControl = { true },
        onSessionStopped = {
          reconciled += it
          true
        },
      )

    private fun row(key: String) =
      buildJsonObject {
        put("key", JsonPrimitive(key))
        put("agentId", JsonPrimitive("main"))
        put("sessionId", JsonPrimitive("session-${key.substringAfterLast(':')}"))
        put("hasActiveRun", JsonPrimitive(active && key == "agent:main:a"))
        put("activeRunIds", JsonArray(if (active && key == "agent:main:a") listOf(JsonPrimitive("run-a")) else emptyList()))
      }
  }

  @Test fun switchImmediatelyAfterStopStillCancelsOnlyTheCapturedChat() =
    runTest {
      val f = Fixture(this)
      f.chat.load("agent:main:a")
      runCurrent()
      assertTrue(f.chat.sendMessageForOwnerAwaitAcceptance("test", "off", emptyList(), ChatComposerOwner("phone", "main", "agent:main:a")))
      f.chat.abort()
      f.chat.load("agent:main:b")
      runCurrent()
      val abort = f.requests.single { it.first == "chat.abort" }.second
      assertEquals(JsonPrimitive("agent:main:a"), abort["sessionKey"])
      assertEquals(JsonPrimitive("run-a"), abort["runId"])
      assertEquals(
        "agent:main:a",
        f.reconciled
          .single()
          .owner.sessionKey,
      )
      assertEquals("agent:main:b", f.chat.sessionKey.value)
      assertFalse(f.chat.stops.blocksSend(ChatComposerOwner("phone", "main", "agent:main:b")))
    }

  @Test fun stopWithoutLocalPendingRunsCanRecoverAnExistingIdleChat() =
    runTest {
      val f = Fixture(this)
      f.chat.load("agent:main:a")
      runCurrent()
      f.chat.abort()
      runCurrent()
      assertEquals(1, f.reconciled.size)
      assertTrue(f.requests.none { it.first == "chat.abort" || it.first == "chat.send" })
    }
}
