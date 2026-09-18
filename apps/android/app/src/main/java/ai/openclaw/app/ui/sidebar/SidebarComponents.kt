package ai.openclaw.app.ui.sidebar

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SidebarSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  palette: SidebarPalette,
  modifier: Modifier = Modifier,
) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = modifier.fillMaxWidth().testTag("sidebar-search"),
    singleLine = true,
    label = { Text(nativeString("Search projects and chats")) },
    leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
    trailingIcon = {
      if (query.isNotEmpty()) {
        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(sidebarActionTouchTarget)) {
          Icon(imageVector = Icons.Default.Close, contentDescription = nativeString("Clear search"))
        }
      }
    },
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.text,
        unfocusedTextColor = palette.text,
        focusedContainerColor = palette.elevated,
        unfocusedContainerColor = palette.elevated,
        cursorColor = ClawTheme.colors.primary,
        focusedBorderColor = ClawTheme.colors.primary,
        unfocusedBorderColor = palette.hairline,
        focusedLabelColor = ClawTheme.colors.primary,
        unfocusedLabelColor = palette.muted,
        focusedLeadingIconColor = palette.text,
        unfocusedLeadingIconColor = palette.muted,
        focusedTrailingIconColor = palette.text,
        unfocusedTrailingIconColor = palette.muted,
      ),
  )
}

@Composable
internal fun SidebarSectionTitle(
  label: String,
  palette: SidebarPalette,
  modifier: Modifier = Modifier,
) {
  Text(
    text = label,
    style = ClawTheme.type.section,
    color = palette.text,
    modifier = modifier.semantics { heading() }.padding(vertical = 8.dp),
    maxLines = 1,
  )
}

@Composable
internal fun SidebarNavigationActionRow(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  palette: SidebarPalette,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = sidebarActionTouchTarget)
        .clip(RoundedCornerShape(10.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = palette.text,
      modifier = Modifier.size(ClawTheme.sizes.standardIcon),
    )
    Text(
      text = label,
      style = ClawTheme.type.body,
      color = palette.text,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
