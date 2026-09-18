package ai.openclaw.app.plugin

import kotlinx.coroutines.flow.StateFlow

/** Immutable Gateway Plugin lifecycle projection. ClawHub directory data is a separate owner. */
internal data class PluginState(
  val connected: Boolean = false,
  val adminScope: Boolean = false,
  val capabilities: PluginGatewayCapabilities = PluginGatewayCapabilities(),
  val summary: GatewayPluginCatalogSummary = GatewayPluginCatalogSummary(),
  val refreshing: Boolean = false,
  val errorText: String? = null,
  val search: GatewayPluginSearchState = GatewayPluginSearchState(),
  val inspection: GatewayPluginInspectionState = GatewayPluginInspectionState.Idle,
  val mutation: GatewayPluginMutationState = GatewayPluginMutationState.Idle,
)

internal data class PluginActions(
  val refresh: () -> Unit,
  val search: (String) -> Unit,
  val inspect: (String) -> Unit,
  val requestInstall: (GatewayPluginMutationIntent.Install) -> Unit,
  val requestSetEnabled: (String, String, Boolean) -> Unit,
  val requestUninstall: (String, String) -> Unit,
  val confirmMutation: () -> Unit,
  val confirmInstallPolicy: () -> Unit,
  val confirmCapabilities: () -> Unit,
  val dismissMutation: () -> Unit,
  val reconcileMutation: () -> Unit,
)

internal class PluginFeature(
  val state: StateFlow<PluginState>,
  val actions: PluginActions,
  val loadIcon: suspend (String) -> GatewayPluginIconPayload?,
)
