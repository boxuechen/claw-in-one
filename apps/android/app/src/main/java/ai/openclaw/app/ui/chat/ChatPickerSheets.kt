package ai.openclaw.app.ui.chat

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.ai.ModelUnavailableReason
import ai.openclaw.app.chat.providerQualifiedRef
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModelPickerSheet(
  sections: ChatModelPickerSections,
  favorites: Set<String>,
  onDismiss: () -> Unit,
  onSelect: (String?) -> Unit,
  onToggleFavorite: (String) -> Unit,
  onRecoverModelAccess: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
      contentPadding = PaddingValues(bottom = 24.dp),
    ) {
      item {
        Surface(
          onClick = { onSelect(null) },
          modifier = Modifier.fillMaxWidth().heightIn(min = ClawTheme.sizes.minimumTouchTarget),
          color = Color.Transparent,
          contentColor = ClawTheme.colors.text,
        ) {
          Text(
            text = nativeString("Default"),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            style = ClawTheme.type.body,
          )
        }
      }
      item {
        HorizontalDivider(color = ClawTheme.colors.border, thickness = 1.dp)
      }
      listOf(
        "Current" to listOfNotNull(sections.current),
        "Pinned" to sections.pinned,
        "Recent" to sections.recent,
        "Models" to sections.remaining,
      ).forEach { (title, models) ->
        if (models.isNotEmpty()) {
          item(key = "section-$title") {
            Text(
              text = title,
              modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 6.dp),
              style = ClawTheme.type.caption,
              color = ClawTheme.colors.textMuted,
            )
          }
          itemsIndexed(
            items = models,
            key = { _, model -> model.providerQualifiedRef() },
          ) { _, model ->
            val ref = model.providerQualifiedRef()
            ChatModelPickerRow(
              model = model,
              pinned = ref in favorites,
              onSelect = { onSelect(ref) },
              onToggleFavorite = { onToggleFavorite(ref) },
              onRecover = onRecoverModelAccess,
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatPermissionPickerSheet(
  state: ChatPermissionPickerState,
  onDismiss: () -> Unit,
  onSelect: (SessionPermissionMode) -> Unit,
  onRecover: () -> Unit,
) {
  val modes =
    listOf(
      SessionPermissionMode.Default,
      SessionPermissionMode.ReadOnly,
      SessionPermissionMode.Standard,
      SessionPermissionMode.Workspace,
      SessionPermissionMode.Full,
    )
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("chat-permission-picker-list"),
      contentPadding = PaddingValues(bottom = 24.dp),
    ) {
      item(key = "permission-heading") {
        Column(
          modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(text = nativeString("Chat permissions"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
          Text(
            text = nativeString("Choose once for this chat. The choice stays in effect for follow-up work until you change it."),
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.textMuted,
          )
        }
      }
      state.statusMessage?.let { message ->
        item(key = "permission-status") {
          Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            color = ClawTheme.colors.warningSoft,
            contentColor = ClawTheme.colors.text,
            shape = RoundedCornerShape(ClawTheme.radii.row),
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
              Text(text = message, style = ClawTheme.type.caption, color = ClawTheme.colors.text)
              state.recoveryLabel?.let { label ->
                ClawTextButton(text = label, onClick = onRecover)
              }
            }
          }
        }
      }
      itemsIndexed(
        items = modes,
        key = { _, mode -> mode.wire ?: "default" },
      ) { _, mode ->
        ChatPermissionPickerRow(
          mode = mode,
          selected = mode == state.displayedMode,
          applying = state.applying && mode == state.displayedMode,
          enabled = !state.applying && mode in state.enabledModes,
          requiresAdmin =
            mode == SessionPermissionMode.Full &&
              mode != state.displayedMode &&
              state.fullRequiresAdmin,
          onSelect = { onSelect(mode) },
        )
      }
    }
  }
}

@Composable
private fun ChatPermissionPickerRow(
  mode: SessionPermissionMode,
  selected: Boolean,
  applying: Boolean,
  enabled: Boolean,
  requiresAdmin: Boolean,
  onSelect: () -> Unit,
) {
  Surface(
    onClick = onSelect,
    enabled = enabled,
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 68.dp)
        .testTag("chat-permission-${mode.wire ?: "default"}"),
    color = if (selected) ClawTheme.colors.surfacePressed else Color.Transparent,
    contentColor = ClawTheme.colors.text,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = chatPermissionModeLabel(mode),
          style = ClawTheme.type.body,
          color = if (enabled || selected) ClawTheme.colors.text else ClawTheme.colors.textMuted,
        )
        Text(
          text = chatPermissionModeDescription(mode),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.textMuted,
        )
        if (requiresAdmin) {
          Text(
            text = nativeString("Requires administrator access."),
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.warning,
          )
        }
      }
      when {
        applying ->
          CircularProgressIndicator(
            modifier =
              Modifier
                .size(22.dp)
                .semantics { contentDescription = nativeString("Applying permissions…") },
            color = ClawTheme.colors.primary,
            strokeWidth = 2.dp,
          )
        selected ->
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = nativeString("Selected"),
            modifier = Modifier.size(22.dp),
            tint = ClawTheme.colors.primary,
          )
      }
    }
  }
}

