package ai.openclaw.app.ui.settings

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawSeparatedColumn
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared settings detail shell with back navigation, title, subtitle, and section content.
 */
@Composable
internal fun SettingsDetailFrame(
  title: String,
  subtitle: String,
  onBack: () -> Unit,
  subtitleTextAlign: TextAlign = TextAlign.Start,
  content: @Composable () -> Unit,
) {
  ClawScaffold(
    contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp),
    // The Settings sheet already owns the top safe area. Details retain only
    // horizontal and bottom protection so fields still clear system UI and IME.
    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    containerColor = ClawTheme.colors.surface,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier.fillMaxWidth().testTag("settings-detail-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        ClawFloatingIconButton(
          icon = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = nativeString("Back"),
          onClick = onBack,
        )
        Text(
          text = title,
          style = ClawTheme.type.title,
          color = ClawTheme.colors.text,
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
      ) {
        if (subtitle.isNotBlank()) {
          item {
            Text(
              text = subtitle,
              style = ClawTheme.type.body,
              color = ClawTheme.colors.textMuted,
              modifier = Modifier.fillMaxWidth(),
              textAlign = subtitleTextAlign,
            )
          }
        }
        item {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
          }
        }
      }
    }
  }
}

/**
 * Toggle row model reused by settings sections that render simple on/off controls.
 */
internal data class SettingsToggleRow(
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val checked: Boolean,
  val onCheckedChange: (Boolean) -> Unit,
  val enabled: Boolean = true,
)

/**
 * Compact metric row model for connected gateway summaries.
 */
internal data class SettingsMetric(
  val title: String,
  val value: String,
  val copyable: Boolean = false,
)

@Composable
internal fun SettingsTogglePanel(rows: List<SettingsToggleRow>) {
  ClawPanel(contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
    ClawSeparatedColumn(items = rows) { row ->
      SettingsToggleListRow(row)
    }
  }
}

@Composable
internal fun SettingsToggleListRow(row: SettingsToggleRow) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .clickable(enabled = row.enabled) { row.onCheckedChange(!row.checked) }
        .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Icon(imageVector = row.icon, contentDescription = null, modifier = Modifier.size(ClawTheme.sizes.standardIcon), tint = ClawTheme.colors.text)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(text = row.title, style = ClawTheme.type.body, color = ClawTheme.colors.text, maxLines = 1)
      Text(text = row.subtitle, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Switch(checked = row.checked, onCheckedChange = row.onCheckedChange, enabled = row.enabled)
  }
}

/**
 * Reusable metric panel for settings screens with compact title/value rows.
 */
@Composable
internal fun SettingsMetricPanel(rows: List<SettingsMetric>) {
  ClawPanel(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
    ClawSeparatedColumn(items = rows) { row ->
      Row(modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 0.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = row.title,
          style = ClawTheme.type.body,
          color = ClawTheme.colors.text,
          modifier = Modifier.weight(0.9f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = row.value,
          style = ClawTheme.type.caption.copy(fontSize = 13.sp, lineHeight = 17.sp),
          color = ClawTheme.colors.textMuted,
          modifier = Modifier.weight(1.1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.End,
        )
      }
    }
  }
}

@Composable
internal fun SettingsIconMark(icon: ImageVector) {
  Surface(
    modifier = Modifier.size(30.dp),
    shape = CircleShape,
    color = ClawTheme.colors.surfaceRaised,
    border = BorderStroke(1.dp, ClawTheme.colors.border),
    contentColor = ClawTheme.colors.text,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(15.dp))
    }
  }
}
