package ai.openclaw.app.plugin

data class GatewayPluginCatalogSummary(
  val plugins: List<GatewayPluginCatalogEntry> = emptyList(),
  val diagnosticsCount: Int = 0,
  val mutationAllowed: Boolean = false,
)

enum class GatewayPluginState(
  val wireValue: String,
) {
  Enabled("enabled"),
  Disabled("disabled"),
  NotInstalled("not-installed"),
  Error("error"),
  ;

  companion object {
    fun fromWire(value: String?): GatewayPluginState? = entries.firstOrNull { it.wireValue == value }
  }
}

sealed interface GatewayPluginInstallAction {
  data class Official(
    val pluginId: String,
  ) : GatewayPluginInstallAction

  data class ClawHub(
    val packageName: String,
  ) : GatewayPluginInstallAction
}

data class GatewayPluginCatalogEntry(
  val id: String,
  val name: String,
  val packageName: String?,
  val description: String?,
  val version: String?,
  val kinds: List<String>,
  val origin: String?,
  val installed: Boolean,
  val enabled: Boolean,
  val state: GatewayPluginState,
  val featured: Boolean,
  val featuredAt: Long?,
  val order: Double?,
  val hasIcon: Boolean,
  val category: String?,
  val removable: Boolean,
  val install: GatewayPluginInstallAction?,
  val error: String?,
)

data class GatewayPluginSearchState(
  val query: String = "",
  val searching: Boolean = false,
  val results: List<GatewayPluginSearchResult> = emptyList(),
  val errorText: String? = null,
)

data class GatewayPluginSearchResult(
  val score: Double,
  val packageName: String,
  val displayName: String,
  val family: String,
  val channel: String,
  val official: Boolean,
  val summary: String?,
  val latestVersion: String?,
  val runtimeId: String?,
  val downloads: Long?,
  val verificationTier: String?,
)

data class GatewayPluginIdentity(
  val id: String,
  val name: String,
  val version: String?,
  val description: String?,
  val origin: String?,
  val installed: Boolean,
  val enabled: Boolean,
)

data class GatewayPluginInspectSource(
  val kind: String,
  val spec: String?,
  val packageName: String?,
  val integrity: String?,
  val integrityKind: String?,
)

data class GatewayPluginDeclaredSurface(
  val channels: List<String> = emptyList(),
  val providers: List<String> = emptyList(),
  val tools: List<String> = emptyList(),
  val contracts: List<String> = emptyList(),
  val hooks: List<String> = emptyList(),
  val mcpServers: List<String> = emptyList(),
  val cliCommands: List<String> = emptyList(),
  val cliBackends: List<String> = emptyList(),
  val skills: List<String> = emptyList(),
  val dangerousConfigFlags: List<String> = emptyList(),
) {
  fun isEmpty(): Boolean =
    channels.isEmpty() &&
      providers.isEmpty() &&
      tools.isEmpty() &&
      contracts.isEmpty() &&
      hooks.isEmpty() &&
      mcpServers.isEmpty() &&
      cliCommands.isEmpty() &&
      cliBackends.isEmpty() &&
      skills.isEmpty() &&
      dangerousConfigFlags.isEmpty()
}

data class GatewayPluginHookGrant(
  val effective: Boolean,
  val configured: Boolean?,
)

data class GatewayPluginModelGrants(
  val allowModelOverride: Boolean?,
  val allowedModels: List<String>,
  val allowedCompletionModels: List<String>,
  val allowAuthProfileOverride: Boolean?,
  val allowAgentIdOverride: Boolean?,
)

data class GatewayPluginOperatorGrants(
  val allowPromptInjection: GatewayPluginHookGrant,
  val allowConversationAccess: GatewayPluginHookGrant,
  val llm: GatewayPluginModelGrants?,
  val subagent: GatewayPluginModelGrants?,
)

enum class GatewayPluginTrustDisposition(
  val wireValue: String,
) {
  Clean("clean"),
  ReviewRecommended("review-recommended"),
  ReviewRequired("review-required"),
  Blocked("blocked"),
  ;

  companion object {
    fun fromWire(value: String?): GatewayPluginTrustDisposition? = entries.firstOrNull { it.wireValue == value }
  }
}

data class GatewayPluginTrust(
  val disposition: GatewayPluginTrustDisposition,
  val reasons: List<String>,
  val checkedAt: String?,
  val acknowledgedAt: String?,
  val pending: Boolean,
  val stale: Boolean,
)

data class GatewayPluginInspection(
  val plugin: GatewayPluginIdentity,
  val source: GatewayPluginInspectSource?,
  val declared: GatewayPluginDeclaredSurface,
  val reviewToken: String,
  val grants: GatewayPluginOperatorGrants,
  val trust: GatewayPluginTrust?,
)

/** Inspection payload safe to expose to presentation. The one-time review token is excluded. */
data class GatewayPluginInspectionDetails(
  val plugin: GatewayPluginIdentity,
  val source: GatewayPluginInspectSource?,
  val declared: GatewayPluginDeclaredSurface,
  val grants: GatewayPluginOperatorGrants,
  val trust: GatewayPluginTrust?,
)

sealed interface GatewayPluginInspectionState {
  data object Idle : GatewayPluginInspectionState

  data class Loading(
    val pluginId: String,
  ) : GatewayPluginInspectionState

  data class Ready(
    val pluginId: String,
    val details: GatewayPluginInspectionDetails,
  ) : GatewayPluginInspectionState

  data class Error(
    val pluginId: String,
    val message: String,
  ) : GatewayPluginInspectionState
}

internal fun GatewayPluginInspection.toDetails(): GatewayPluginInspectionDetails =
  GatewayPluginInspectionDetails(
    plugin = plugin,
    source = source,
    declared = declared,
    grants = grants,
    trust = trust,
  )

data class GatewayPluginMutationResult(
  val plugin: GatewayPluginCatalogEntry,
  val restartRequired: Boolean,
  val warnings: List<String>,
)

data class GatewayPluginUninstallResult(
  val pluginId: String,
  val restartRequired: Boolean,
  val removed: List<String>,
  val warnings: List<String>,
)

data class PluginCapabilityConsentChallenge(
  val pluginId: String,
  val reviewToken: String,
  val widened: GatewayPluginDeclaredSurface?,
  val acceptedAt: String?,
)

enum class PluginInstallPolicySeverity {
  Info,
  Warning,
  Critical,
}

data class PluginInstallPolicyFinding(
  val ruleId: String,
  val severity: PluginInstallPolicySeverity,
  val message: String,
  val file: String?,
  val line: Int?,
  val evidence: String?,
)

data class PluginInstallPolicyChallenge(
  val targetName: String,
  val targetType: String,
  val requestMode: String,
  val reason: String,
  val findings: List<PluginInstallPolicyFinding>,
)

sealed interface PluginMutationChallenge {
  data class CapabilityConsent(
    val value: PluginCapabilityConsentChallenge,
  ) : PluginMutationChallenge

  data class InstallPolicy(
    val value: PluginInstallPolicyChallenge,
  ) : PluginMutationChallenge
}

data class PluginGatewayCapabilities(
  val inventory: Boolean = false,
  val search: Boolean = false,
  val inspect: Boolean = false,
  val install: Boolean = false,
  val setEnabled: Boolean = false,
  val uninstall: Boolean = false,
  val refresh: Boolean = false,
) {
  val lifecycleMutation: Boolean
    get() = install && setEnabled && uninstall
}
