package ai.openclaw.app.ui.extensions

import ai.openclaw.app.plugin.GatewayPluginCatalogEntry
import ai.openclaw.app.plugin.PluginFeature
import ai.openclaw.app.plugin.PluginState
import ai.openclaw.app.skill.SkillFeature
import ai.openclaw.app.skill.SkillState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Adapts the two independent Gateway inventories to the OpenClaw-owned capability surface. */
@Composable
internal fun BuiltInCapabilitiesRoute(
  plugin: PluginFeature?,
  skill: SkillFeature?,
  destination: ExtensionCenterDestination.BuiltInCapabilities,
  contentPadding: PaddingValues,
  onSelectDirectory: (BuiltInCapabilityDirectory) -> Unit,
) {
  val pluginState = plugin?.state?.collectAsState()?.value ?: PluginState()
  val pluginSummary = pluginState.summary
  val pluginRefreshing = pluginState.refreshing
  val pluginErrorText = pluginState.errorText
  val pluginInventoryAvailable = pluginState.capabilities.inventory
  val pluginSetEnabledAvailable = pluginState.capabilities.setEnabled
  val pluginMutationState = pluginState.mutation
  val skillState = skill?.state?.collectAsState()?.value ?: SkillState()
  val skillsSummary = skillState.summary
  val skillsRefreshing = skillState.refreshing
  val skillErrorText = skillState.errorText
  val mutatingSkillKeys = skillState.mutationKeys
  val operatorAdmin = pluginState.adminScope
  val connected = pluginState.connected

  val pluginLifecycle =
    pluginLifecyclePresentation(
      state = pluginMutationState,
      canInstall = false,
      canSetEnabled =
        connected && operatorAdmin && pluginSetEnabledAvailable && pluginSummary.mutationAllowed,
      canUninstall = false,
    )
  val state =
    BuiltInCapabilitiesUiState(
      connected = connected,
      pluginInventoryAvailable = pluginInventoryAvailable,
      refreshing = pluginRefreshing || skillsRefreshing,
      pluginErrorText = pluginErrorText,
      skillErrorText = skillErrorText,
      plugins = builtInPlugins(pluginSummary.plugins.map(GatewayPluginCatalogEntry::toPresentationItem)),
      skills = builtInSkills(skillsSummary.skills.map { it.toInstalledSkillItem() }),
      canSetPluginEnabled = pluginLifecycle.canSetEnabled,
      canSetSkillEnabled = connected && operatorAdmin,
      pluginLifecycle = pluginLifecycle,
      mutatingSkillKeys = mutatingSkillKeys,
    )

  LaunchedEffect(connected, pluginInventoryAvailable) {
    if (connected) {
      if (pluginInventoryAvailable) plugin?.actions?.refresh?.invoke()
      skill?.actions?.refresh?.invoke()
    }
  }

  BuiltInCapabilitiesScreen(
    selectedDirectory = destination.directory,
    state = state,
    contentPadding = contentPadding,
    onSelectDirectory = onSelectDirectory,
    onRefresh = {
      plugin?.actions?.refresh?.invoke()
      skill?.actions?.refresh?.invoke()
    },
    onSetPluginEnabled = { item, enabled ->
      plugin?.actions?.requestSetEnabled?.invoke(item.pluginId, item.displayName, enabled)
    },
    onSetSkillEnabled = { skillKey, enabled -> skill?.actions?.setEnabled?.invoke(skillKey, enabled) },
    onRefreshPluginMutation = { plugin?.actions?.reconcileMutation?.invoke() },
    onDismissPluginMutation = { plugin?.actions?.dismissMutation?.invoke() },
  )

  PluginMutationDialog(
    dialog = pluginLifecycle.dialog,
    onDismiss = { plugin?.actions?.dismissMutation?.invoke() },
    onConfirmMutation = { plugin?.actions?.confirmMutation?.invoke() },
    onConfirmPolicy = { plugin?.actions?.confirmInstallPolicy?.invoke() },
    onConfirmCapabilities = { plugin?.actions?.confirmCapabilities?.invoke() },
  )
}
