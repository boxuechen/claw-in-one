package ai.openclaw.app.permissions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** The pinned Gateway protocol only; no fallback to retired execSecurity/execAsk fields. */
internal class SessionPermissionsCodec {
  private val json = Json { ignoreUnknownKeys = true }

  fun describeParams(target: SessionPermissionTarget): String =
    buildJsonObject {
      // sessions.describe accepts a canonical key, but no agentId argument.
      put("key", JsonPrimitive(target.key))
    }.toString()

  fun describe(payload: String): SessionPermissionRow = lookup(payload) ?: error("Session is unavailable")

  fun lookup(payload: String): SessionPermissionRow? {
    val response = root(payload)
    require("session" in response)
    if (response["session"] == JsonNull) return null
    val row = response["session"] as? JsonObject ?: error("Invalid Session row")
    val key = row.requiredString("key")
    val agentId = row.string("agentId") ?: key.split(':').takeIf { it.size >= 3 && it[0] == "agent" }?.get(1)
    require(!agentId.isNullOrBlank() && key.startsWith("agent:$agentId:"))
    val model = row.string("model")
    val provider = row.string("modelProvider")
    val modelRef = if (model != null && provider != null && !model.startsWith("$provider/")) "$provider/$model" else model
    return SessionPermissionRow(
      key = key,
      agentId = agentId,
      sessionId = row.requiredString("sessionId"),
      mode = mode(row),
      pending = row.boolean("permissionModePending") ?: false,
      projectId = row.string("projectId"),
      sessionRoot = row.string("sessionRoot"),
      modelRef = modelRef,
      thinkingLevel = row.string("thinkingLevel"),
      displayName = row.string("displayName"),
    )
  }

  fun createParams(draft: SessionPermissionDraft): String =
    buildJsonObject {
      put("key", JsonPrimitive(draft.target.key))
      put("agentId", JsonPrimitive(draft.target.agentId))
      put("idempotencyKey", JsonPrimitive(draft.idempotencyKey))
      put("permissionMode", JsonPrimitive(draft.mode.wire))
      draft.projectId?.let { put("projectId", JsonPrimitive(it)) }
      draft.modelRef?.let { put("model", JsonPrimitive(it)) }
      draft.thinkingLevel?.let { put("thinkingLevel", JsonPrimitive(it)) }
      draft.displayName?.let { put("displayName", JsonPrimitive(it)) }
      // No task/message/attachments: the Chat send owner dispatches once after confirmation.
    }.toString()

  fun patchParams(
    ref: SessionPermissionRef,
    expectedMode: SessionPermissionMode,
    requestedMode: SessionPermissionMode,
  ): String =
    buildJsonObject {
      put("key", JsonPrimitive(ref.target.key))
      put("agentId", JsonPrimitive(ref.target.agentId))
      put("expectedSessionId", JsonPrimitive(ref.sessionId))
      ref.lifecycleRevision?.let { put("expectedLifecycleRevision", JsonPrimitive(it)) }
      put("expectedPermissionMode", expectedMode.wire?.let(::JsonPrimitive) ?: JsonNull)
      put("permissionMode", requestedMode.wire?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

  fun mutation(payload: String): SessionPermissionMutation {
    val response = root(payload)
    require(response.boolean("ok") == true)
    val entry = response["entry"] as? JsonObject ?: error("Missing canonical Session entry")
    val sessionId = entry.requiredString("sessionId")
    response.string("sessionId")?.let { require(it == sessionId) }
    return SessionPermissionMutation(
      key = response.requiredString("key"),
      sessionId = sessionId,
      mode = mode(entry),
      lifecycleRevision = entry.string("lifecycleRevision"),
      projectId = entry.string("projectId"),
    )
  }

  fun changedKey(payload: String): String? = root(payload).string("sessionKey")

  fun isIdentityConflict(details: String?): Boolean = details != null && runCatching { root(details).string("reason") == "session-changed" }.getOrDefault(false)

  private fun root(payload: String) = json.parseToJsonElement(payload) as? JsonObject ?: error("Invalid Session response")

  private fun mode(row: JsonObject): SessionPermissionMode {
    val raw = row.string("permissionMode")
    return SessionPermissionMode.entries.firstOrNull { it.wire == raw } ?: error("Unsupported Session permission mode")
  }

  private fun JsonObject.string(key: String): String? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    require(value is JsonPrimitive && value.isString)
    return value.contentOrNull?.takeIf { it.isNotBlank() } ?: error("Invalid $key")
  }

  private fun JsonObject.requiredString(key: String) = string(key) ?: error("Missing $key")

  private fun JsonObject.boolean(key: String): Boolean? {
    val value = this[key] ?: return null
    require(value is JsonPrimitive && !value.isString)
    return value.booleanOrNull ?: error("Invalid $key")
  }
}
