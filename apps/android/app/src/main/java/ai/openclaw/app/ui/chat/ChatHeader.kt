package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatThinkingLevelOption
import ai.openclaw.app.currentAppLanguage
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawSegmentedControl
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Locale

internal data class ChatHeaderState(
  val showSidebarButton: Boolean,
  val sessionIdentity: String,
  val chatTitle: String,
  val projectName: String? = null,
  val renameEnabled: Boolean = false,
  val permission: ChatPermissionPickerState? = null,
  val modelLabel: String,
  val modelPickerEnabled: Boolean,
  val thinkingLevel: String,
  val thinkingOptions: List<ChatThinkingLevelOption>,
  val thinkingSupported: Boolean,
  val contextPercent: Int? = null,
  val refreshVisible: Boolean = false,
) {
  val optionsVisible: Boolean
    get() =
      modelPickerEnabled ||
        thinkingSupported ||
        contextPercent != null ||
        !projectName.isNullOrBlank() ||
        chatTitle.isNotBlank() ||
        permission != null ||
        refreshVisible
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHeader(
  state: ChatHeaderState,
  onOpenSidebar: () -> Unit,
  onOpenModelPicker: () -> Unit,
  onOpenPermissionPicker: () -> Unit,
  onOpenRenameDialog: () -> Unit,
  onThinkingLevelChange: (String) -> Unit,
  onRefresh: () -> Unit,
) {
  var optionsOpen by rememberSaveable(state.sessionIdentity) { mutableStateOf(false) }
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
        .zIndex(1f),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (state.showSidebarButton) {
      ClawFloatingIconButton(
        icon = Icons.Rounded.Menu,
        contentDescription = nativeString("Show Sidebar"),
        onClick = onOpenSidebar,
      )
    }
    Text(
      text = state.chatTitle,
      style = ClawTheme.type.section,
      color = ClawTheme.colors.text,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (state.optionsVisible) {
      IconButton(
        onClick = { optionsOpen = true },
        modifier =
          Modifier
            .size(ClawTheme.sizes.minimumTouchTarget)
            .semantics { contentDescription = nativeString("Chat options") },
      ) {
        Icon(
          imageVector = Icons.Rounded.MoreVert,
          contentDescription = null,
          modifier = Modifier.size(ClawTheme.sizes.standardIcon),
          tint = ClawTheme.colors.text,
        )
      }
    } else {
      Spacer(modifier = Modifier.size(ClawTheme.sizes.minimumTouchTarget))
    }
  }

  if (optionsOpen) {
    ModalBottomSheet(
      onDismissRequest = { optionsOpen = false },
      containerColor = ClawTheme.colors.surface,
      contentColor = ClawTheme.colors.text,
      dragHandle = null,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(nativeString("Chat options"), style = ClawTheme.type.section, modifier = Modifier.weight(1f))
          IconButton(
            onClick = { optionsOpen = false },
            modifier = Modifier.size(ClawTheme.sizes.minimumTouchTarget),
          ) {
            Icon(Icons.Default.Close, contentDescription = nativeString("Close"))
          }
        }

        if (state.modelPickerEnabled) {
          ChatOptionRow(
            label = nativeString("Model"),
            value = state.modelLabel,
            onClick = {
              optionsOpen = false
              onOpenModelPicker()
            },
          )
        }

        if (state.thinkingSupported) {
          Text(nativeString("Thinking"), style = ClawTheme.type.label, color = ClawTheme.colors.textMuted)
          ChatThinkingLevelSelector(
            options = state.thinkingOptions,
            selectedId = state.thinkingLevel,
            onSelect = onThinkingLevelChange,
          )
        }

        state.contextPercent?.let { percent ->
          ChatOptionRow(
            label = nativeString("Context"),
            value = nativeString("\${percent}% used", percent),
          )
        }

        if (
          !state.projectName.isNullOrBlank() ||
          state.chatTitle.isNotBlank() ||
          state.permission != null
        ) {
          HorizontalDivider(color = ClawTheme.colors.border)
          Text(nativeString("Chat details"), style = ClawTheme.type.label, color = ClawTheme.colors.textMuted)
          ChatOptionRow(
            label = nativeString("Chat name"),
            value = state.chatTitle,
            onClick =
              if (state.renameEnabled) {
                {
                  optionsOpen = false
                  onOpenRenameDialog()
                }
              } else {
                null
              },
            modifier = Modifier.testTag("chat-name-option"),
          )
          state.projectName?.takeIf(String::isNotBlank)?.let {
            ChatOptionRow(label = nativeString("Project"), value = it)
          }
          state.permission?.let { permission ->
            ChatOptionRow(
              label = nativeString("Access"),
              value =
                if (permission.applying) {
                  nativeString("Applying permissions…")
                } else {
                  chatPermissionModeLabel(permission.displayedMode)
                },
              onClick =
                if (permission.canOpen) {
                  {
                    optionsOpen = false
                    onOpenPermissionPicker()
                  }
                } else {
                  null
                },
              modifier = Modifier.testTag("chat-access-option"),
            )
          }
        }

        if (state.refreshVisible) {
          HorizontalDivider(color = ClawTheme.colors.border)
          ClawTextButton(
            text = nativeString("Refresh chat"),
            onClick = {
              optionsOpen = false
              onRefresh()
            },
            icon = Icons.Default.Refresh,
          )
        }
      }
    }
  }
}

@Composable
private fun ChatOptionRow(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  val content: @Composable () -> Unit = {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(label, style = ClawTheme.type.body, color = ClawTheme.colors.text, modifier = Modifier.weight(1f))
      Text(
        value,
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (onClick != null) {
        Icon(
          imageVector = Icons.Default.ExpandMore,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = ClawTheme.colors.textMuted,
        )
      }
    }
  }
  if (onClick == null) {
    Column(modifier = modifier) { content() }
  } else {
    Surface(
      onClick = onClick,
      modifier = modifier.fillMaxWidth().heightIn(min = ClawTheme.sizes.minimumTouchTarget),
      color = Color.Transparent,
      contentColor = ClawTheme.colors.text,
    ) {
      content()
    }
  }
}

@Composable
private fun ChatThinkingLevelSelector(
  options: List<ChatThinkingLevelOption>,
  selectedId: String,
  onSelect: (String) -> Unit,
) {
  val rows = remember(options) { chatThinkingOptionRows(options) }
  val normalizedSelected = selectedId.trim().lowercase(Locale.US)
  val languageTag = currentAppLanguage().languageTag
  val selectedLabel =
    options
      .firstOrNull { it.id.trim().lowercase(Locale.US) == normalizedSelected }
      ?.let { option -> chatThinkingOptionLabel(option, languageTag) }
      .orEmpty()
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    rows.forEach { row ->
      val labels = row.map { option -> chatThinkingOptionLabel(option, languageTag) }
      ClawSegmentedControl(
        options = labels,
        selected = selectedLabel,
        onSelect = { selected ->
          row.firstOrNull { option -> chatThinkingOptionLabel(option, languageTag) == selected }?.let { onSelect(it.id) }
        },
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}
