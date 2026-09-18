package ai.openclaw.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun parseAiSetupDetection(
  raw: String,
  json: Json,
): AiSetupDetection? =
  runCatching {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
    AiSetupDetection(
      candidates = root.requiredObjects("candidates").map { it.toSetupCandidate(unavailable = false) },
      unavailableCandidates = root.optionalObjects("unavailableCandidates").map { it.toSetupCandidate(unavailable = true) },
      manualProviders = root.requiredObjects("manualProviders").map(JsonObject::toManualProvider),
      authOptions = root.optionalObjects("authOptions").map(JsonObject::toSetupAuthOption),
      workspace = root.requiredString("workspace"),
      configuredModel = root.optionalString("configuredModel"),
      setupComplete = root.requiredBoolean("setupComplete"),
    )
  }.getOrNull()

internal fun parseAiSetupVerification(
  raw: String,
  json: Json,
): AiSetupVerification? =
  runCatching {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
    if (root.requiredBoolean("ok")) {
      AiSetupVerification.Ready(
        modelRef = root.requiredString("modelRef"),
        latencyMs = root.requiredDouble("latencyMs"),
      )
    } else {
      AiSetupVerification.Failed(
        status = root.requiredString("status"),
        error = root.requiredString("error"),
      )
    }
  }.getOrNull()

internal fun parseGatewayWizardStartResult(
  raw: String,
  json: Json,
): GatewayWizardResult? = parseGatewayWizardResult(raw, json, requireSessionId = true)

internal fun parseGatewayWizardNextResult(
  raw: String,
  json: Json,
): GatewayWizardResult? = parseGatewayWizardResult(raw, json, requireSessionId = false)

internal fun parseGatewayWizardStatus(
  raw: String,
  json: Json,
): GatewayWizardStatus? =
  runCatching {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
    GatewayWizardStatus(
      status = root.requiredString("status").toWizardStatus(),
      error = root.optionalStringPreservingWhitespace("error"),
    )
  }.getOrNull()

private fun parseGatewayWizardResult(
  raw: String,
  json: Json,
  requireSessionId: Boolean,
): GatewayWizardResult? =
  runCatching {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
    GatewayWizardResult(
      sessionId = if (requireSessionId) root.requiredString("sessionId") else root.optionalString("sessionId"),
      done = root.requiredBoolean("done"),
      status = root.optionalString("status")?.toWizardStatus(),
      step = (root["step"] as? JsonObject)?.toWizardStep(),
      error = root.optionalStringPreservingWhitespace("error"),
      preparedModelRef = root.optionalString("preparedModelRef"),
      modelActivation = (root["modelActivation"] as? JsonObject)?.toWizardModelActivation(),
    )
  }.getOrNull()

internal fun parseModelCatalog(
  raw: String,
  json: Json,
): ModelCatalogSnapshot? =
  runCatching {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
    ModelCatalogSnapshot(
      models = root.requiredObjects("models").map(JsonObject::toAiModel),
      refreshFailed = root.optionalBoolean("refreshFailed") ?: false,
      providerOutcomes = root.optionalObjects("providerOutcomes").map(JsonObject::toProviderOutcome),
    )
  }.getOrNull()

internal fun parseGatewayRestartPreflight(
  raw: String,
  json: Json,
): GatewayRestartPreflight? =
  runCatching {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
    root.toRestartPreflight()
  }.getOrNull()

internal fun parseGatewayRestartRequestResult(
  raw: String,
  json: Json,
): GatewayRestartRequestResult? =
  runCatching {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
    if (!root.requiredBoolean("ok")) return null
    GatewayRestartRequestResult(
      status =
        when (root.requiredString("status")) {
          "scheduled" -> GatewayRestartRequestStatus.Scheduled
          "deferred" -> GatewayRestartRequestStatus.Deferred
          "coalesced" -> GatewayRestartRequestStatus.Coalesced
          else -> error("Unsupported restart status")
        },
      preflight = (root["preflight"] as? JsonObject)?.toRestartPreflight() ?: error("Missing preflight"),
    )
  }.getOrNull()

