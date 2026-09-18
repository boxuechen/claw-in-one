package ai.openclaw.app.ai

import kotlinx.serialization.json.JsonElement

internal enum class AiSetupAuthKind {
  OAuth,
  DeviceCode,
  Install,
  Custom,
}

internal data class AiSetupCandidate(
  val id: String,
  val brandId: String?,
  val label: String,
  val detail: String,
  val modelRef: String?,
  val authOptionId: String?,
  val manualProviderId: String?,
  val recommended: Boolean,
  val credentials: Boolean?,
  val iconUrl: String?,
  val websiteUrl: String?,
)

internal data class AiSetupAuthOption(
  val id: String,
  val brandId: String?,
  val label: String,
  val hint: String?,
  val groupLabel: String?,
  val iconUrl: String?,
  val websiteUrl: String?,
  val kind: AiSetupAuthKind,
  val featured: Boolean,
)

internal data class AiManualProvider(
  val id: String,
  val brandId: String?,
  val groupLabel: String?,
  val label: String,
  val hint: String?,
  val iconUrl: String?,
  val websiteUrl: String?,
)

internal data class AiSetupDetection(
  val candidates: List<AiSetupCandidate>,
  val unavailableCandidates: List<AiSetupCandidate>,
  val manualProviders: List<AiManualProvider>,
  val authOptions: List<AiSetupAuthOption>,
  val workspace: String,
  val configuredModel: String?,
  val setupComplete: Boolean,
)

internal sealed interface AiSetupVerification {
  data class Ready(
    val modelRef: String,
    val latencyMs: Double,
  ) : AiSetupVerification

  data class Failed(
    val status: String,
    val error: String,
  ) : AiSetupVerification
}

internal data class AiSetupActivation(
  val authChoice: String,
  val apiKey: String,
  val modelRef: String,
)

internal enum class GatewayWizardStepType {
  Note,
  Select,
  Text,
  Confirm,
  MultiSelect,
  Progress,
  Action,
}

internal data class GatewayWizardOption(
  val value: JsonElement,
  val label: String,
  val hint: String?,
)

internal data class GatewayWizardDeviceCode(
  val code: String,
  val expiresInMinutes: Int?,
  val message: String?,
)

internal data class GatewayWizardStep(
  val id: String,
  val type: GatewayWizardStepType,
  val title: String?,
  val message: String?,
  val options: List<GatewayWizardOption>,
  val initialValue: JsonElement?,
  val placeholder: String?,
  val sensitive: Boolean,
  val executor: String?,
  val externalUrl: String?,
  val deviceCode: GatewayWizardDeviceCode?,
)

internal enum class GatewayWizardRunStatus {
  Running,
  Done,
  Cancelled,
  Error,
}

internal data class GatewayWizardModelActivation(
  val modelRef: String,
  val gatewayRestartRequired: Boolean,
)

internal data class GatewayWizardResult(
  val sessionId: String?,
  val done: Boolean,
  val status: GatewayWizardRunStatus?,
  val step: GatewayWizardStep?,
  val error: String?,
  val preparedModelRef: String?,
  val modelActivation: GatewayWizardModelActivation?,
)

internal data class GatewayWizardStatus(
  val status: GatewayWizardRunStatus,
  val error: String?,
)

internal enum class ModelUnavailableReason {
  MissingAuth,
  AuthFailed,
  Cooldown,
}

internal data class AiThinkingLevel(
  val id: String,
  val label: String,
)

internal data class AiModel(
  val id: String,
  val name: String,
  val provider: String,
  val alias: String?,
  val tags: List<String>,
  val available: Boolean?,
  val unavailableReason: ModelUnavailableReason?,
  val unavailableUntilEpochMs: Long?,
  val contextTokens: Long?,
  val supportsReasoning: Boolean,
  val supportsTools: Boolean?,
  val apiKeySupported: Boolean?,
  val thinkingLevels: List<AiThinkingLevel> = emptyList(),
  val thinkingDefault: String? = null,
)

internal data class ModelProviderOutcome(
  val provider: String,
  val status: String,
)

internal data class ModelCatalogSnapshot(
  val models: List<AiModel>,
  val refreshFailed: Boolean,
  val providerOutcomes: List<ModelProviderOutcome>,
)

internal sealed interface ModelCatalogStatus {
  data object Disconnected : ModelCatalogStatus

  data class Incompatible(
    val missingMethods: Set<String>,
  ) : ModelCatalogStatus

  data object Loading : ModelCatalogStatus

  data class Ready(
    val snapshot: ModelCatalogSnapshot,
  ) : ModelCatalogStatus

  data class Failed(
    val message: String,
  ) : ModelCatalogStatus
}

internal data class ModelCatalogState(
  val status: ModelCatalogStatus = ModelCatalogStatus.Disconnected,
  val refreshing: Boolean = false,
)

internal data class GatewayRestartPreflight(
  val safe: Boolean,
  val totalActive: Int,
  val summary: String,
)

internal enum class GatewayRestartRequestStatus {
  Scheduled,
  Deferred,
  Coalesced,
}

internal data class GatewayRestartRequestResult(
  val status: GatewayRestartRequestStatus,
  val preflight: GatewayRestartPreflight,
)
