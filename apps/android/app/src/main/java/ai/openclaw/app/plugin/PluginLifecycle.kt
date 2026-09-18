package ai.openclaw.app.plugin

internal const val PLUGIN_MUTATION_REQUEST_TIMEOUT_MS = 150_000L

sealed interface GatewayPluginMutationIntent {
  val displayName: String
  val identity: String

  data class Install(
    val action: GatewayPluginInstallAction,
    override val displayName: String,
    val version: String? = null,
  ) : GatewayPluginMutationIntent {
    override val identity: String
      get() =
        when (action) {
          is GatewayPluginInstallAction.Official -> "plugin:${action.pluginId}"
          is GatewayPluginInstallAction.ClawHub -> "plugin:${action.packageName}"
        }
  }

  data class SetEnabled(
    val pluginId: String,
    override val displayName: String,
    val enabled: Boolean,
  ) : GatewayPluginMutationIntent {
    override val identity: String = "plugin:$pluginId"
  }

  data class Uninstall(
    val pluginId: String,
    override val displayName: String,
  ) : GatewayPluginMutationIntent {
    override val identity: String = "plugin:$pluginId"
  }
}

enum class GatewayPluginMutationStage {
  Installing,
  Enabling,
  Disabling,
  Removing,
  Inspecting,
  Reconciling,
  WaitingForRestart,
  Restarting,
  Reconnecting,
}

sealed interface GatewayPluginMutationState {
  data object Idle : GatewayPluginMutationState

  /** Only install and uninstall enter this state. Enable/disable follows the upstream direct action. */
  data class Confirmation(
    val intent: GatewayPluginMutationIntent,
  ) : GatewayPluginMutationState

  data class Working(
    val intent: GatewayPluginMutationIntent,
    val stage: GatewayPluginMutationStage,
  ) : GatewayPluginMutationState

  data class PolicyReview(
    val intent: GatewayPluginMutationIntent.Install,
    val challenge: PluginInstallPolicyChallenge,
  ) : GatewayPluginMutationState

  data class CapabilityReview(
    val intent: GatewayPluginMutationIntent,
    val pluginId: String,
    val inspection: GatewayPluginInspectionDetails,
    val widened: GatewayPluginDeclaredSurface?,
    val acceptedAt: String?,
  ) : GatewayPluginMutationState

  data class Succeeded(
    val intent: GatewayPluginMutationIntent,
    /** Null means the mutation was confirmed by readback after its response was lost. */
    val restartRequired: Boolean?,
    val warnings: List<String>,
    val removed: List<String> = emptyList(),
  ) : GatewayPluginMutationState

  data class Failed(
    val intent: GatewayPluginMutationIntent?,
    val message: String,
  ) : GatewayPluginMutationState

  data class UnknownOutcome(
    val intent: GatewayPluginMutationIntent,
    val message: String,
  ) : GatewayPluginMutationState
}

internal fun GatewayPluginCatalogSummary.confirms(intent: GatewayPluginMutationIntent): Boolean =
  when (intent) {
    is GatewayPluginMutationIntent.Install -> plugins.any { it.installed && it.matches(intent) }
    is GatewayPluginMutationIntent.SetEnabled ->
      plugins.any { it.id == intent.pluginId && it.installed && it.enabled == intent.enabled }
    is GatewayPluginMutationIntent.Uninstall -> plugins.none { it.id == intent.pluginId && it.installed }
  }

internal fun GatewayPluginCatalogEntry.matches(intent: GatewayPluginMutationIntent.Install): Boolean =
  when (val action = intent.action) {
    is GatewayPluginInstallAction.Official -> id == action.pluginId || install == action
    is GatewayPluginInstallAction.ClawHub -> packageName == action.packageName || install == action
  }
