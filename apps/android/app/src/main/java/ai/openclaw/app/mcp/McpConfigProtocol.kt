package ai.openclaw.app.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import java.net.URI

private val mcpServerNamePattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")

internal fun parseMcpConfigSnapshot(
  raw: String,
  json: Json,
): McpConfigSnapshot? {
  val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
  val hash = root.string("hash") ?: return null
  val config = (root["sourceConfig"] as? JsonObject) ?: (root["config"] as? JsonObject) ?: return null
  val mcp = config["mcp"] as? JsonObject
  val servers = mcp?.get("servers") as? JsonObject
  val parsed =
    servers
      ?.mapValues { (_, value) -> value as? JsonObject ?: return null }
      .orEmpty()
  return McpConfigSnapshot(hash = hash, servers = parsed)
}

internal fun McpConfigSnapshot.toSummary(): GatewayMcpConfigSummary =
  GatewayMcpConfigSummary(
    servers =
      servers
        .map { (name, config) -> config.toServerSummary(name) }
        .sortedBy { it.name.lowercase() },
  )

internal fun mcpHttpServerConfig(
  target: String,
  transport: McpServerTransport,
  auth: String? = null,
): JsonObject? {
  val normalized = target.trim()
  if (transport != McpServerTransport.StreamableHttp && transport != McpServerTransport.Sse) return null
  val scheme = runCatching { URI(normalized).scheme?.lowercase() }.getOrNull()
  if (scheme != "http" && scheme != "https") return null
  return buildJsonObject {
    put("url", JsonPrimitive(normalized))
    put(
      "transport",
      JsonPrimitive(if (transport == McpServerTransport.StreamableHttp) "streamable-http" else "sse"),
    )
    auth?.trim()?.takeIf(String::isNotEmpty)?.let { put("auth", JsonPrimitive(it)) }
  }
}

internal sealed interface McpPatchBuildResult {
  data class Ready(
    val paramsJson: String,
  ) : McpPatchBuildResult

  data class Error(
    val message: String,
  ) : McpPatchBuildResult
}

internal fun buildMcpPatchParams(
  snapshot: McpConfigSnapshot,
  intent: GatewayMcpMutationIntent,
): McpPatchBuildResult {
  val name = intent.serverName.trim()
  if (!mcpServerNamePattern.matches(name)) {
    return McpPatchBuildResult.Error("Use letters, numbers, dots, dashes, or underscores for the MCP server name.")
  }
  val serverPatch =
    when (intent) {
      is GatewayMcpMutationIntent.Add -> {
        if (name in snapshot.servers) return McpPatchBuildResult.Error("An MCP server named $name already exists.")
        intent.config
      }
      is GatewayMcpMutationIntent.SetEnabled -> {
        if (name !in snapshot.servers) return McpPatchBuildResult.Error("MCP server $name no longer exists.")
        buildJsonObject { put("enabled", if (intent.enabled) JsonNull else JsonPrimitive(false)) }
      }
      is GatewayMcpMutationIntent.Remove -> {
        if (name !in snapshot.servers) return McpPatchBuildResult.Error("MCP server $name no longer exists.")
        JsonNull
      }
    }
  val patch =
    buildJsonObject {
      put(
        "mcp",
        buildJsonObject {
          put("servers", buildJsonObject { put(name, serverPatch) })
        },
      )
    }
  return McpPatchBuildResult.Ready(
    buildJsonObject {
      put("baseHash", JsonPrimitive(snapshot.hash))
      put("raw", JsonPrimitive(patch.toString()))
      put("note", JsonPrimitive(intent.note()))
    }.toString(),
  )
}

internal fun McpConfigSnapshot.confirms(intent: GatewayMcpMutationIntent): Boolean =
  when (intent) {
    is GatewayMcpMutationIntent.Add -> servers[intent.serverName]?.matches(intent.config) == true
    is GatewayMcpMutationIntent.SetEnabled -> {
      val server = servers[intent.serverName] ?: return false
      ((server["enabled"] as? JsonPrimitive)?.booleanOrNull != false) == intent.enabled
    }
    is GatewayMcpMutationIntent.Remove -> intent.serverName !in servers
  }

private fun JsonObject.matches(expected: JsonObject): Boolean = expected.all { (key, value) -> this[key] == value }

private fun JsonObject.toServerSummary(name: String): GatewayMcpServerSummary {
  val url = stringPreservingWhitespace("url").orEmpty()
  val command = stringPreservingWhitespace("command").orEmpty()
  val transport =
    when {
      command.isNotEmpty() -> McpServerTransport.Stdio
      url.isEmpty() -> McpServerTransport.Invalid
      this["transport"] == null || string("transport") == "sse" -> McpServerTransport.Sse
      string("transport") == "streamable-http" -> McpServerTransport.StreamableHttp
      else -> McpServerTransport.Invalid
    }
  return GatewayMcpServerSummary(
    name = name,
    enabled = (this["enabled"] as? JsonPrimitive)?.booleanOrNull != false,
    transport = transport,
    target = if (command.isNotEmpty()) command else redactUrl(url),
    auth = string("auth")?.takeIf { it == "oauth" },
    toolFilter = this["toolFilter"] is JsonObject || this["toolFilter"] is JsonArray,
    parallel = (this["supportsParallelToolCalls"] as? JsonPrimitive)?.booleanOrNull == true,
    tls =
      when {
        (this["sslVerify"] as? JsonPrimitive)?.booleanOrNull == false -> "verify-off"
        this["clientCert"] != null || this["clientKey"] != null -> "mTLS"
        else -> null
      },
  )
}

private fun GatewayMcpMutationIntent.note(): String =
  when (this) {
    is GatewayMcpMutationIntent.Add -> "plugins: add MCP server $serverName"
    is GatewayMcpMutationIntent.SetEnabled -> "plugins: ${if (enabled) "enable" else "disable"} MCP server $serverName"
    is GatewayMcpMutationIntent.Remove -> "plugins: remove MCP server $serverName"
  }

private fun redactUrl(value: String): String {
  if (value.isBlank()) return ""
  return runCatching {
    val uri = URI(value)
    val host = uri.host ?: return@runCatching "${uri.scheme}://…"
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    val path = uri.rawPath.orEmpty()
    val query = if (uri.rawQuery == null) "" else "?…"
    "${uri.scheme}://$host$port$path$query"
  }.getOrDefault("Redacted endpoint")
}

private fun JsonObject.string(key: String): String? =
  stringPreservingWhitespace(key)
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun JsonObject.stringPreservingWhitespace(key: String): String? = (this[key] as? JsonPrimitive)?.content
