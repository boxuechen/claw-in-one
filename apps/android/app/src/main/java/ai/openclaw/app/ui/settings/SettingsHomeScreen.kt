package ai.openclaw.app.ui.settings

import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.i18n.resolveNativeTextResource
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** Presentation-only settings home. Runtime state is adapted by [SettingsOverviewRoute]. */
@Composable
internal fun SettingsHomeScreen(
  state: SettingsOverviewUiState,
  onRouteChange: (SettingsDestination) -> Unit,
  onClose: () -> Unit,
) {
  ClawScaffold(
    contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp),
    // The containing bottom sheet already sits below the system status area.
    // Reapplying safeDrawing here creates a second, visibly oversized top inset.
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    containerColor = ClawTheme.colors.surface,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings-list"),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp),
      ) {
        item {
          Text(
            text = nativeString("Settings"),
            style = ClawTheme.type.display,
            color = ClawTheme.colors.text,
            modifier = Modifier.fillMaxWidth().padding(end = 56.dp, bottom = 8.dp),
          )
        }

        state.sections.forEach { section ->
          item {
            SettingsSectionTitle(section.title)
          }
          item {
            SettingsGroup(rows = section.rows, onOpen = onRouteChange)
          }
        }

        item {
          Column(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Text(text = state.versionLabel, style = ClawTheme.type.caption.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = ClawTheme.colors.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = state.statusLabel,
                style = ClawTheme.type.caption.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
                color = ClawTheme.colors.textSubtle,
              )
              if (state.statusNeedsAttention) {
                val attentionDescription = nativeString("Needs attention")
                Box(
                  modifier =
                    Modifier
                      .size(ClawTheme.sizes.statusDot)
                      .clip(CircleShape)
                      .background(ClawTheme.colors.warning)
                      .semantics { contentDescription = attentionDescription },
                )
              }
            }
          }
        }
      }

      ClawFloatingIconButton(
        icon = Icons.Default.Close,
        contentDescription = nativeString("Close"),
        onClick = onClose,
        modifier = Modifier.align(Alignment.TopEnd).testTag("settings-close").zIndex(1f),
      )
    }
  }
}

internal data class SettingsRow(
  val title: NativeText,
  val value: NativeText,
  val icon: ImageVector,
  val needsAttention: Boolean = false,
  val route: SettingsDestination? = null,
)

internal fun SettingsRow.showsAttentionIndicator(): Boolean = needsAttention

internal data class SettingsSection(
  val title: String,
  val rows: List<SettingsRow>,
)

internal data class SettingsHomeSectionLabels(
  val preferences: String = "Preferences",
  val product: String = "ClawInOne",
)

internal fun settingsHomeSections(
  rows: List<SettingsRow>,
  labels: SettingsHomeSectionLabels = SettingsHomeSectionLabels(),
): List<SettingsSection> =
  SettingsHomeSectionId.entries.mapNotNull { sectionId ->
    val sectionRows =
      rows.filter { row ->
        val route = row.route ?: return@filter false
        route in topLevelSettingsDestinations && settingsHomeSectionForRoute(route) == sectionId
      }
    if (sectionRows.isEmpty()) {
      null
    } else {
      SettingsSection(
        title =
          when (sectionId) {
            SettingsHomeSectionId.Preferences -> labels.preferences
            SettingsHomeSectionId.Product -> labels.product
          },
        rows = sectionRows,
      )
    }
  }

private enum class SettingsHomeSectionId {
  Preferences,
  Product,
}

private fun settingsHomeSectionForRoute(route: SettingsDestination): SettingsHomeSectionId? =
  when (route) {
    SettingsDestination.AiModels,
    SettingsDestination.Appearance,
    SettingsDestination.LocalEnvironment,
    -> SettingsHomeSectionId.Preferences

    SettingsDestination.About,
    -> SettingsHomeSectionId.Product

    SettingsDestination.Home,
    SettingsDestination.Licenses,
    -> null
  }

@Composable
internal fun SettingsSectionTitle(title: String) {
  Text(
    text = title,
    style = ClawTheme.type.section,
    color = ClawTheme.colors.textMuted,
  )
}

@Composable
internal fun SettingsGroup(
  rows: List<SettingsRow>,
  onOpen: (SettingsDestination) -> Unit,
  onAction: (() -> Unit)? = null,
) {
  ClawPanel(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
    Column {
      rows.forEachIndexed { index, row ->
        SettingsListRow(
          row = row,
          showDisclosure = row.route != null || onAction != null,
          onClick = {
            val rowRoute = row.route
            if (rowRoute == null) {
              onAction?.invoke()
            } else {
              onOpen(rowRoute)
            }
          },
        )
        if (index != rows.lastIndex) {
          HorizontalDivider(color = ClawTheme.colors.border.copy(alpha = 0.82f), thickness = 1.dp)
        }
      }
    }
  }
}

@Composable
private fun SettingsListRow(
  row: SettingsRow,
  showDisclosure: Boolean,
  onClick: () -> Unit,
) {
  val localizedTitle = row.title.resolveNativeTextResource()
  val localizedValue = row.value.resolveNativeTextResource()
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 54.dp)
        .clip(RoundedCornerShape(ClawTheme.radii.row))
        .clickable(onClick = onClick)
        .padding(horizontal = 0.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(imageVector = row.icon, contentDescription = null, modifier = Modifier.size(ClawTheme.sizes.standardIcon), tint = ClawTheme.colors.text)
    Text(text = localizedTitle, style = ClawTheme.type.body, color = ClawTheme.colors.text, modifier = Modifier.weight(1f), maxLines = 1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
      if (localizedValue.isNotBlank()) {
        Text(text = localizedValue, style = ClawTheme.type.caption.copy(fontSize = 13.sp, lineHeight = 17.sp), color = ClawTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (row.showsAttentionIndicator()) {
        val attentionDescription = nativeString("Needs attention")
        Box(
          modifier =
            Modifier
              .size(ClawTheme.sizes.statusDot)
              .clip(CircleShape)
              .background(ClawTheme.colors.warning)
              .semantics { contentDescription = attentionDescription },
        )
      }
      if (showDisclosure) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = settingsRowDisclosureDescription(localizedTitle, opensRoute = row.route != null),
          modifier = Modifier.size(17.dp),
          tint = ClawTheme.colors.text,
        )
      }
    }
  }
}

internal fun settingsRowDisclosureDescription(
  localizedTitle: String,
  opensRoute: Boolean,
): String = if (opensRoute) nativeString("Open \${row.title}", localizedTitle) else localizedTitle
