package ai.openclaw.app.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val CapabilityConsentCode = "PLUGIN_CAPABILITY_CONSENT_REQUIRED"
private const val InstallPolicyCode = "install_policy_warning_acknowledgement_required"
private val declaredSurfaceKeys =
  setOf(
    "channels",
    "providers",
    "tools",
    "contracts",
    "hooks",
    "mcpServers",
    "cliCommands",
    "cliBackends",
    "skills",
    "dangerousConfigFlags",
  )

internal fun pluginGatewayCapabilities(methods: Set<String>): PluginGatewayCapabilities =
  PluginGatewayCapabilities(
    inventory = "plugins.list" in methods,
    search = "plugins.search" in methods,
    inspect = "plugins.inspect" in methods,
    install = "plugins.install" in methods,
    setEnabled = "plugins.setEnabled" in methods,
    uninstall = "plugins.uninstall" in methods,
    refresh = "plugins.refresh" in methods,
  )

internal fun parsePluginCatalog(
  raw: String,
  json: Json,
): GatewayPluginCatalogSummary {
  val root = json.parseToJsonElement(raw) as? JsonObject ?: return GatewayPluginCatalogSummary()
  val plugins =
    (root["plugins"] as? JsonArray)
      ?.mapNotNull { value -> (value as? JsonObject)?.toGatewayPluginCatalogEntry() }
      .orEmpty()
  return GatewayPluginCatalogSummary(
    plugins = plugins,
    diagnosticsCount = (root["diagnostics"] as? JsonArray)?.size ?: 0,
    mutationAllowed = root.boolean("mutationAllowed"),
  )
}

internal fun parsePluginSearchResults(
  raw: String,
  json: Json,
): List<GatewayPluginSearchResult> {
  val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
  return (root["results"] as? JsonArray)
    ?.mapNotNull { value ->
      val result = value as? JsonObject ?: return@mapNotNull null
      val pluginPackage = result["package"] as? JsonObject ?: return@mapNotNull null
      GatewayPluginSearchResult(
        score = result.double("score") ?: 0.0,
        packageName = pluginPackage.string("name") ?: return@mapNotNull null,
        displayName = pluginPackage.string("displayName") ?: return@mapNotNull null,
        family = pluginPackage.string("family") ?: return@mapNotNull null,
        channel = pluginPackage.string("channel") ?: return@mapNotNull null,
        official = pluginPackage.boolean("isOfficial"),
        summary = pluginPackage.string("summary"),
        latestVersion = pluginPackage.string("latestVersion"),
        runtimeId = pluginPackage.string("runtimeId"),
        downloads = pluginPackage.long("downloads"),
        verificationTier = pluginPackage.string("verificationTier"),
      )
    }.orEmpty()
}

internal fun parsePluginInspection(
  raw: String,
  json: Json,
): GatewayPluginInspection? {
  val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
  if (!root.boolean("ok")) return null
  val plugin = (root["plugin"] as? JsonObject)?.toGatewayPluginIdentity() ?: return null
  val declared = (root["declared"] as? JsonObject)?.toDeclaredSurface() ?: return null
  val grants = (root["grants"] as? JsonObject)?.toOperatorGrants() ?: return null
  val reviewToken = root.stringPreservingWhitespace("reviewToken")?.takeIf(String::isNotEmpty) ?: return null
  return GatewayPluginInspection(
    plugin = plugin,
    source = (root["source"] as? JsonObject)?.toInspectSource(),
    declared = declared,
    reviewToken = reviewToken,
    grants = grants,
    trust = (root["trust"] as? JsonObject)?.toTrust(),
  )
}

internal fun parsePluginMutationResult(
  raw: String,
  json: Json,
): GatewayPluginMutationResult? {
  val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
  if (!root.boolean("ok")) return null
  return GatewayPluginMutationResult(
    plugin = (root["plugin"] as? JsonObject)?.toGatewayPluginCatalogEntry() ?: return null,
    restartRequired = root.requiredBoolean("restartRequired") ?: return null,
    warnings = root.optionalStringList("warnings") ?: return null,
  )
}

