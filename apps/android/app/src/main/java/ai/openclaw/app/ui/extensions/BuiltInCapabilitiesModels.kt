package ai.openclaw.app.ui.extensions

/** Immutable projection for OpenClaw-owned capabilities hidden from the consumer directory. */
internal data class BuiltInCapabilitiesUiState(
  val connected: Boolean,
  val pluginInventoryAvailable: Boolean,
  val refreshing: Boolean,
  val pluginErrorText: String?,
  val skillErrorText: String?,
  val plugins: List<PluginCatalogItem>,
  val skills: List<InstalledSkillItem>,
  val canSetPluginEnabled: Boolean,
  val canSetSkillEnabled: Boolean,
  val pluginLifecycle: PluginLifecyclePresentation,
  val mutatingSkillKeys: Set<String>,
)

internal fun builtInPlugins(plugins: List<PluginCatalogItem>): List<PluginCatalogItem> =
  plugins
    .filter { it.isBuiltIn && !it.isProductInternal && it.status != PluginInstallStatus.Available }
    .sortedBy { it.displayName.lowercase() }

internal fun builtInSkills(
  skills: List<InstalledSkillItem>,
): List<InstalledSkillItem> =
  skills
    .filter(InstalledSkillItem::isBuiltIn)
    .sortedBy { it.displayName.lowercase() }
