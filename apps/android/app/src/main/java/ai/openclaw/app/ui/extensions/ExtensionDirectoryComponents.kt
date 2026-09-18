package ai.openclaw.app.ui.extensions

import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared quiet-directory primitives. Runtime-specific state and mutations stay in each domain. */
@Composable
internal fun ExtensionDirectorySearchField(
  value: String,
  onValueChange: (String) -> Unit,
  onClear: () -> Unit,
  placeholder: String,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val clearSearchLabel = stringResource(R.string.extension_clear_search)
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    modifier =
      modifier
        .fillMaxWidth()
        .height(48.dp)
        .padding(vertical = 4.dp)
        .clip(RoundedCornerShape(ClawTheme.radii.pill))
        .background(ClawTheme.colors.surface)
        .padding(horizontal = 14.dp),
    singleLine = true,
    textStyle = ClawTheme.type.body.copy(color = ClawTheme.colors.text),
    cursorBrush = SolidColor(ClawTheme.colors.primary),
    decorationBox = { innerTextField ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.Search,
          contentDescription = null,
          tint = ClawTheme.colors.textMuted,
          modifier = Modifier.size(ClawTheme.sizes.standardIcon),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
          if (value.isEmpty()) {
            Text(
              text = placeholder,
              style = ClawTheme.type.body,
              color = ClawTheme.colors.textMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          innerTextField()
        }
        if (value.isNotEmpty()) {
          Surface(
            onClick = onClear,
            modifier =
              Modifier
                .size(ClawTheme.sizes.minimumTouchTarget)
                .semantics { contentDescription = clearSearchLabel },
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = ClawTheme.colors.textMuted,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(ClawTheme.sizes.compactIcon),
              )
            }
          }
        }
      }
    },
  )
}

@Composable
internal fun ExtensionDirectoryState(
  title: String,
  message: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  warning: Boolean = false,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(vertical = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(text = title, style = ClawTheme.type.section, color = ClawTheme.colors.text)
    Text(
      text = message,
      style = ClawTheme.type.body,
      color = if (warning) ClawTheme.colors.warning else ClawTheme.colors.textMuted,
      textAlign = TextAlign.Center,
    )
    if (actionLabel != null && onAction != null) {
      ClawTextButton(text = actionLabel, onClick = onAction)
    }
  }
}

@Composable
internal fun ExtensionDirectoryLoading(message: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator(
      modifier = Modifier.size(18.dp),
      color = ClawTheme.colors.textMuted,
      strokeWidth = 2.dp,
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(text = message, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExtensionReviewSheet(
  title: String,
  intro: String,
  confirmLabel: String,
  confirmEnabled: Boolean = true,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
    dragHandle = { BottomSheetDefaults.DragHandle(color = ClawTheme.colors.textMuted) },
  ) {
    ExtensionReviewSheetContent(
      title = title,
      intro = intro,
      confirmLabel = confirmLabel,
      confirmEnabled = confirmEnabled,
      onConfirm = onConfirm,
      onDismiss = onDismiss,
      content = content,
    )
  }
}

@Composable
internal fun ExtensionReviewSheetContent(
  title: String,
  intro: String,
  confirmLabel: String,
  confirmEnabled: Boolean = true,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(text = title, style = ClawTheme.type.title, color = ClawTheme.colors.text)
    Text(text = intro, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    Column(
      modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      content()
    }
    ClawPrimaryButton(
      text = confirmLabel,
      onClick = onConfirm,
      enabled = confirmEnabled,
      modifier = Modifier.fillMaxWidth(),
    )
    ClawTextButton(
      text = nativeString("Cancel"),
      onClick = onDismiss,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
