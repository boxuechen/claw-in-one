package ai.openclaw.app.ui.extensions

import ai.openclaw.app.mcp.ConnectionFeature
import ai.openclaw.app.plugin.PluginFeature
import ai.openclaw.app.plugin.catalog.PluginDirectoryFeature
import ai.openclaw.app.skill.SkillFeature
import ai.openclaw.app.skill.catalog.SkillDirectoryFeature
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Owns the native Plugins hub and delegates runtime adaptation to feature routes. */
@Composable
internal fun ExtensionCenterRoute(
  plugin: PluginFeature?,
  pluginDirectory: PluginDirectoryFeature,
  skill: SkillFeature?,
  skillDirectory: SkillDirectoryFeature,
  connections: ConnectionFeature?,
  destination: ExtensionCenterDestination,
  onDestinationChange: (ExtensionCenterDestination) -> Unit,
  onBack: () -> Unit,
) {
  BackHandler(onBack = onBack)
  ExtensionCenterScreen(
    destination = destination,
    onSelectDirectory = { directory ->
      onDestinationChange(ExtensionCenterDestination.Directory(directory))
    },
    onOpenSettings = {
      (destination as? ExtensionCenterDestination.Directory)?.let { directory ->
        onDestinationChange(ExtensionCenterDestination.PluginSettings(directory.directory))
      }
    },
    onOpenBuiltInCapabilities = {
      (destination as? ExtensionCenterDestination.Directory)?.let { directory ->
        onDestinationChange(
          ExtensionCenterDestination.BuiltInCapabilities(
            directory = directory.directory.toBuiltInCapabilityDirectory(),
            returnDirectory = directory.directory,
          ),
        )
      }
    },
    onBack = onBack,
  ) { contentPadding ->
    when (destination) {
      is ExtensionCenterDestination.SkillDetail ->
        SkillsHubRoute(
          skill = skill,
          directory = skillDirectory,
          destination = destination,
          contentPadding = contentPadding,
          onOpenSkill = {},
        )
      is ExtensionCenterDestination.PluginDetail ->
        PluginHubRoute(
          plugin = plugin,
          directory = pluginDirectory,
          connections = connections,
          destination = destination,
          contentPadding = contentPadding,
          onOpenPlugin = { _, _ -> },
          onOpenCategory = {},
        )
      is ExtensionCenterDestination.PluginCategory ->
        PluginHubRoute(
          plugin = plugin,
          directory = pluginDirectory,
          connections = connections,
          destination = destination,
          contentPadding = contentPadding,
          onOpenPlugin = { pluginId, displayName ->
            onDestinationChange(
              ExtensionCenterDestination.PluginDetail(
                pluginId = pluginId,
                displayName = displayName,
                origin = PluginDetailOrigin.Category(destination.sectionId),
              ),
            )
          },
          onOpenCategory = {},
        )
      is ExtensionCenterDestination.PluginSettings ->
        PluginHubRoute(
          plugin = plugin,
          directory = pluginDirectory,
          connections = connections,
          destination = destination,
          contentPadding = contentPadding,
          onOpenPlugin = { pluginId, displayName ->
            onDestinationChange(
              ExtensionCenterDestination.PluginDetail(
                pluginId = pluginId,
                displayName = displayName,
                origin = PluginDetailOrigin.Settings,
                returnDirectory = destination.returnDirectory,
              ),
            )
          },
          onOpenCategory = {},
        )
      is ExtensionCenterDestination.BuiltInCapabilities ->
        BuiltInCapabilitiesRoute(
          plugin = plugin,
          skill = skill,
          destination = destination,
          contentPadding = contentPadding,
          onSelectDirectory = { directory ->
            onDestinationChange(destination.copy(directory = directory))
          },
        )
      is ExtensionCenterDestination.Directory ->
        when (destination.directory) {
          ExtensionCenterDirectory.Skills ->
            SkillsHubRoute(
              skill = skill,
              directory = skillDirectory,
              destination = destination,
              contentPadding = contentPadding,
              onOpenSkill = { skillKey ->
                onDestinationChange(
                  ExtensionCenterDestination.SkillDetail(
                    skillKey = skillKey,
                  ),
                )
              },
            )
          ExtensionCenterDirectory.Plugins ->
            PluginHubRoute(
              plugin = plugin,
              directory = pluginDirectory,
              connections = connections,
              destination = destination,
              contentPadding = contentPadding,
              onOpenPlugin = { pluginId, displayName ->
                onDestinationChange(
                  ExtensionCenterDestination.PluginDetail(
                    pluginId = pluginId,
                    displayName = displayName,
                  ),
                )
              },
              onOpenCategory = { sectionId ->
                onDestinationChange(ExtensionCenterDestination.PluginCategory(sectionId))
              },
            )
        }
    }
  }
}
