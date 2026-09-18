package ai.openclaw.app

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.gateway.Question
import ai.openclaw.app.gateway.QuestionListResult
import ai.openclaw.app.gateway.QuestionRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal object AndroidScreenshotFixture : AndroidScreenshotRuntimeFixture {
  @Volatile private var scene: AndroidScreenshotScene = AndroidScreenshotScene.Home

  internal val configuredScene: AndroidScreenshotScene
    get() = scene

  fun configure(scene: AndroidScreenshotScene) {
    this.scene = scene
  }

  override val gatewayId = "android-screenshot-gateway"
  override val controlUiBaseUrl = "http://127.0.0.1:18789"
  override val mainSessionKey = "agent:main:node-screenshot"
  const val primarySessionTitle = "Android release planning"

  override val offline = false

  override fun createRequester(): (String, String?) -> String {
    // A runtime gets a fresh lifetime; list refreshes and scene re-entry keep its exact record.
    val pendingQuestion =
      System.currentTimeMillis().let { nowMs ->
        QuestionRecord(
          id = "android-screenshot-question",
          questions =
            listOf(
              Question(
                questionId = "release_note",
                header = "Release note",
                question = "What should the release note mention?",
                options = emptyList(),
              ),
            ),
          agentId = "main",
          sessionKey = mainSessionKey,
          createdAtMs = nowMs,
          expiresAtMs = nowMs + 600_000,
          status = "pending",
        )
      }
    return { method, paramsJson ->
      when (method) {
        "health" -> buildJsonObject { put("ok", JsonPrimitive(true)) }.toString()
        "chat.history" -> chatHistory()
        "sessions.list" -> sessionList()
        "chat.metadata" -> chatMetadata()
        "question.list" -> Json.encodeToString(QuestionListResult(listOf(pendingQuestion)))
        else -> error("Screenshot fixture does not implement gateway method $method with params $paramsJson")
      }
    }
  }

  override val models =
    listOf(
      AiModel(
        id = "gpt-5.2",
        name = "GPT-5.2",
        provider = "openai",
        alias = null,
        tags = listOf("recommended"),
        available = true,
        unavailableReason = null,
        unavailableUntilEpochMs = null,
        supportsReasoning = true,
        contextTokens = 200_000,
        supportsTools = true,
        apiKeySupported = true,
      ),
    )

  private fun chatHistory(): String =
    buildJsonObject {
      put("sessionId", JsonPrimitive("screenshot-session"))
      put("thinkingLevel", JsonPrimitive("low"))
      put(
        "messages",
        buildJsonArray {
          repeat(24) { index ->
            add(
              chatMessage(
                role = "assistant",
                content = "Earlier discussion ${index + 1}: keep the release note concise and describe the user-visible change.",
                timestamp = 1_783_550_000_000 + index * 10_000L,
              ),
            )
          }
          add(chatMessage("user", "What is blocking the Android release?", 1_783_555_020_000))
          add(
            chatMessage(
              "assistant",
              "Two review threads are still open on the release branch, and the localization sync needs one more pass. " +
                "Once those land, the changelog draft is ready for review and the tag can go out.",
              1_783_555_080_000,
            ),
          )
          add(
            chatMessage(
              role = "user",
              content = "[System] Continue the interrupted turn.",
              timestamp = 1_783_555_100_000,
              provenanceSourceTool = "main_session_restart_recovery",
            ),
          )
          add(
            chatMessage(
              role = "user",
              content = "[System] Gateway restarted during the Android release update.",
              timestamp = 1_783_555_120_000,
              provenanceSourceTool = "restart-sentinel",
            ),
          )
          add(chatMessage("user", "Summarize the open review feedback for me.", 1_783_555_140_000))
          add(
            chatMessage(
              "assistant",
              "The release check is ready:\n\n```kotlin\nval ready = lint && tests\n```\n\n" +
                "Review https://openclaw.ai before tagging.",
              1_783_555_200_000,
            ),
          )
          add(
            chatMessage(
              role = "system",
              content = "Compaction",
              timestamp = 1_783_555_220_000,
              marker =
                buildJsonObject {
                  put("kind", JsonPrimitive("compaction"))
                  put("id", JsonPrimitive("android-screenshot-compaction"))
                  put("tokensBefore", JsonPrimitive(900_000))
                  put("tokensAfter", JsonPrimitive(24_700))
                },
            ),
          )
          add(
            chatMessage(
              role = "system",
              content = "Reset",
              timestamp = 1_783_555_240_000,
              marker =
                buildJsonObject {
                  put("kind", JsonPrimitive("reset"))
                  put("id", JsonPrimitive("android-screenshot-reset"))
                },
            ),
          )
          add(chatMessage("user", "Draft a short status update for the team.", 1_783_555_260_000))
          add(
            chatMessage(
              "assistant",
              "The Android release is close. Two review follow-ups and one localization pass remain; once those land, " +
                "the changelog can be reviewed and the tag can go out.",
              1_783_555_320_000,
            ),
          )
        },
      )
      put(
        "sessionInfo",
        buildJsonObject {
          put("key", JsonPrimitive(mainSessionKey))
          put("displayName", JsonPrimitive("New chat"))
          put("updatedAt", JsonPrimitive(1_783_555_320_000))
          put("unread", JsonPrimitive(false))
          put("modelProvider", JsonPrimitive("openai"))
          put("model", JsonPrimitive("gpt-5.2"))
          put("contextTokens", JsonPrimitive(200_000))
        },
      )
      if (scene != AndroidScreenshotScene.BlankChat) {
        put(
          "inFlightRun",
          buildJsonObject {
            put("runId", JsonPrimitive("android-screenshot-active-run"))
            put("text", JsonPrimitive(""))
          },
        )
      }
    }.toString()

  private fun chatMessage(
    role: String,
    content: String,
    timestamp: Long,
    provenanceSourceTool: String? = null,
    marker: JsonObject? = null,
  ) = buildJsonObject {
    put("role", JsonPrimitive(role))
    put("content", JsonPrimitive(content))
    put("timestamp", JsonPrimitive(timestamp))
    provenanceSourceTool?.let { sourceTool ->
      put(
        "provenance",
        buildJsonObject {
          put("kind", JsonPrimitive("internal_system"))
          put("sourceTool", JsonPrimitive(sourceTool))
        },
      )
    }
    marker?.let { put("__openclaw", it) }
  }

  private fun sessionList(): String =
    buildJsonObject {
      put(
        "sessions",
        buildJsonArray {
          add(session("discord:release-planning", primarySessionTitle, 1_783_555_200_000))
          add(session("main", "Product notes", 1_783_468_800_000))
          add(session("discord:android", "Android QA", 1_783_382_400_000))
        },
      )
      put("totalCount", JsonPrimitive(3))
    }.toString()

  private fun session(
    key: String,
    displayName: String,
    updatedAt: Long,
  ) = buildJsonObject {
    put("key", JsonPrimitive(key))
    put("displayName", JsonPrimitive(displayName))
    put("updatedAt", JsonPrimitive(updatedAt))
    put("lastActivityAt", JsonPrimitive(updatedAt))
    put("unread", JsonPrimitive(false))
    put("archived", JsonPrimitive(false))
    put("modelProvider", JsonPrimitive("openai"))
    put("model", JsonPrimitive("gpt-5.2"))
    put("totalTokens", JsonPrimitive(18_420))
    put("contextTokens", JsonPrimitive(200_000))
  }

  private fun chatMetadata(): String =
    buildJsonObject {
      put(
        "commands",
        buildJsonArray {
          listOf(
            Triple("help", "Show available commands.", false),
            Triple("commands", "List all slash commands.", false),
            Triple("tools", "List available runtime tools.", true),
            Triple("skill", "Run a skill by name.", true),
            Triple("learn", "Draft a reusable skill from recent work or named sources.", true),
            Triple("loop", "Loop a prompt: /loop [interval] <prompt> | /loop status | /loop stop [name]", true),
          ).forEach { (name, description, acceptsArgs) ->
            add(
              buildJsonObject {
                put("name", JsonPrimitive(name))
                put("description", JsonPrimitive(description))
                put("acceptsArgs", JsonPrimitive(acceptsArgs))
              },
            )
          }
        },
      )
      put(
        "models",
        buildJsonArray {
          add(
            buildJsonObject {
              put("id", JsonPrimitive("gpt-5.2"))
              put("name", JsonPrimitive("GPT-5.2"))
              put("provider", JsonPrimitive("openai"))
              put("available", JsonPrimitive(true))
              put("reasoning", JsonPrimitive(true))
              put("contextWindow", JsonPrimitive(200_000))
              put(
                "input",
                buildJsonArray {
                  add(JsonPrimitive("text"))
                  add(JsonPrimitive("image"))
                  add(JsonPrimitive("audio"))
                  add(JsonPrimitive("document"))
                },
              )
            },
          )
        },
      )
    }.toString()
}