internal fun aiSetupDetectParams(agentId: String?): String = optionalAgentParams(agentId)

internal fun aiSetupVerifyParams(agentId: String?): String = optionalAgentParams(agentId)

internal fun agentsUpdateModelParams(
  agentId: String,
  modelRef: String?,
): String =
  buildJsonObject {
    put("agentId", JsonPrimitive(agentId.requireNormalized("agentId")))
    put("model", modelRef?.requireNormalized("modelRef")?.let(::JsonPrimitive) ?: JsonNull)
  }.toString()

internal fun aiSetupAuthStartParams(
  sessionId: String,
  authChoice: String,
  agentId: String?,
  workspace: String?,
  nativeSessionCatalogsEnabled: Boolean,
): String =
  setupStartParams(
    sessionId = sessionId,
    agentId = agentId,
    workspace = workspace,
    nativeSessionCatalogsEnabled = nativeSessionCatalogsEnabled,
  ) {
    put("authChoice", JsonPrimitive(authChoice.requireNormalized("authChoice")))
  }

internal fun aiSetupActivateStartParams(
  sessionId: String,
  activation: AiSetupActivation,
  agentId: String?,
  workspace: String?,
  nativeSessionCatalogsEnabled: Boolean,
): String =
  setupStartParams(
    sessionId = sessionId,
    agentId = agentId,
    workspace = workspace,
    nativeSessionCatalogsEnabled = nativeSessionCatalogsEnabled,
  ) {
    put("kind", JsonPrimitive("api-key"))
    put("authChoice", JsonPrimitive(activation.authChoice.requireNormalized("authChoice")))
    put("apiKey", JsonPrimitive(activation.apiKey.requireNormalized("apiKey")))
    put("modelRef", JsonPrimitive(activation.modelRef.requireNormalized("modelRef")))
  }

internal fun modelsListParams(
  agentId: String?,
  sessionKey: String? = null,
  refresh: Boolean,
  provider: String? = null,
  view: String = "configured",
): String =
  buildJsonObject {
    put("view", JsonPrimitive(view))
    put("includeDetails", JsonPrimitive(true))
    put("includeProviderCapabilities", JsonPrimitive(true))
    if (refresh) put("refresh", JsonPrimitive(true))
    provider.normalized()?.let { put("provider", JsonPrimitive(it)) }
    if (sessionKey == null) {
      agentId.normalized()?.let { put("agentId", JsonPrimitive(it)) }
    } else {
      sessionKey.normalized()?.let { put("sessionKey", JsonPrimitive(it)) }
    }
  }.toString()

internal fun wizardNextParams(
  sessionId: String,
): String = wizardSessionParams(sessionId)

internal fun wizardNextParams(
  sessionId: String,
  stepId: String,
  value: JsonElement?,
): String =
  buildJsonObject {
    put("sessionId", JsonPrimitive(sessionId.requireNormalized("sessionId")))
    put(
      "answer",
      buildJsonObject {
        put("stepId", JsonPrimitive(stepId.requireNormalized("stepId")))
        if (value != null) put("value", value)
      },
    )
  }.toString()

internal fun wizardSessionParams(sessionId: String): String = buildJsonObject { put("sessionId", JsonPrimitive(sessionId.requireNormalized("sessionId"))) }.toString()

internal fun gatewayRestartRequestParams(reason: String): String =
  buildJsonObject {
    put("reason", JsonPrimitive(reason.trim().take(200).ifEmpty { "clawinone.ai-setup" }))
    put("skipDeferral", JsonPrimitive(false))
  }.toString()

private fun optionalAgentParams(agentId: String?): String = buildJsonObject { agentId.normalized()?.let { put("agentId", JsonPrimitive(it)) } }.toString()

private fun setupStartParams(
  sessionId: String,
  agentId: String?,
  workspace: String?,
  nativeSessionCatalogsEnabled: Boolean,
  append: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): String =
  buildJsonObject {
    put("sessionId", JsonPrimitive(sessionId.requireNormalized("sessionId")))
    agentId.normalized()?.let { put("agentId", JsonPrimitive(it)) }
    workspace.normalized()?.let { put("workspace", JsonPrimitive(it)) }
    put("nativeSessionCatalogsEnabled", JsonPrimitive(nativeSessionCatalogsEnabled))
    append()
  }.toString()

