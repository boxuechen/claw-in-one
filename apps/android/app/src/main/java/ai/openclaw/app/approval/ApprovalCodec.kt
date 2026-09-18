package ai.openclaw.app.approval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Pinned canonical reviewer contract. List/event payloads discover IDs, never supply review text. */
internal class ApprovalCodec(
  private val json: Json = Json,
) {
  fun ids(
    payload: String,
    kind: ApprovalKind,
  ): List<ApprovalRow> {
    val rows =
      (json.parseToJsonElement(payload) as? JsonArray ?: error("Malformed approval list"))
        .map { value ->
          val item = value as? JsonObject ?: error("Malformed approval list entry")
          ApprovalRow(item.id(), kind, item.time("createdAtMs"), item.time("expiresAtMs"))
        }
    require(rows.map { it.id }.toSet().size == rows.size)
    return rows
  }

  fun eventId(payload: String?): String? = runCatching { (json.parseToJsonElement(payload ?: return null) as JsonObject).id() }.getOrNull()

  /** Untrusted request metadata is only a subscription hint, never Chat attribution. */
  fun candidates(payload: String): Set<ApprovalSession> =
    runCatching {
      val value = json.parseToJsonElement(payload)
      val items = if (value is JsonArray) value else listOf(value)
      items
        .mapNotNull { item ->
          val request = (item as? JsonObject)?.get("request") as? JsonObject ?: return@mapNotNull null
          request.optionalNonEmptyText("sessionKey")?.let { ApprovalSession(it, request.optionalNonEmptyText("agentId")) }
        }.toSet()
    }.getOrDefault(emptySet())

  fun sessionEvent(payload: String): ApprovalSessionEvent {
    val root = json.parseToJsonElement(payload) as JsonObject
    require(root.keys.all { it in setOf("sessionKey", "sourceSessionKey", "updatedAtMs", "phase", "approval") })
    val approval = snapshot(root.getValue("approval") as JsonObject)
    require(root.text("phase") == if (approval is ApprovalSnapshot.Pending) "pending" else "terminal")
    return ApprovalSessionEvent(root.text("sessionKey"), root.optionalStrictNonEmptyText("sourceSessionKey"), root.time("updatedAtMs"), approval)
  }

  fun subscription(payload: String): ApprovalSessionReplay {
    val root = json.parseToJsonElement(payload) as JsonObject
    require(root.keys == setOf("subscribed", "key", "approvalReplay"))
    require((root["subscribed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true)
    root.text("key")
    val replay = root.getValue("approvalReplay") as JsonObject
    require(replay.keys == setOf("sessionKey", "updatedAtMs", "approvals", "truncated"))
    val rows = (replay.getValue("approvals") as JsonArray).map { (snapshot(it as JsonObject) as ApprovalSnapshot.Pending).row }
    require(rows.map { it.id }.distinct().size == rows.size)
    val truncated = (replay["truncated"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Missing truncation")
    return ApprovalSessionReplay(replay.text("sessionKey"), replay.time("updatedAtMs"), rows, truncated)
  }

  fun get(
    payload: String,
    id: String,
    kind: ApprovalKind,
  ): ApprovalSnapshot {
    val root = json.parseToJsonElement(payload) as JsonObject
    require(root.keys == setOf("approval"))
    return snapshot(root.getValue("approval") as JsonObject).also { require(it.id == id && it.kind == kind) }
  }

  fun resolve(
    payload: String,
    id: String,
    kind: ApprovalKind,
    decision: String,
  ): ApprovalResolution {
    val root = json.parseToJsonElement(payload) as JsonObject
    require(root.keys == setOf("applied", "approval"))
    val applied = (root["applied"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Missing applied")
    val terminal = snapshot(root.getValue("approval") as JsonObject) as? ApprovalSnapshot.Terminal ?: error("Not terminal")
    require(terminal.id == id && terminal.kind == kind)
    require(!applied || terminal.decision == decision)
    return ApprovalResolution(applied, terminal)
  }

  private fun snapshot(obj: JsonObject): ApprovalSnapshot {
    val id = obj.id()
    val created = obj.time("createdAtMs")
    val expires = obj.time("expiresAtMs")
    require(expires >= created)
    obj.text("urlPath")
    val presentation = obj.getValue("presentation") as JsonObject
    val decisions = decisions(presentation.getValue("allowedDecisions"))
    val details = details(presentation)
    val status = obj.text("status")
    val common = setOf("id", "urlPath", "createdAtMs", "expiresAtMs", "presentation", "status")
    if (status == "pending") {
      require(obj.keys.all { it in common || it == "sourceSessionKey" })
      val attribution = ApprovalAttribution(sourceSessionKey = obj.optionalStrictNonEmptyText("sourceSessionKey"))
      return ApprovalSnapshot.Pending(ApprovalRow(id, details.kind, created, expires, details, decisions, attribution = attribution))
    }
    require(obj.keys.all { it in common || it in setOf("resolvedAtMs", "source", "resolver", "reason", "decision") })
    val resolvedAtMs = obj.time("resolvedAtMs")
    val source = obj["source"]?.let(::source)
    obj["resolver"]?.let(::resolver)
    val reason = obj.text("reason")
    val decision = obj.optionalText("decision")
    val terminalStatus =
      when (status) {
        "allowed" -> {
          require(reason == "user" && decision in setOf("allow-once", "allow-always") && decision in decisions)
          ApprovalStatus.Allowed
        }
        "denied" -> {
          require(reason in setOf("user", "malformed-verdict", "no-route", "storage-corrupt") && decision == "deny")
          ApprovalStatus.Denied
        }
        "expired" -> {
          require(reason == "timeout" && "decision" !in obj)
          ApprovalStatus.Expired
        }
        "cancelled" -> {
          require(reason in setOf("run-aborted", "gateway-restart") && "decision" !in obj)
          ApprovalStatus.Cancelled
        }
        else -> error("Unknown approval status")
      }
    return ApprovalSnapshot.Terminal(id, details.kind, terminalStatus, decision, source, resolvedAtMs, details, created)
  }

  private fun details(obj: JsonObject): ApprovalDetails {
    val scope = obj["scope"]?.let(::scope).orEmpty()
    val agent = obj.optionalNonEmptyText("agentId")
    return when (obj.text("kind")) {
      "exec" -> {
        require(obj.keys.all { it in setOf("kind", "commandText", "commandPreview", "warningText", "host", "nodeId", "agentId", "scope", "allowedDecisions") })
        ApprovalDetails.Exec(obj.text("commandText"), obj.optionalText("commandPreview"), obj.optionalText("warningText"), obj.optionalText("host"), obj.optionalNonEmptyText("nodeId"), agent, scope)
      }
      "plugin" -> {
        require(obj.keys.all { it in setOf("kind", "title", "description", "detail", "severity", "pluginId", "toolName", "agentId", "scope", "allowedDecisions", "externalResolution") })
        val severity = obj.text("severity").also { require(it in setOf("info", "warning", "critical")) }
        val external = obj["externalResolution"]?.let { it as JsonObject }
        val externalChoices =
          external
            ?.let {
              require(it.keys == setOf("label", "decisions"))
              val values = (it.getValue("decisions") as JsonArray).map { v -> v.string() }
              require(values.size in 1..2 && values.toSet().size == values.size)
              require(values.all { choice -> choice in setOf("allow-once", "allow-always") })
              values.toSet()
            }.orEmpty()
        ApprovalDetails.Plugin(
          obj.boundedText("title", 80),
          obj.boundedText("description", 512),
          obj.optionalBoundedText("detail", 16_384),
          severity,
          obj.optionalNonEmptyText("pluginId"),
          obj.optionalNonEmptyText("toolName"),
          external?.boundedText("label", 80),
          externalChoices,
          agent,
          scope,
        )
      }
      else -> error("Unsupported approval owner")
    }
  }

  private fun scope(value: JsonElement): List<Pair<String, String>> {
    val obj = value as JsonObject
    val kind = obj.text("kind")
    when (kind) {
      "message-send" -> {
        require(obj.keys.all { it in setOf("kind", "target", "recipientCount", "recipients", "audience") })
        obj.boundedText("target", 128)
        obj.integer("recipientCount", 1..1_000_000)
        obj["recipients"]?.let { raw ->
          val recipients = raw as JsonArray
          require(recipients.size <= 5)
          recipients.forEach { require(it.string().codePointCount() in 1..128) }
        }
        obj.optionalStrictNonEmptyText("audience")?.let { require(it in setOf("internal", "external")) }
      }
      "payment" -> {
        require(obj.keys == setOf("kind", "amount", "currency", "target"))
        obj.boundedText("amount", 40)
        obj.boundedText("currency", 12)
        obj.boundedText("target", 128)
      }
      "external-post" -> {
        require(obj.keys == setOf("kind", "target", "visibility"))
        obj.boundedText("target", 128)
        require(obj.text("visibility") in setOf("public", "restricted"))
      }
      "standing-grant" -> {
        require(obj.keys.all { it in setOf("kind", "automation", "command", "expiresInDays") })
        obj.boundedText("automation", 128)
        obj.boundedText("command", 256)
        if ("expiresInDays" in obj) obj.integer("expiresInDays", 1..3650)
      }
      else -> error("Unknown approval scope")
    }
    return obj.entries.filter { it.key != "kind" }.map { (key, item) ->
      key to if (item is JsonArray) item.joinToString(", ") { it.string() } else (item as JsonPrimitive).content
    }
  }

  private fun source(value: JsonElement): ApprovalSource {
    val obj = value as JsonObject
    require(obj.keys.all { it in setOf("agentId", "sessionKey") })
    val agentId = obj.optionalStrictNonEmptyText("agentId")
    return ApprovalSource(obj.optionalStrictNonEmptyText("sessionKey"), agentId)
  }

  private fun resolver(value: JsonElement) {
    val obj = value as JsonObject
    require(obj.keys.all { it in setOf("kind", "id") })
    require(obj.text("kind") in setOf("device", "channel", "runtime", "system"))
    obj.optionalStrictNonEmptyText("id")
  }

  private fun decisions(value: JsonElement): List<String> {
    val values = (value as JsonArray).map { it.string() }
    require(values.size in 1..3 && values.toSet().size == values.size && "deny" in values)
    require(values.all { it in setOf("allow-once", "allow-always", "deny") })
    return values
  }

  private fun JsonElement.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Not text")

  private fun JsonObject.text(key: String): String = getValue(key).string().also { require(it.isNotEmpty()) }

  private fun JsonObject.optionalText(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.string()

  private fun JsonObject.optionalNonEmptyText(key: String): String? = optionalText(key)?.also { require(it.isNotEmpty()) }

  private fun JsonObject.optionalStrictNonEmptyText(key: String): String? = this[key]?.string()?.also { require(it.isNotEmpty()) }

  private fun JsonObject.boundedText(
    key: String,
    maxCodePoints: Int,
  ): String = text(key).also { require(it.codePointCount() <= maxCodePoints) }

  private fun JsonObject.optionalBoundedText(
    key: String,
    maxCodePoints: Int,
  ): String? = optionalStrictNonEmptyText(key)?.also { require(it.codePointCount() <= maxCodePoints) }

  private fun JsonObject.integer(
    key: String,
    range: IntRange,
  ): Int = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in range } ?: error("Invalid integer")

  private fun JsonObject.time(key: String): Long = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: error("Invalid time")

  private fun JsonObject.id(): String = text("id").also { require(validApprovalId(it)) }

  private fun String.codePointCount(): Int = codePointCount(0, length)
}

internal fun validApprovalId(value: String): Boolean =
  value.isNotEmpty() &&
    value !in setOf(".", "..") &&
    value.indices.all { index ->
      val char = value[index]
      when {
        char.isHighSurrogate() -> index + 1 < value.length && value[index + 1].isLowSurrogate()
        char.isLowSurrogate() -> index > 0 && value[index - 1].isHighSurrogate()
        else -> true
      }
    }

internal fun approvalGetParams(id: String): String = buildJsonObject { put("id", id) }.toString()

internal fun approvalResolveParams(
  id: String,
  kind: ApprovalKind,
  decision: String,
): String =
  buildJsonObject {
    put("id", id)
    put("kind", kind.wire)
    put("decision", decision)
  }.toString()