internal fun parsePluginUninstallResult(
  raw: String,
  json: Json,
): GatewayPluginUninstallResult? {
  val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
  if (!root.boolean("ok")) return null
  return GatewayPluginUninstallResult(
    pluginId = root.string("pluginId") ?: return null,
    restartRequired = root.requiredBoolean("restartRequired") ?: return null,
    removed = root.requiredStringList("removed") ?: return null,
    warnings = root.optionalStringList("warnings") ?: return null,
  )
}

internal fun parsePluginMutationChallenge(
  rawDetailsJson: String?,
  json: Json,
): PluginMutationChallenge? {
  val root = rawDetailsJson?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
  return parseCapabilityConsentChallenge(root)?.let(PluginMutationChallenge::CapabilityConsent)
    ?: parseInstallPolicyChallenge(root)?.let(PluginMutationChallenge::InstallPolicy)
}

internal fun pluginSearchParams(query: String): String =
  buildJsonObject {
    put("query", JsonPrimitive(query.trim()))
    put("limit", JsonPrimitive(20))
  }.toString()

internal fun pluginInspectParams(pluginId: String): String = buildJsonObject { put("pluginId", JsonPrimitive(pluginId.trim())) }.toString()

internal fun pluginInstallParams(
  action: GatewayPluginInstallAction,
  version: String? = null,
  acknowledgeInstallPolicyWarning: Boolean = false,
  capabilityReviewToken: String? = null,
): String =
  buildJsonObject {
    when (action) {
      is GatewayPluginInstallAction.ClawHub -> {
        put("source", JsonPrimitive("clawhub"))
        put("packageName", JsonPrimitive(action.packageName))
        version?.trim()?.takeIf(String::isNotEmpty)?.let { put("version", JsonPrimitive(it)) }
      }
      is GatewayPluginInstallAction.Official -> {
        put("source", JsonPrimitive("official"))
        put("pluginId", JsonPrimitive(action.pluginId))
      }
    }
    if (acknowledgeInstallPolicyWarning) put("acknowledgeInstallPolicyWarning", JsonPrimitive(true))
    capabilityReviewToken?.takeIf(String::isNotEmpty)?.let { token ->
      put("acknowledgeCapabilities", buildJsonObject { put("reviewToken", JsonPrimitive(token)) })
    }
  }.toString()

internal fun pluginSetEnabledParams(
  pluginId: String,
  enabled: Boolean,
  capabilityReviewToken: String? = null,
): String =
  buildJsonObject {
    put("pluginId", JsonPrimitive(pluginId.trim()))
    put("enabled", JsonPrimitive(enabled))
    capabilityReviewToken?.takeIf(String::isNotEmpty)?.let { token ->
      put("acknowledgeCapabilities", buildJsonObject { put("reviewToken", JsonPrimitive(token)) })
    }
  }.toString()

internal fun pluginUninstallParams(pluginId: String): String = buildJsonObject { put("pluginId", JsonPrimitive(pluginId.trim())) }.toString()

private fun JsonObject.toGatewayPluginCatalogEntry(): GatewayPluginCatalogEntry? {
  val install = (this["install"] as? JsonObject)?.toInstallAction()
  return GatewayPluginCatalogEntry(
    id = string("id") ?: return null,
    name = string("name") ?: return null,
    packageName = string("packageName"),
    description = string("description"),
    version = string("version"),
    kinds = stringList("kind"),
    origin = string("origin"),
    installed = boolean("installed"),
    enabled = boolean("enabled"),
    state = GatewayPluginState.fromWire(string("state")) ?: return null,
    featured = boolean("featured"),
    featuredAt = long("featuredAt"),
    order = double("order"),
    hasIcon = boolean("hasIcon"),
    category = string("category"),
    removable = boolean("removable"),
    install = install,
    error = string("error"),
  )
}

