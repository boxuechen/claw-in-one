package ai.openclaw.app.webdelivery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal const val WEB_PROJECT_RESULT_EVENT_STREAM = "claw-in-one-web-project.result"
private const val WEB_PROJECT_RESULT_EVENT_PROTOCOL_VERSION = 1

internal sealed interface WebProjectGatewayEvent {
  val sessionKey: String
  val sessionId: String
  val runId: String
  val toolCallId: String
  val projectId: String
  val generation: Int

  data class Ready(
    override val sessionKey: String,
    override val sessionId: String,
    override val runId: String,
    override val toolCallId: String,
    override val projectId: String,
    override val generation: Int,
    val resultId: String,
    val targetId: String,
    val url: String,
    val appName: String,
  ) : WebProjectGatewayEvent

  data class Stopped(
    override val sessionKey: String,
    override val sessionId: String,
    override val runId: String,
    override val toolCallId: String,
    override val projectId: String,
    override val generation: Int,
  ) : WebProjectGatewayEvent
}

internal fun parseWebProjectGatewayEvent(
  event: String,
  payloadJson: String?,
  json: Json,
): WebProjectGatewayEvent? {
  if (event != "agent" || payloadJson.isNullOrBlank()) return null
  val payload = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return null
  if (payload.string("stream") != WEB_PROJECT_RESULT_EVENT_STREAM) return null
  val sessionKey = payload.string("sessionKey")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val runId = payload.string("runId")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val data = payload["data"] as? JsonObject ?: return null
  if (data.int("protocolVersion") != WEB_PROJECT_RESULT_EVENT_PROTOCOL_VERSION) return null
  val sessionId = data.string("executionSessionId")?.takeIf(SESSION_ID_PATTERN::matches) ?: return null
  val toolCallId = data.string("toolCallId")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val projectId = data.string("projectId")?.takeIf(ID_PATTERN::matches) ?: return null
  val generation = data.int("generation")?.takeIf { it > 0 } ?: return null
  return when (data.string("phase")) {
    "ready" ->
      WebProjectGatewayEvent.Ready(
        sessionKey = sessionKey,
        sessionId = sessionId,
        runId = runId,
        toolCallId = toolCallId,
        projectId = projectId,
        generation = generation,
        resultId = data.string("resultId")?.takeIf(ID_PATTERN::matches) ?: return null,
        targetId = data.string("targetId")?.takeIf(TARGET_ID_PATTERN::matches) ?: return null,
        url = data.string("url")?.takeIf(URL_PATTERN::matches) ?: return null,
        appName = data.string("appName")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 96 } ?: return null,
      )
    "stopped" ->
      WebProjectGatewayEvent.Stopped(
        sessionKey = sessionKey,
        sessionId = sessionId,
        runId = runId,
        toolCallId = toolCallId,
        projectId = projectId,
        generation = generation,
      )
    else -> null
  }
}

private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val TARGET_ID_PATTERN = Regex("[0-9a-f]{64}")
private val URL_PATTERN = Regex("http://127\\.0\\.0\\.1:38[0-9]{3}/")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