private fun JsonObject.toSetupCandidate(unavailable: Boolean): AiSetupCandidate =
  AiSetupCandidate(
    id = if (unavailable) requiredString("id") else requiredString("kind"),
    brandId = optionalString("brandId"),
    label = requiredString("label"),
    detail = if (unavailable) requiredString("reason") else requiredStringPreservingWhitespace("detail"),
    modelRef = optionalString("modelRef"),
    authOptionId = optionalString("authOptionId"),
    manualProviderId = optionalString("manualProviderId"),
    recommended = if (unavailable) false else requiredBoolean("recommended"),
    credentials = optionalBoolean("credentials"),
    iconUrl = optionalHttpsUrl("icon"),
    websiteUrl = optionalHttpsUrl("website"),
  )

private fun JsonObject.toManualProvider(): AiManualProvider =
  AiManualProvider(
    id = requiredString("id"),
    brandId = optionalString("brandId"),
    groupLabel = optionalString("groupLabel"),
    label = requiredString("label"),
    hint = optionalStringPreservingWhitespace("hint"),
    iconUrl = optionalHttpsUrl("icon"),
    websiteUrl = optionalHttpsUrl("website"),
  )

private fun JsonObject.toSetupAuthOption(): AiSetupAuthOption =
  AiSetupAuthOption(
    id = requiredString("id"),
    brandId = optionalString("brandId"),
    label = requiredString("label"),
    hint = optionalStringPreservingWhitespace("hint"),
    groupLabel = optionalStringPreservingWhitespace("groupLabel"),
    iconUrl = optionalHttpsUrl("icon"),
    websiteUrl = optionalHttpsUrl("website"),
    kind =
      when (requiredString("kind")) {
        "oauth" -> AiSetupAuthKind.OAuth
        "device-code" -> AiSetupAuthKind.DeviceCode
        "install" -> AiSetupAuthKind.Install
        "custom" -> AiSetupAuthKind.Custom
        else -> error("Unsupported auth kind")
      },
    featured = requiredBoolean("featured"),
  )

private fun JsonObject.toWizardStep(): GatewayWizardStep =
  GatewayWizardStep(
    id = requiredString("id"),
    type =
      when (requiredString("type")) {
        "note" -> GatewayWizardStepType.Note
        "select" -> GatewayWizardStepType.Select
        "text" -> GatewayWizardStepType.Text
        "confirm" -> GatewayWizardStepType.Confirm
        "multiselect" -> GatewayWizardStepType.MultiSelect
        "progress" -> GatewayWizardStepType.Progress
        "action" -> GatewayWizardStepType.Action
        else -> error("Unsupported wizard step")
      },
    title = optionalStringPreservingWhitespace("title"),
    message = optionalStringPreservingWhitespace("message"),
    options =
      optionalObjects("options").map { option ->
        GatewayWizardOption(
          value = option["value"] ?: JsonNull,
          label = option.requiredString("label"),
          hint = option.optionalStringPreservingWhitespace("hint"),
        )
      },
    initialValue = this["initialValue"]?.takeUnless { it is JsonNull },
    placeholder = optionalStringPreservingWhitespace("placeholder"),
    sensitive = optionalBoolean("sensitive") ?: false,
    executor = optionalString("executor"),
    externalUrl = optionalHttpsUrl("externalUrl"),
    deviceCode =
      (this["deviceCode"] as? JsonObject)?.let { code ->
        GatewayWizardDeviceCode(
          code = code.requiredString("code"),
          expiresInMinutes = code.optionalInt("expiresInMinutes"),
          message = code.optionalStringPreservingWhitespace("message"),
        )
      },
  )

private fun JsonObject.toWizardModelActivation(): GatewayWizardModelActivation =
  GatewayWizardModelActivation(
    modelRef = requiredString("modelRef"),
    gatewayRestartRequired = optionalBoolean("gatewayRestartRequired") ?: false,
  )

