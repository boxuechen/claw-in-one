package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.CHAT_SESSION_LABEL_MAX_CHARS
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.takeUtf16Safe
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

internal data class ChatRenameDialogState(
  val sessionKey: String,
  val sessionId: String,
  val savedLabel: String?,
  val initialValue: String,
  val value: String,
  val submitting: Boolean = false,
  val errorMessage: String? = null,
) {
  val hasManualLabel get() = !savedLabel.isNullOrBlank()
  val canSave get() = value.trim().isNotEmpty() && value.trim() != initialValue.trim()
}

@Composable
internal fun ChatRenameDialog(
  state: ChatRenameDialogState,
  available: Boolean,
  onValueChange: (String) -> Unit,
  onSave: () -> Unit,
  onUseAutomaticName: () -> Unit,
  onDismiss: () -> Unit,
) {
  val focusRequester = remember { FocusRequester() }
  var fieldValue by
    remember(state.sessionKey, state.sessionId) {
      mutableStateOf(
        TextFieldValue(
          text = state.value,
          selection = TextRange(0, state.value.length),
        ),
      )
    }
  LaunchedEffect(state.value) {
    if (fieldValue.text != state.value) fieldValue = fieldValue.copy(text = state.value)
  }
  LaunchedEffect(state.sessionKey, state.sessionId) { focusRequester.requestFocus() }
  val saveEnabled = available && !state.submitting && state.canSave

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("chat-rename-dialog"),
    title = { Text(nativeString("Rename chat")) },
    text = {
      Column {
        OutlinedTextField(
          value = fieldValue,
          onValueChange = { next ->
            val bounded = next.text.takeUtf16Safe(CHAT_SESSION_LABEL_MAX_CHARS)
            val textChanged = bounded != fieldValue.text
            fieldValue = next.copy(text = bounded, selection = next.selection.coerceIn(0, bounded.length))
            if (textChanged) onValueChange(bounded)
          },
          enabled = available && !state.submitting,
          singleLine = true,
          label = { Text(nativeString("Chat name")) },
          isError = state.errorMessage != null,
          supportingText =
            state.errorMessage?.let { message ->
              {
                Text(
                  text = message,
                  color = ClawTheme.colors.warning,
                  modifier =
                    Modifier.semantics {
                      liveRegion = LiveRegionMode.Assertive
                      error(message)
                    },
                )
              }
            },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { if (saveEnabled) onSave() }),
          colors =
            TextFieldDefaults.colors(
              errorIndicatorColor = ClawTheme.colors.warning,
              errorLabelColor = ClawTheme.colors.warning,
              errorSupportingTextColor = ClawTheme.colors.warning,
            ),
          modifier =
            Modifier
              .padding(top = 4.dp)
              .focusRequester(focusRequester)
              .testTag("chat-rename-field"),
        )
        if (state.hasManualLabel) {
          TextButton(
            enabled = available && !state.submitting,
            onClick = onUseAutomaticName,
            modifier = Modifier.testTag("chat-use-automatic-name"),
          ) {
            Text(nativeString("Use automatic name"))
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = saveEnabled,
        onClick = onSave,
        modifier = Modifier.testTag("chat-rename-save"),
      ) {
        Text(nativeString(if (state.submitting) "Renaming…" else "Save"))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("chat-rename-cancel")) {
        Text(nativeString("Cancel"))
      }
    },
  )
}

private fun TextRange.coerceIn(
  minimum: Int,
  maximum: Int,
): TextRange = TextRange(start.coerceIn(minimum, maximum), end.coerceIn(minimum, maximum))