private fun JsonObject.toInstallAction(): GatewayPluginInstallAction? =
  when (string("source")) {
    "official" -> string("pluginId")?.let(GatewayPluginInstallAction::Official)
    "clawhub" -> string("packageName")?.let(GatewayPluginInstallAction::ClawHub)
    else -> null
  }

private fun JsonObject.toGatewayPluginIdentity(): GatewayPluginIdentity? =
  GatewayPluginIdentity(
    id = string("id") ?: return null,
    name = string("name") ?: return null,
    version = string("version"),
    description = string("description"),
    origin = string("origin"),
    installed = boolean("installed"),
    enabled = boolean("enabled"),
  )

private fun JsonObject.toInspectSource(): GatewayPluginInspectSource? =
  GatewayPluginInspectSource(
    kind = string("kind") ?: return null,
    spec = string("spec"),
    packageName = string("packageName"),
    integrity = stringPreservingWhitespace("integrity"),
    integrityKind = string("integrityKind"),
  )

private fun JsonObject.toDeclaredSurface(): GatewayPluginDeclaredSurface =
  GatewayPluginDeclaredSurface(
    channels = stringList("channels"),
    providers = stringList("providers"),
    tools = stringList("tools"),
    contracts = stringList("contracts"),
    hooks = stringList("hooks"),
    mcpServers = stringList("mcpServers"),
    cliCommands = stringList("cliCommands"),
    cliBackends = stringList("cliBackends"),
    skills = stringList("skills"),
    dangerousConfigFlags = stringList("dangerousConfigFlags"),
  )

private fun JsonObject.toHookGrant(): GatewayPluginHookGrant? =
  GatewayPluginHookGrant(
    effective = (this["effective"] as? JsonPrimitive)?.booleanOrNull ?: return null,
    configured = (this["configured"] as? JsonPrimitive)?.booleanOrNull,
  )

private fun JsonObject.toModelGrants(): GatewayPluginModelGrants =
  GatewayPluginModelGrants(
    allowModelOverride = (this["allowModelOverride"] as? JsonPrimitive)?.booleanOrNull,
    allowedModels = stringList("allowedModels"),
    allowedCompletionModels = stringList("allowedCompletionModels"),
    allowAuthProfileOverride = (this["allowAuthProfileOverride"] as? JsonPrimitive)?.booleanOrNull,
    allowAgentIdOverride = (this["allowAgentIdOverride"] as? JsonPrimitive)?.booleanOrNull,
  )

private fun JsonObject.toOperatorGrants(): GatewayPluginOperatorGrants? {
  val hooks = this["hooks"] as? JsonObject ?: return null
  return GatewayPluginOperatorGrants(
    allowPromptInjection = (hooks["allowPromptInjection"] as? JsonObject)?.toHookGrant() ?: return null,
    allowConversationAccess = (hooks["allowConversationAccess"] as? JsonObject)?.toHookGrant() ?: return null,
    llm = (this["llm"] as? JsonObject)?.toModelGrants(),
    subagent = (this["subagent"] as? JsonObject)?.toModelGrants(),
  )
}

private fun JsonObject.toTrust(): GatewayPluginTrust? =
  GatewayPluginTrust(
    disposition = GatewayPluginTrustDisposition.fromWire(string("disposition")) ?: return null,
    reasons = stringList("reasons"),
    checkedAt = string("checkedAt"),
    acknowledgedAt = string("acknowledgedAt"),
    pending = boolean("pending"),
    stale = boolean("stale"),
  )

