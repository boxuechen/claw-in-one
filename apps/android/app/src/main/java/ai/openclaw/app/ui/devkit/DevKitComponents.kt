package ai.openclaw.app.ui.devkit

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawSeparatedColumn
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DevKitNavigationHeader(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  backModifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    ClawFloatingIconButton(
      icon = Icons.AutoMirrored.Filled.ArrowBack,
      contentDescription = nativeString("Back"),
      onClick = onBack,
      modifier = backModifier,
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
}

/** Full-screen detail frame owned by DevKit. */
@Composable
internal fun DevKitDetailFrame(
  title: String,
  subtitle: String,
  onBack: () -> Unit,
  content: @Composable () -> Unit,
) {
  ClawScaffold(
    contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp),
    containerColor = ClawTheme.colors.canvas,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      DevKitNavigationHeader(title = title, onBack = onBack)
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

internal data class DevKitToggleRow(
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val checked: Boolean,
  val onCheckedChange: (Boolean) -> Unit,
  val enabled: Boolean = true,
)

@Composable
internal fun DevKitTogglePanel(rows: List<DevKitToggleRow>) {
  ClawPanel(contentPadding = PaddingValues(0.dp)) {
    ClawSeparatedColumn(items = rows) { row ->
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
        Icon(
          imageVector = row.icon,
          contentDescription = null,
          modifier = Modifier.size(ClawTheme.sizes.standardIcon),
          tint = ClawTheme.colors.text,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
          Text(text = row.title, style = ClawTheme.type.body, color = ClawTheme.colors.text, maxLines = 1)
          Text(
            text = row.subtitle,
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Switch(checked = row.checked, onCheckedChange = row.onCheckedChange, enabled = row.enabled)
      }
    }
  }
}
