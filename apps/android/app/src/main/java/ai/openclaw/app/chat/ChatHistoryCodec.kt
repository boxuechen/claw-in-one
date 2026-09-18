package ai.openclaw.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

internal class ChatHistoryCodec(
  private val json: Json,
  private val sessionCodec: ChatSessionCatalogCodec,
) {
  fun parseHistory(
    historyJson: String,
    sessionKey: String,
    previousMessages: List<ChatMessage>,
  ): ChatHistory {
    val root = json.parseToJsonElement(historyJson).asObjectOrNull() ?: return ChatHistory(sessionKey, null, null, emptyList())
    val sid = root["sessionId"].asStringOrNull()
    val thinkingLevel = root["thinkingLevel"].asStringOrNull()
    val sessionInfo = root["sessionInfo"].asObjectOrNull()?.let { sessionCodec.parseEntry(it, fallbackKey = sessionKey) }
    val array = root["messages"].asArrayOrNull() ?: JsonArray(emptyList())

    val entries = array.mapNotNull(JsonElement::asObjectOrNull)
    val messages = entries.mapNotNull(::parseMessage)
    val commentary = entries.mapNotNull(::parseCommentarySegment)

    return ChatHistory(
      sessionKey = sessionKey,
      sessionId = sid,
      thinkingLevel = thinkingLevel,
      messages = reconcileMessageIds(previous = previousMessages, incoming = messages),
      commentary = commentary,
      sessionInfo = sessionInfo,
      inFlightRun = parseInFlightRun(root),
    )
  }

  fun parseMessage(
    obj: JsonObject,
    maxChars: Int = 8_000,
  ): ChatMessage? {
    // Gateway 9.4 display projections are not canonical conversation messages.
    // They have separate owners below and must never inherit answer actions.
    if (obj["openclawStreamFallback"] is JsonObject || obj["openclawMessageToolMirror"] is JsonObject) return null
    val role = normalizeVisibleChatMessageRole(obj["role"].asStringOrNull()) ?: return null
    val metadata = obj["__openclaw"].asObjectOrNull()
    val content = parseChatMessageContents(obj)
    val provenance = parseChatMessageProvenance(obj["provenance"])
    val transcriptMarker = parseChatTranscriptMarker(metadata)
    // Reasoning and tool activity have dedicated owners. If removing those protocol
    // blocks leaves no canonical message payload, do not publish a transcript message.
    // Marker/provenance-only entries remain available to their purpose-specific rows.
    if (content.isEmpty() && provenance == null && transcriptMarker == null) return null
    // v2026.7.1-2 retains entry IDs but signals display caps with an exact terminal suffix.
    // Native clients can outlive their Gateway; normalize here until the minimum supported
    // Gateway guarantees the structural marker. The retrieval cap differs from history's.
    val legacySuffix = "\n...(truncated)..."
    val truncated = metadata?.get("truncated")
    return ChatMessage(
      id = UUID.randomUUID().toString(),
      role = role,
      content = content,
      timestampMs = obj["timestamp"].asLongOrNull(),
      idempotencyKey = obj["idempotencyKey"].asStringOrNull(),
      entryId = metadata?.get("id").asJsonStringOrNull(),
      truncated =
        truncated == JsonPrimitive(true) ||
          (truncated == null && content.any { it.type == "text" && it.text?.length == maxChars + legacySuffix.length && it.text.endsWith(legacySuffix) }),
      provenance = provenance,
      transcriptMarker = transcriptMarker,
      senderLabel = obj["senderLabel"].asJsonStringOrNull()?.trim()?.takeIf { role == "user" && it.isNotEmpty() },
    )
  }

  internal fun parseCommentarySegment(obj: JsonObject): ChatCommentarySegment? {
    if (obj["role"].asStringOrNull()?.trim()?.lowercase() != "assistant") return null
    val fallback = obj["openclawStreamFallback"].asObjectOrNull() ?: return null
    if (fallback["source"].asJsonStringOrNull() != "segment") return null
    val itemId = fallback["itemId"].asJsonStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val text =
      parseChatMessageContents(obj)
        .asSequence()
        .filter { it.type == "text" }
        .mapNotNull(ChatMessageContent::text)
        .filter(String::isNotBlank)
        .joinToString("\n\n")
        .trim()
        .takeIf(String::isNotEmpty) ?: return null
    val metadata = obj["__openclaw"].asObjectOrNull()
    val runId =
      sequenceOf(
        metadata?.get("runId").asJsonStringOrNull(),
        obj["runId"].asJsonStringOrNull(),
        fallback["runId"].asJsonStringOrNull(),
        metadata?.get("idempotencyKey").asJsonStringOrNull(),
        obj["idempotencyKey"].asJsonStringOrNull(),
      ).mapNotNull { it?.trim()?.removeSuffix(":user")?.takeIf(String::isNotEmpty) }
        .firstOrNull()
    return ChatCommentarySegment(
      runId = runId,
      itemId = itemId,
      text = text,
      timestampMs = obj["timestamp"].asLongOrNull(),
      sequence = metadata?.get("seq").asLongOrNull()?.takeIf { it > 0 },
    )
  }

  private fun parseChatMessageProvenance(element: JsonElement?): ChatMessageProvenance? {
    val obj = element.asObjectOrNull() ?: return null
    val kind = obj["kind"].asJsonStringOrNull() ?: return null
    return ChatMessageProvenance(
      kind = kind,
      sourceTool = obj["sourceTool"].asJsonStringOrNull(),
    )
  }

  private fun parseChatTranscriptMarker(element: JsonElement?): ChatTranscriptMarker? {
    val obj = element.asObjectOrNull() ?: return null
    val kind = obj["kind"].asJsonStringOrNull() ?: return null
    return ChatTranscriptMarker(
      kind = kind,
      id = obj["id"].asJsonStringOrNull(),
      tokensBefore = obj["tokensBefore"].asJsonNumberOrNull(),
      tokensAfter = obj["tokensAfter"].asJsonNumberOrNull(),
    )
  }

  private fun parseInFlightRun(root: JsonObject): ChatInFlightRun? {
    val obj = root["inFlightRun"].asObjectOrNull() ?: return null
    val runId = obj["runId"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ChatInFlightRun(
      runId = runId,
      text = obj["text"].asStringOrNull().orEmpty(),
    )
  }
}

private val MANAGED_IMAGE_PATH_REGEX =
  Regex("^/api/chat/media/outgoing/[^/]+/([0-9a-fA-F-]{36})/full(?:\\?.*)?$")

/**
 * Convert gateway chat content parts into Android UI content parts.
 */
internal fun parseChatMessageContent(el: JsonElement): ChatMessageContent? {
  val obj = el.asObjectOrNull() ?: return null
  return when (obj["type"].asStringOrNull() ?: "text") {
    "text", "input_text", "output_text" ->
      (obj["text"].asStringOrNull() ?: obj["content"].asStringOrNull())
        ?.takeIf(String::isNotBlank)
        ?.let { ChatMessageContent(type = "text", text = it) }

    "image" -> {
      val inlineContent = obj["content"].asStringOrNull()?.takeIf { it.isNotBlank() }
      val url = obj["url"].asStringOrNull()
      ChatMessageContent(
        type = "image",
        mimeType = obj["mimeType"].asStringOrNull(),
        fileName = contentLabel(obj),
        artifactId =
          obj["artifactId"].asStringOrNull()
            ?: managedImageArtifactId(url),
        base64 = inlineContent?.takeIf { it.length <= CHAT_IMAGE_MAX_BASE64_CHARS },
      )
    }

    "attachment", "file" -> {
      val attachment = obj["attachment"].asObjectOrNull() ?: obj
      val kind = attachment["kind"].asStringOrNull()
      val type =
        if (kind == "document" || !attachment.containsKey("kind")) {
          "file"
        } else {
          "unsupported"
        }
      ChatMessageContent(
        type = type,
        mimeType = attachment["mimeType"].asStringOrNull(),
        fileName = contentLabel(attachment),
      )
    }

    // These blocks drive the dedicated reasoning/tool activity owners. They are not
    // message attachments, so keeping them as unsupported content would duplicate the
    // activity UI and expose a misleading attachment affordance after history reload.
    "thinking", "reasoning", "redacted_thinking",
    "toolCall", "tool_call", "toolUse", "tool_use",
    "toolResult", "tool_result",
    -> null

    else ->
      ChatMessageContent(
        type = "unsupported",
        mimeType = obj["mimeType"].asStringOrNull(),
        fileName = contentLabel(obj),
      )
  }
}

internal fun managedImageArtifactId(rawUrl: String?): String? {
  val attachmentId = managedImageAttachmentId(rawUrl) ?: return null
  return "artifact_managed_image_$attachmentId"
}

private fun managedImageAttachmentId(rawUrl: String?): String? {
  val match =
    rawUrl
      ?.trim()
      ?.let(MANAGED_IMAGE_PATH_REGEX::matchEntire)
      ?: return null
  return runCatching { UUID.fromString(match.groupValues[1]).toString() }.getOrNull()
}

private fun contentLabel(obj: JsonObject): String? =
  sequenceOf(
    obj["fileName"].asStringOrNull(),
    obj["alt"].asStringOrNull(),
    obj["label"].asStringOrNull(),
    obj["name"].asStringOrNull(),
    obj["title"].asStringOrNull(),
    obj["preview"].asObjectOrNull()?.get("title").asStringOrNull(),
  ).firstNotNullOfOrNull { label ->
    label
      ?.trim()
      ?.take(160)
      ?.takeIf(String::isNotEmpty)
  }

internal fun parseChatMessageContents(obj: JsonObject): List<ChatMessageContent> {
  val content =
    obj["content"].asArrayOrNull()?.mapNotNull(::parseChatMessageContent)
      ?: obj["content"].asStringOrNull()?.let { listOf(ChatMessageContent(type = "text", text = it)) }
      ?: obj["text"].asStringOrNull()?.let { listOf(ChatMessageContent(type = "text", text = it)) }
      ?: emptyList()
  return content
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

private fun JsonElement?.asJsonNumberOrNull(): Double? =
  (this as? JsonPrimitive)
    ?.takeUnless(JsonPrimitive::isString)
    ?.content
    ?.toDoubleOrNull()

private fun JsonElement?.asLongOrNull(): Long? =
  when (this) {
    is JsonPrimitive -> content.toLongOrNull()
    else -> null
  }
