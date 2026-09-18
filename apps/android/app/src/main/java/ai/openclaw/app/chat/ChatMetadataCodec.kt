package ai.openclaw.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun parseChatCommands(
  json: Json,
  commandsJson: String,
): List<ChatCommandEntry> {
  val root = json.parseToJsonElement(commandsJson).asObjectOrNull() ?: return emptyList()
  val commands = root["commands"].asArrayOrNull() ?: return emptyList()
  return commands.mapNotNull { item -> parseChatCommandEntry(item.asObjectOrNull()) }
}

private fun parseChatCommandEntry(obj: JsonObject?): ChatCommandEntry? {
  if (obj == null) return null
  val aliases =
    obj["textAliases"]
      .asArrayOrNull()
      ?.mapNotNull { alias -> alias.asStringOrNull()?.trim()?.takeIf { it.startsWith("/") && it.length > 1 } }
      ?.distinct()
      .orEmpty()
  val name =
    obj["name"]
      .asStringOrNull()
      ?.trim()
      ?.removePrefix("/")
      ?.takeIf { it.isNotEmpty() }
      ?: aliases.firstOrNull()?.removePrefix("/")
      ?: return null
  return ChatCommandEntry(
    name = name,
    description = obj["description"].asStringOrNull()?.trim().orEmpty(),
    category = obj["category"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
    textAliases = aliases,
    acceptsArgs = obj["acceptsArgs"].asBooleanOrNull() ?: false,
  )
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.asStringOrNull(): String? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
  }

private fun JsonElement?.asBooleanOrNull(): Boolean? =
  when (this) {
    is JsonPrimitive -> content.toBooleanStrictOrNull()
    else -> null
  }