private fun JsonObject.toAiModel(): AiModel =
  AiModel(
    id = requiredString("id"),
    name = requiredString("name"),
    provider = requiredString("provider"),
    alias = optionalString("alias"),
    tags = optionalStrings("tags"),
    available = optionalBoolean("available"),
    unavailableReason =
      when (optionalString("unavailableReason")) {
        null -> null
        "missing-auth" -> ModelUnavailableReason.MissingAuth
        "auth-failed" -> ModelUnavailableReason.AuthFailed
        "cooldown" -> ModelUnavailableReason.Cooldown
        else -> error("Unsupported unavailable reason")
      },
    unavailableUntilEpochMs = optionalLong("unavailableUntil"),
    contextTokens = optionalLong("contextTokens") ?: optionalLong("contextWindow"),
    supportsReasoning = optionalBoolean("reasoning") ?: false,
    supportsTools = optionalBoolean("supportsTools"),
    apiKeySupported = optionalBoolean("apiKeySupported"),
    thinkingLevels =
      optionalObjects("thinkingLevels").map { level ->
        AiThinkingLevel(
          id = level.requiredString("id"),
          label = level.requiredString("label"),
        )
      },
    thinkingDefault = optionalString("thinkingDefault"),
  )

private fun JsonObject.toProviderOutcome(): ModelProviderOutcome =
  ModelProviderOutcome(
    provider = requiredString("provider"),
    status = requiredString("status"),
  )

private fun JsonObject.toRestartPreflight(): GatewayRestartPreflight =
  GatewayRestartPreflight(
    safe = requiredBoolean("safe"),
    totalActive = (this["counts"] as? JsonObject)?.requiredInt("totalActive") ?: error("Missing counts"),
    summary = requiredStringPreservingWhitespace("summary"),
  )

private fun String.toWizardStatus(): GatewayWizardRunStatus =
  when (this) {
    "running" -> GatewayWizardRunStatus.Running
    "done" -> GatewayWizardRunStatus.Done
    "cancelled" -> GatewayWizardRunStatus.Cancelled
    "error" -> GatewayWizardRunStatus.Error
    else -> error("Unsupported wizard status")
  }

private fun JsonObject.requiredObjects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it as? JsonObject ?: error("Invalid $key") } ?: error("Missing $key")

private fun JsonObject.optionalObjects(key: String): List<JsonObject> =
  when (val value = this[key]) {
    null,
    JsonNull,
    -> emptyList()
    is JsonArray -> value.map { it as? JsonObject ?: error("Invalid $key") }
    else -> error("Invalid $key")
  }

private fun JsonObject.optionalStrings(key: String): List<String> =
  when (val value = this[key]) {
    null,
    JsonNull,
    -> emptyList()
    is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.normalized() ?: error("Invalid $key") }
    else -> error("Invalid $key")
  }

private fun JsonObject.requiredString(key: String): String = optionalString(key) ?: error("Missing $key")

private fun JsonObject.requiredStringPreservingWhitespace(key: String): String = optionalStringPreservingWhitespace(key)?.takeIf(String::isNotEmpty) ?: error("Missing $key")

private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.normalized()

private fun JsonObject.optionalStringPreservingWhitespace(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonObject.requiredBoolean(key: String): Boolean = optionalBoolean(key) ?: error("Missing $key")

private fun JsonObject.optionalBoolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.requiredLong(key: String): Long = optionalLong(key) ?: error("Missing $key")

private fun JsonObject.optionalLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.requiredInt(key: String): Int = optionalInt(key) ?: error("Missing $key")

private fun JsonObject.optionalInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.requiredDouble(key: String): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: error("Missing $key")

private fun JsonObject.optionalHttpsUrl(key: String): String? =
  optionalStringPreservingWhitespace(key)?.takeIf { it.startsWith("https://") } ?: optionalStringPreservingWhitespace(key)?.let {
    error("Invalid $key")
  }

private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String.requireNormalized(name: String): String = normalized() ?: error("Missing $name")
