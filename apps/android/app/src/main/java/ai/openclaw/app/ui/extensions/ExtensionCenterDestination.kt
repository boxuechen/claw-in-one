package ai.openclaw.app.ui.extensions

/** Consumer directories inside the extension center. These are navigation, not runtime filters. */
internal enum class ExtensionCenterDirectory {
  Plugins,
  Skills,
}

/** Source-owned capabilities that ship with OpenClaw rather than being added by the user. */
internal enum class BuiltInCapabilityDirectory {
  Plugins,
  Skills,
}

internal fun ExtensionCenterDirectory.toBuiltInCapabilityDirectory(): BuiltInCapabilityDirectory =
  when (this) {
    ExtensionCenterDirectory.Plugins -> BuiltInCapabilityDirectory.Plugins
    ExtensionCenterDirectory.Skills -> BuiltInCapabilityDirectory.Skills
  }

internal sealed interface PluginDetailOrigin {
  data object Directory : PluginDetailOrigin

  data class Category(
    val sectionId: PluginDirectorySectionId,
  ) : PluginDetailOrigin

  data object Settings : PluginDetailOrigin
}

/** Navigation owned by the extension center and independent from Settings. */
internal sealed interface ExtensionCenterDestination {
  data class Directory(
    val directory: ExtensionCenterDirectory = ExtensionCenterDirectory.Plugins,
  ) : ExtensionCenterDestination

  data class PluginDetail(
    val pluginId: String,
    val displayName: String,
    val origin: PluginDetailOrigin = PluginDetailOrigin.Directory,
    val returnDirectory: ExtensionCenterDirectory = ExtensionCenterDirectory.Plugins,
  ) : ExtensionCenterDestination

  data class PluginCategory(
    val sectionId: PluginDirectorySectionId,
  ) : ExtensionCenterDestination

  data class SkillDetail(
    val skillKey: String,
  ) : ExtensionCenterDestination

  data class PluginSettings(
    val returnDirectory: ExtensionCenterDirectory = ExtensionCenterDirectory.Plugins,
  ) : ExtensionCenterDestination

  data class BuiltInCapabilities(
    val directory: BuiltInCapabilityDirectory = BuiltInCapabilityDirectory.Plugins,
    val returnDirectory: ExtensionCenterDirectory = ExtensionCenterDirectory.Plugins,
  ) : ExtensionCenterDestination
}

internal fun ExtensionCenterDestination.parent(): ExtensionCenterDestination? =
  when (this) {
    is ExtensionCenterDestination.Directory -> null
    is ExtensionCenterDestination.PluginDetail ->
      when (origin) {
        PluginDetailOrigin.Directory -> ExtensionCenterDestination.Directory(returnDirectory)
        is PluginDetailOrigin.Category -> ExtensionCenterDestination.PluginCategory(origin.sectionId)
        PluginDetailOrigin.Settings -> ExtensionCenterDestination.PluginSettings(returnDirectory)
      }
    is ExtensionCenterDestination.PluginCategory ->
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Plugins)
    is ExtensionCenterDestination.SkillDetail ->
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Skills)
    is ExtensionCenterDestination.PluginSettings ->
      ExtensionCenterDestination.Directory(returnDirectory)
    is ExtensionCenterDestination.BuiltInCapabilities ->
      ExtensionCenterDestination.Directory(returnDirectory)
  }
