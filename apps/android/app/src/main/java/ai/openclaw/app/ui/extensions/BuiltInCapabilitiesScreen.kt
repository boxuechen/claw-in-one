package ai.openclaw.app.ui.extensions

import ai.openclaw.app.R
import ai.openclaw.app.ui.design.ClawDetailRow
import ai.openclaw.app.ui.design.ClawListPanel
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawSegmentedControl
import ai.openclaw.app.ui.design.ClawTextBadge
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun BuiltInCapabilitiesScreen(
  selectedDirectory: BuiltInCapabilityDirectory,
  state: BuiltInCapabilitiesUiState,
  contentPadding: PaddingValues,
  onSelectDirectory: (BuiltInCapabilityDirectory) -> Unit,
  onRefresh: () -> Unit,
  onSetPluginEnabled: (PluginCatalogItem, Boolean) -> Unit,
  onSetSkillEnabled: (String, Boolean) -> Unit,
  onRefreshPluginMutation: () -> Unit,
  onDismissPluginMutation: () -> Unit,
) {
  var query by rememberSaveable { mutableStateOf("") }
  val plugins = remember(state.plugins, query) { state.plugins.filterPluginsByQuery(query) }
  val skills = remember(state.skills, query) { state.skills.filterSkillsByQuery(query) }
  val pluginLabel = stringResource(R.string.extension_center_plugins)
  val skillLabel = stringResource(R.string.extension_center_skills)
  val selectedLabel =
    when (selectedDirectory) {
      BuiltInCapabilityDirectory.Plugins -> pluginLabel
      BuiltInCapabilityDirectory.Skills -> skillLabel
    }

  LazyColumn(
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    item {
      ClawSegmentedControl(
        options = listOf(pluginLabel, skillLabel),
        selected = selectedLabel,
        onSelect = { selected ->
          onSelectDirectory(
            if (selected == pluginLabel) {
              BuiltInCapabilityDirectory.Plugins
            } else {
              BuiltInCapabilityDirectory.Skills
            },
          )
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    item {
      val searchLabel = stringResource(R.string.extension_search_built_in_capabilities)
      ExtensionDirectorySearchField(
        value = query,
        onValueChange = { query = it },
        onClear = { query = "" },
        placeholder = searchLabel,
        enabled = true,
        modifier = Modifier.semantics { contentDescription = searchLabel },
      )
    }

    state.pluginLifecycle.notice?.let {
      item {
        PluginLifecycleNoticePanel(
          lifecycle = state.pluginLifecycle,
          onRefresh = onRefreshPluginMutation,
          onDismiss = onDismissPluginMutation,
        )
      }
    }

    if (!state.connected) {
      item {
        ExtensionDirectoryState(
          title = stringResource(R.string.extension_built_in_offline_title),
          message = stringResource(R.string.extension_built_in_offline_message),
          actionLabel = stringResource(R.string.extension_try_again),
          onAction = onRefresh,
        )
      }
      return@LazyColumn
    }

    when (selectedDirectory) {
      BuiltInCapabilityDirectory.Plugins -> {
        state.pluginErrorText?.let { message -> item { BuiltInInlineNotice(message) } }
        when {
          state.refreshing && state.plugins.isEmpty() ->
            item { ExtensionDirectoryLoading(stringResource(R.string.extension_loading_built_in_capabilities)) }
          !state.pluginInventoryAvailable ->
            item {
              ExtensionDirectoryState(
                title = stringResource(R.string.extension_plugins_unavailable_title),
                message = stringResource(R.string.extension_update_manage_plugins),
                actionLabel = stringResource(R.string.extension_refresh),
                onAction = onRefresh,
                warning = true,
              )
            }
          plugins.isEmpty() ->
            item {
              BuiltInEmptyState(
                if (query.isBlank()) {
                  stringResource(R.string.extension_no_built_in_plugins)
                } else {
                  stringResource(R.string.extension_no_built_in_matches)
                },
              )
            }
          else ->
            item {
              BuiltInPluginList(
                plugins = plugins,
                lifecycle = state.pluginLifecycle,
                canSetEnabled = state.canSetPluginEnabled,
                onSetEnabled = onSetPluginEnabled,
              )
            }
        }
      }
      BuiltInCapabilityDirectory.Skills -> {
        state.skillErrorText?.let { message -> item { BuiltInInlineNotice(message) } }
        when {
          state.refreshing && state.skills.isEmpty() ->
            item { ExtensionDirectoryLoading(stringResource(R.string.extension_loading_built_in_capabilities)) }
          skills.isEmpty() ->
            item {
              BuiltInEmptyState(
                if (query.isBlank()) {
                  stringResource(R.string.extension_no_built_in_skills)
                } else {
                  stringResource(R.string.extension_no_built_in_matches)
                },
              )
            }
          else ->
            item {
              BuiltInSkillList(
                skills = skills,
                mutatingSkillKeys = state.mutatingSkillKeys,
                canSetEnabled = state.canSetSkillEnabled,
                onSetEnabled = onSetSkillEnabled,
              )
            }
        }
      }
    }
  }
}

@Composable
private fun BuiltInPluginList(
  plugins: List<PluginCatalogItem>,
  lifecycle: PluginLifecyclePresentation,
  canSetEnabled: Boolean,
  onSetEnabled: (PluginCatalogItem, Boolean) -> Unit,
) {
  ClawListPanel(items = plugins) { plugin ->
    val busy = lifecycle.busyIdentity == plugin.pluginIdentity()
    val toggleDescription = stringResource(R.string.extension_toggle_built_in_plugin, plugin.displayName)
    ClawDetailRow(
      title = plugin.displayName,
      subtitle = plugin.summary ?: stringResource(R.string.extension_built_in_plugin_description),
      leading = { ClawTextBadge(pluginBadge(plugin.displayName)) },
      trailing = {
        if (busy) {
          Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = ClawTheme.colors.textMuted,
              strokeWidth = 2.dp,
            )
          }
        } else {
          Switch(
            checked = plugin.enabled,
            onCheckedChange = { enabled -> onSetEnabled(plugin, enabled) },
            enabled = canSetEnabled && lifecycle.busyIdentity == null,
            modifier = Modifier.semantics { contentDescription = toggleDescription },
          )
        }
      },
    )
  }
}

@Composable
private fun BuiltInSkillList(
  skills: List<InstalledSkillItem>,
  mutatingSkillKeys: Set<String>,
  canSetEnabled: Boolean,
  onSetEnabled: (String, Boolean) -> Unit,
) {
  ClawListPanel(items = skills) { skill ->
    val busy = skill.skillKey in mutatingSkillKeys
    val toggleDescription = stringResource(R.string.extension_toggle_built_in_skill, skill.displayName)
    ClawDetailRow(
      title = skill.displayName,
      subtitle = skill.summary ?: stringResource(R.string.extension_built_in_skill_description),
      leading = { ClawTextBadge(skill.badge) },
      trailing = {
        if (busy) {
          Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = ClawTheme.colors.textMuted,
              strokeWidth = 2.dp,
            )
          }
        } else {
          Switch(
            checked = skill.status != InstalledSkillStatus.Disabled,
            onCheckedChange = { enabled -> onSetEnabled(skill.skillKey, enabled) },
            enabled = canSetEnabled,
            modifier = Modifier.semantics { contentDescription = toggleDescription },
          )
        }
      },
    )
  }
}

@Composable
private fun BuiltInInlineNotice(message: String) {
  ClawPanel { Text(text = message, style = ClawTheme.type.body, color = ClawTheme.colors.warning) }
}

@Composable
private fun BuiltInEmptyState(message: String) {
  ClawPanel {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(text = message, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
  }
}

private fun List<PluginCatalogItem>.filterPluginsByQuery(query: String): List<PluginCatalogItem> {
  val normalized = query.trim()
  if (normalized.isEmpty()) return this
  return filter { plugin ->
    plugin.displayName.contains(normalized, ignoreCase = true) ||
      plugin.summary?.contains(normalized, ignoreCase = true) == true
  }
}

private fun List<InstalledSkillItem>.filterSkillsByQuery(query: String): List<InstalledSkillItem> {
  val normalized = query.trim()
  if (normalized.isEmpty()) return this
  return filter { skill ->
    skill.displayName.contains(normalized, ignoreCase = true) ||
      skill.summary?.contains(normalized, ignoreCase = true) == true
  }
}
