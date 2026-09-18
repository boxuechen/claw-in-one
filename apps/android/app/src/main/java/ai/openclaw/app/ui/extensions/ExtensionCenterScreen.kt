package ai.openclaw.app.ui.extensions

import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ExtensionCenterScreen(
  destination: ExtensionCenterDestination,
  onSelectDirectory: (ExtensionCenterDirectory) -> Unit,
  onOpenSettings: () -> Unit,
  onOpenBuiltInCapabilities: () -> Unit,
  onBack: () -> Unit,
  content: @Composable (PaddingValues) -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(ClawTheme.colors.canvas)
        .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    Box(
      modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
      ClawFloatingIconButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = nativeString("Back"),
        onClick = onBack,
        controlSize = 44.dp,
        iconSize = 24.dp,
        modifier = Modifier.align(Alignment.CenterStart),
      )

      when (destination) {
        is ExtensionCenterDestination.Directory -> {
          ExtensionDirectorySelector(
            selected = destination.directory,
            onSelect = onSelectDirectory,
            modifier = Modifier.align(Alignment.Center),
          )
          ExtensionCenterMoreMenu(
            onOpenSettings = onOpenSettings,
            onOpenBuiltInCapabilities = onOpenBuiltInCapabilities,
            modifier = Modifier.align(Alignment.CenterEnd),
          )
        }
        else ->
          Text(
            text = extensionCenterTitle(destination),
            style = ClawTheme.type.section,
            color = ClawTheme.colors.text,
            modifier = Modifier.align(Alignment.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
      }
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      content(PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp))
    }
  }
}

@Composable
private fun ExtensionCenterMoreMenu(
  onOpenSettings: () -> Unit,
  onOpenBuiltInCapabilities: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(modifier = modifier) {
    ClawFloatingIconButton(
      icon = Icons.Outlined.Settings,
      contentDescription = stringResource(R.string.extension_more_options),
      onClick = { expanded = true },
      controlSize = 44.dp,
      iconSize = 24.dp,
    )
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      containerColor = ClawTheme.colors.surfaceRaised,
    ) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.extension_built_in_capabilities)) },
        onClick = {
          expanded = false
          onOpenBuiltInCapabilities()
        },
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.extension_plugin_settings)) },
        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
        onClick = {
          expanded = false
          onOpenSettings()
        },
      )
    }
  }
}

@Composable
private fun ExtensionDirectorySelector(
  selected: ExtensionCenterDirectory,
  onSelect: (ExtensionCenterDirectory) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val label = extensionCenterDirectoryLabel(selected)
  val selectorDescription = stringResource(R.string.extension_choose_directory, label)
  Box(modifier = modifier) {
    Surface(
      onClick = { expanded = true },
      modifier =
        Modifier
          .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
          .semantics { contentDescription = selectorDescription },
      shape = RoundedCornerShape(ClawTheme.radii.pill),
      color = androidx.compose.ui.graphics.Color.Transparent,
      contentColor = ClawTheme.colors.text,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = label, style = ClawTheme.type.section)
        Icon(
          imageVector = Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          modifier = Modifier.padding(start = 4.dp).size(ClawTheme.sizes.compactIcon),
        )
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      containerColor = ClawTheme.colors.surfaceRaised,
    ) {
      ExtensionCenterDirectory.entries.forEach { directory ->
        DropdownMenuItem(
          text = {
            Text(
              text = extensionCenterDirectoryLabel(directory),
              style = ClawTheme.type.body,
              color = if (directory == selected) ClawTheme.colors.text else ClawTheme.colors.textMuted,
            )
          },
          onClick = {
            expanded = false
            if (directory != selected) onSelect(directory)
          },
        )
      }
    }
  }
}

@Composable
internal fun extensionCenterDirectoryLabel(directory: ExtensionCenterDirectory): String =
  when (directory) {
    ExtensionCenterDirectory.Plugins -> stringResource(R.string.extension_center_plugins)
    ExtensionCenterDirectory.Skills -> stringResource(R.string.extension_center_skills)
  }

@Composable
private fun extensionCenterTitle(destination: ExtensionCenterDestination): String =
  when (destination) {
    is ExtensionCenterDestination.Directory -> extensionCenterDirectoryLabel(destination.directory)
    is ExtensionCenterDestination.PluginDetail -> destination.displayName
    is ExtensionCenterDestination.PluginCategory ->
      when (val sectionId = destination.sectionId) {
        PluginDirectorySectionId.Recommended -> stringResource(R.string.extension_recommended_plugins)
        is PluginDirectorySectionId.Category -> sectionId.category
      }
    is ExtensionCenterDestination.SkillDetail -> nativeString("Skill")
    is ExtensionCenterDestination.PluginSettings -> stringResource(R.string.extension_center_plugins)
    is ExtensionCenterDestination.BuiltInCapabilities -> stringResource(R.string.extension_built_in_capabilities)
  }