internal fun chatPermissionModeLabel(mode: SessionPermissionMode): String =
  nativeString(
    when (mode) {
      SessionPermissionMode.Default -> "Default access"
      SessionPermissionMode.ReadOnly -> "Read-only access"
      SessionPermissionMode.Standard -> "Standard access"
      SessionPermissionMode.Workspace -> "Workspace access"
      SessionPermissionMode.Full -> "Full access"
    },
  )

private fun chatPermissionModeDescription(mode: SessionPermissionMode): String =
  nativeString(
    when (mode) {
      SessionPermissionMode.Default -> "Follow the agent's configured permission policy."
      SessionPermissionMode.ReadOnly -> "Read within the session root. Writing files and running commands are blocked."
      SessionPermissionMode.Standard -> "Read and edit within the session root. Requests beyond it require human review."
      SessionPermissionMode.Workspace -> "Read and edit within the session root. Requests beyond it require AI review."
      SessionPermissionMode.Full -> "Use files and commands without a reviewer. Android Use and system permissions remain separate."
    },
  )

@Composable
private fun ChatModelPickerRow(
  model: AiModel,
  pinned: Boolean,
  onSelect: () -> Unit,
  onToggleFavorite: () -> Unit,
  onRecover: () -> Unit,
) {
  val selectable = model.available != false
  Surface(
    onClick = { if (selectable) onSelect() },
    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
    color = Color.Transparent,
    contentColor = ClawTheme.colors.text,
  ) {
    Row(
      modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = model.name,
          style = ClawTheme.type.body,
          color = if (selectable) ClawTheme.colors.text else ClawTheme.colors.textMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(text = model.provider, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        model.unavailableReason?.let { reason ->
          Text(
            text = modelUnavailableReasonLabel(reason),
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.warning,
          )
        }
      }
      if (!selectable) {
        ClawTextButton(nativeString("Set up"), onRecover)
      }
      IconButton(onClick = onToggleFavorite) {
        Icon(
          imageVector = if (pinned) Icons.Default.Star else Icons.Default.StarBorder,
          contentDescription = if (pinned) nativeString("Unpin model") else nativeString("Pin model"),
          tint = if (pinned) ClawTheme.colors.primary else ClawTheme.colors.textMuted,
        )
      }
    }
  }
}

private fun modelUnavailableReasonLabel(reason: ModelUnavailableReason): String =
  nativeString(
    when (reason) {
      ModelUnavailableReason.MissingAuth -> "Sign-in required"
      ModelUnavailableReason.AuthFailed -> "Access needs attention"
      ModelUnavailableReason.Cooldown -> "Temporarily unavailable"
    },
  )
