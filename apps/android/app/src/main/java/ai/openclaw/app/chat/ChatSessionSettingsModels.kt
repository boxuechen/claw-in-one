package ai.openclaw.app.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

internal data class ChatSessionSettingsKey(
  val gatewayScope: ChatCacheScope?,
  val sessionKey: String,
  val ownerAgentId: String?,
)

/** Settings-only exchange with the session catalog; never replaces unrelated row metadata. */
internal data class ChatSessionSettingsMetadata(
  val modelProvider: String? = null,
  val model: String? = null,
  val thinkingLevel: String? = null,
  val thinkingLevels: List<ChatThinkingLevelOption>? = null,
  val thinkingDefault: String? = null,
) {
  fun applyTo(entry: ChatSessionEntry): ChatSessionEntry =
    entry.copy(
      modelProvider = modelProvider,
      model = model,
      thinkingLevel = thinkingLevel,
      thinkingLevels = thinkingLevels,
      thinkingDefault = thinkingDefault,
    )
}

internal fun ChatSessionEntry.settingsMetadata(): ChatSessionSettingsMetadata = ChatSessionSettingsMetadata(modelProvider, model, thinkingLevel, thinkingLevels, thinkingDefault)

internal fun normalizeChatThinking(raw: String): String = raw.trim().lowercase(Locale.US).ifEmpty { "off" }

internal fun parseChatThinkingLevels(element: JsonElement?): List<ChatThinkingLevelOption>? {
  val array = element as? JsonArray ?: return null
  return array
    .mapNotNull { item ->
      val obj = item as? JsonObject ?: return@mapNotNull null
      val rawId = obj["id"].settingsString()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
      val id = normalizeChatThinking(rawId)
      val label = obj["label"].settingsString()?.takeIf(String::isNotEmpty) ?: id
      ChatThinkingLevelOption(id, label)
    }.distinctBy { it.id }
}

internal fun JsonElement?.settingsString(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()
