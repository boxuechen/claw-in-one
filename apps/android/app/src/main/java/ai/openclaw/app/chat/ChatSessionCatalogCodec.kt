package ai.openclaw.app.chat

import ai.openclaw.app.gateway.SessionObserverDigest
import ai.openclaw.app.project.projectIdFromSessionKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

internal const val SESSION_LIST_FETCH_LIMIT = 200

internal data class ChatSessionListResult(
  val sessions: List<ChatSessionEntry>,
  val isTruncated: Boolean,
)

internal class ChatSessionCatalogCodec(
  private val json: Json,
) {
  fun parseList(jsonString: String): ChatSessionListResult {
    val root =
      json.parseToJsonElement(jsonString).asObjectOrNull()
        ?: return ChatSessionListResult(emptyList(), isTruncated = false)
    val sessions =
      root["sessions"]
        .asArrayOrNull()
        ?.mapNotNull { item -> parseEntry(item.asObjectOrNull()) }
        .orEmpty()
    val totalCount = root["totalCount"].asLongOrNull()
    val isTruncated =
      root["hasMore"].asBooleanOrNull() == true ||
        (totalCount != null && totalCount > sessions.size)
    return ChatSessionListResult(sessions, isTruncated)
  }

  fun parseEntry(
    obj: JsonObject?,
    fallbackKey: String? = null,
  ): ChatSessionEntry? {
    if (obj == null) return null
    val key =
      obj["key"]
        .asStringOrNull()
        ?.trim()
        .orEmpty()
        .ifEmpty {
          obj["sessionKey"]
            .asStringOrNull()
            ?.trim()
            .orEmpty()
        }.ifEmpty { fallbackKey?.trim().orEmpty() }
    if (key.isEmpty()) return null
    return ChatSessionEntry(
      key = key,
      projectId = obj["projectId"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: projectIdFromSessionKey(key),
      sessionId = obj["sessionId"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
      permissionMode = obj["permissionMode"].asStringOrNull(),
      permissionModePending = obj["permissionModePending"].asBooleanOrNull() == true,
      updatedAtMs = obj["updatedAt"].asLongOrNull(),
      ownerAgentId = obj["agentId"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
      classification = obj["classification"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
      accountId = obj["accountId"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
      peerKind = obj["peerKind"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
      isMain = obj["isMain"].asBooleanOrNull(),
      isBackground = obj["isBackground"].asBooleanOrNull(),
      hasClassificationMetadata =
        "classification" in obj ||
          "accountId" in obj ||
          "peerKind" in obj ||
          "isMain" in obj ||
          "isBackground" in obj,
      displayName = obj["displayName"].asStringOrNull()?.trim(),
      derivedTitle = obj["derivedTitle"].asStringOrNull()?.trim(),
      label = obj["label"].asStringOrNull()?.trim(),
      color =
        obj["color"]
          .asJsonStringOrNull()
          ?.trim()
          ?.lowercase()
          ?.takeIf { it.isNotEmpty() },
      hasColorMetadata = "color" in obj,
      pinned = obj["pinned"].asBooleanOrNull(),
      archived = obj["archived"].asBooleanOrNull(),
      unread = obj["unread"].asBooleanOrNull(),
      lastReadAt = obj["lastReadAt"].asLongOrNull(),
      markedUnreadAt = obj["markedUnreadAt"].asLongOrNull(),
      hasMarkedUnreadMetadata = "markedUnreadAt" in obj,
      agentStatus = parseSessionAgentStatus(obj["agentStatus"]),
      hasAgentStatusMetadata = "agentStatus" in obj,
      observerDigest =
        obj["observerDigest"]
          ?.takeUnless { it is JsonNull }
          ?.let { runCatching { json.decodeFromJsonElement<SessionObserverDigest>(it) }.getOrNull() },
      hasObserverDigestMetadata = "observerDigest" in obj,
      lastActivityAt = obj["lastActivityAt"].asLongOrNull(),
      totalTokens = obj["totalTokens"].asLongOrNull(),
      totalTokensFresh = obj["totalTokensFresh"].asBooleanOrNull(),
      modelProvider = obj["modelProvider"].asStringOrNull()?.trim(),
      model = obj["model"].asStringOrNull()?.trim(),
      thinkingLevel = obj["thinkingLevel"].asStringOrNull()?.trim(),
      thinkingLevels = parseChatThinkingLevels(obj["thinkingLevels"]),
      thinkingDefault = obj["thinkingDefault"].asStringOrNull()?.trim(),
      contextTokens = obj["contextTokens"].asLongOrNull(),
      hasContextUsageMetadata =
        "totalTokens" in obj ||
          "totalTokensFresh" in obj ||
          "contextTokens" in obj,
      hasActiveRun = obj["hasActiveRun"].asBooleanOrNull(),
      activeRunIds =
        obj["activeRunIds"]
          .asArrayOrNull()
          ?.mapNotNull { it.asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) },
      hasActiveRunMetadata = "hasActiveRun" in obj || "activeRunIds" in obj,
      hasActiveRunIdsMetadata = "activeRunIds" in obj,
      status = obj["status"].asStringOrNull()?.trim(),
      lastRunError = obj["lastRunError"].asStringOrNull()?.trim(),
      startedAt = obj["startedAt"].asLongOrNull(),
      endedAt = obj["endedAt"].asLongOrNull(),
      runtimeMs = obj["runtimeMs"].asLongOrNull(),
      outputTokens = obj["outputTokens"].asLongOrNull(),
      hasRunMetadata =
        "status" in obj ||
          "lastRunError" in obj ||
          "startedAt" in obj ||
          "endedAt" in obj ||
          "runtimeMs" in obj ||
          "outputTokens" in obj,
    )
  }

  private fun parseSessionAgentStatus(element: JsonElement?): ChatSessionAgentStatus? {
    val obj = element.asObjectOrNull() ?: return null
    val note = obj["note"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val expiresAt = obj["expiresAt"].asLongOrNull() ?: return null
    return ChatSessionAgentStatus(
      note = note,
      expiresAt = expiresAt,
      attention = obj["attention"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
    )
  }
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.asStringOrNull(): String? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
  }

private fun JsonElement?.asJsonStringOrNull(): String? =
  (this as? JsonPrimitive)
    ?.takeIf(JsonPrimitive::isString)
    ?.content

private fun JsonElement?.asLongOrNull(): Long? =
  when (this) {
    is JsonPrimitive -> content.toLongOrNull()
    else -> null
  }

private fun JsonElement?.asBooleanOrNull(): Boolean? =
  when (this) {
    is JsonPrimitive -> content.toBooleanStrictOrNull()
    else -> null
  }