private fun parseCapabilityConsentChallenge(root: JsonObject): PluginCapabilityConsentChallenge? {
  if (root.stringPreservingWhitespace("capabilityConsentCode") != CapabilityConsentCode) return null
  if (root.keys.any { it !in setOf("capabilityConsentCode", "pluginId", "reviewToken", "widened", "acceptedAt") }) {
    return null
  }
  val widenedElement = root["widened"]
  val widened =
    when (widenedElement) {
      null -> null
      is JsonObject -> widenedElement.toDeclaredSurfaceWidening() ?: return null
      else -> return null
    }
  return PluginCapabilityConsentChallenge(
    pluginId = root.stringPreservingWhitespace("pluginId")?.takeIf(String::isNotEmpty) ?: return null,
    reviewToken = root.stringPreservingWhitespace("reviewToken")?.takeIf(String::isNotEmpty) ?: return null,
    widened = widened,
    acceptedAt = root.stringPreservingWhitespace("acceptedAt")?.takeIf(String::isNotEmpty),
  )
}

private fun parseInstallPolicyChallenge(root: JsonObject): PluginInstallPolicyChallenge? {
  if (root.string("installPolicyCode") != InstallPolicyCode) return null
  val targetType = root.string("targetType")?.takeIf { it == "plugin" || it == "skill" } ?: return null
  val requestMode = root.string("requestMode")?.takeIf { it == "install" || it == "update" } ?: return null
  val findings =
    when (val findingsElement = root["findings"]) {
      null -> emptyList()
      is JsonArray ->
        findingsElement.map { finding ->
          (finding as? JsonObject)?.toPolicyFinding() ?: return null
        }
      else -> return null
    }
  return PluginInstallPolicyChallenge(
    targetName = root.string("targetName") ?: return null,
    targetType = targetType,
    requestMode = requestMode,
    reason = root.stringPreservingWhitespace("reason")?.takeIf(String::isNotEmpty) ?: return null,
    findings = findings,
  )
}

private fun JsonObject.toDeclaredSurfaceWidening(): GatewayPluginDeclaredSurface? {
  if (keys.any { it !in declaredSurfaceKeys }) return null

  fun values(key: String): List<String>? {
    val element = this[key] ?: return emptyList()
    val array = element as? JsonArray ?: return null
    return array.map { item ->
      (item as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty) ?: return null
    }
  }
  return GatewayPluginDeclaredSurface(
    channels = values("channels") ?: return null,
    providers = values("providers") ?: return null,
    tools = values("tools") ?: return null,
    contracts = values("contracts") ?: return null,
    hooks = values("hooks") ?: return null,
    mcpServers = values("mcpServers") ?: return null,
    cliCommands = values("cliCommands") ?: return null,
    cliBackends = values("cliBackends") ?: return null,
    skills = values("skills") ?: return null,
    dangerousConfigFlags = values("dangerousConfigFlags") ?: return null,
  )
}

private fun JsonObject.toPolicyFinding(): PluginInstallPolicyFinding? {
  val severity =
    when (string("severity")) {
      "info" -> PluginInstallPolicySeverity.Info
      "warn" -> PluginInstallPolicySeverity.Warning
      "critical" -> PluginInstallPolicySeverity.Critical
      else -> return null
    }
  return PluginInstallPolicyFinding(
    ruleId = string("ruleId") ?: return null,
    severity = severity,
    message = stringPreservingWhitespace("message")?.takeIf(String::isNotEmpty) ?: return null,
    file = string("file"),
    line = (this["line"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
    evidence = stringPreservingWhitespace("evidence")?.takeIf(String::isNotEmpty),
  )
}

private fun JsonObject.string(key: String): String? = stringPreservingWhitespace(key)?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.stringPreservingWhitespace(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonObject.stringList(key: String): List<String> =
  (this[key] as? JsonArray)
    ?.mapNotNull(JsonElement::stringValue)
    .orEmpty()

private fun JsonObject.requiredStringList(key: String): List<String>? = (this[key] as? JsonArray)?.map { it.stringValue() ?: return null }

private fun JsonObject.optionalStringList(key: String): List<String>? =
  when (val value = this[key]) {
    null -> emptyList()
    is JsonArray -> value.map { it.stringValue() ?: return null }
    else -> null
  }

private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty)

private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.requiredBoolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
