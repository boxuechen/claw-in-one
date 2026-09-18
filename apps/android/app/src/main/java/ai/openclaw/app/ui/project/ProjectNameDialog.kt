package ai.openclaw.app.ui.project

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.project.ProjectNameDialogState
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.rememberSystemAnimationsEnabled
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun ProjectNameDialog(
  state: ProjectNameDialogState,
  onNameChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  onFinishSetup: () -> Unit,
) {
  when (state) {
    ProjectNameDialogState.Hidden -> Unit
    is ProjectNameDialogState.FinishSetup ->
      AlertDialog(
        onDismissRequest = {},
        title = { Text(nativeString("Project created")) },
        text = { Text(nativeString(state.message)) },
        confirmButton = {
          TextButton(onClick = onFinishSetup) { Text(nativeString("Finish setup")) }
        },
      )
    is ProjectNameDialogState.Editing ->
      EditableProjectNameDialog(state, onNameChange, onConfirm, onDismiss)
    is ProjectNameDialogState.Submitting ->
      ProjectNameDialogFrame(
        value = state.value,
        submitting = true,
        error = null,
        errorRevision = 0,
        onNameChange = {},
        onConfirm = {},
        onDismiss = {},
      )
  }
}

@Composable
private fun EditableProjectNameDialog(
  state: ProjectNameDialogState.Editing,
  onNameChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  ProjectNameDialogFrame(
    value = state.value,
    submitting = false,
    error = state.inlineError,
    errorRevision = state.errorRevision,
    onNameChange = onNameChange,
    onConfirm = onConfirm,
    onDismiss = onDismiss,
  )
}

@Composable
private fun ProjectNameDialogFrame(
  value: String,
  submitting: Boolean,
  error: String?,
  errorRevision: Long,
  onNameChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val focusRequester = remember { FocusRequester() }
  val animationsEnabled = rememberSystemAnimationsEnabled()
  val density = LocalDensity.current
  val shake = remember { Animatable(0f) }
  var selectAllOnFirstInteraction by remember { mutableStateOf(true) }
  var fieldValue by
    remember {
      mutableStateOf(
        TextFieldValue(
          text = value,
          selection = TextRange(0, value.length),
        ),
      )
    }
  LaunchedEffect(value) {
    if (fieldValue.text != value) fieldValue = fieldValue.copy(text = value)
  }
  LaunchedEffect(errorRevision, error) {
    if (errorRevision <= 0 || error == null) return@LaunchedEffect
    focusRequester.requestFocus()
    if (animationsEnabled) {
      for (target in listOf(-4f, 4f, -2f, 2f, 0f)) {
        shake.animateTo(target, animationSpec = tween(durationMillis = 36))
      }
    } else {
      shake.snapTo(0f)
    }
  }
  AlertDialog(
    onDismissRequest = { if (!submitting) onDismiss() },
    modifier = Modifier.testTag("project-name-dialog"),
    title = { Text(nativeString("Create project")) },
    text = {
      Column {
        OutlinedTextField(
          value = fieldValue,
          onValueChange = { next ->
            if (selectAllOnFirstInteraction && next.text == fieldValue.text) {
              fieldValue = next.copy(selection = TextRange(0, next.text.length))
              selectAllOnFirstInteraction = false
            } else {
              fieldValue = next
              selectAllOnFirstInteraction = false
              onNameChange(next.text)
            }
          },
          enabled = !submitting,
          singleLine = true,
          label = { Text(nativeString("Project name")) },
          isError = error != null,
          supportingText =
            error?.let { message ->
              {
                Text(
                  text = nativeString(message),
                  color = ClawTheme.colors.warning,
                  modifier =
                    Modifier.semantics {
                      liveRegion = LiveRegionMode.Assertive
                      this.error(message)
                    },
                )
              }
            },
          colors =
            TextFieldDefaults.colors(
              errorIndicatorColor = ClawTheme.colors.warning,
              errorLabelColor = ClawTheme.colors.warning,
              errorSupportingTextColor = ClawTheme.colors.warning,
            ),
          modifier =
            Modifier
              .padding(top = 4.dp)
              .offset { IntOffset(with(density) { shake.value.dp.roundToPx() }, 0) }
              .focusRequester(focusRequester)
              .testTag("project-name-field"),
        )
      }
    },
    confirmButton = {
      TextButton(
        enabled = !submitting && value.isNotBlank(),
        onClick = onConfirm,
      ) {
        Text(nativeString(if (submitting) "Creating…" else "Create"))
      }
    },
    dismissButton = {
      TextButton(enabled = !submitting, onClick = onDismiss) { Text(nativeString("Cancel")) }
    },
  )
}
