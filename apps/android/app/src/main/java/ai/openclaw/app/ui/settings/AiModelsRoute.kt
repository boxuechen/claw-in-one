package ai.openclaw.app.ui.settings

import ai.openclaw.app.ai.AiSetupFeature
import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.ai.AiSetupRoute
import ai.openclaw.app.ui.design.ClawListItem
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class AiSettingsEditor { Access, DefaultModel }

/** Settings projects the shared AI setup controller; it owns navigation only. */
@Composable
internal fun AiModelsRoute(
  setup: AiSetupFeature?,
  onBack: () -> Unit,
  onEditorVisibilityChanged: (Boolean) -> Unit,
) {
  val state = setup?.state?.collectAsState()?.value ?: AiSetupState.Disconnected
  var editorName by rememberSaveable { mutableStateOf<String?>(null) }
  val editor = remember(editorName) { editorName?.let(AiSettingsEditor::valueOf) }

  LaunchedEffect(editor, state) {
    if (editor != null && state is AiSetupState.Ready) editorName = null
  }
  LaunchedEffect(editor) { onEditorVisibilityChanged(editor != null) }

  BackHandler(enabled = editor != null) {
    setup?.actions?.dismiss?.invoke()
    editorName = null
  }

  if (editor != null) {
    AiSetupRoute(
      feature = setup,
      onBack = {
        setup?.actions?.dismiss?.invoke()
        editorName = null
      },
    )
    return
  }

  SettingsDetailFrame(
    title = nativeString("AI and models"),
    subtitle = nativeString("Manage AI access and the default model."),
    onBack = onBack,
  ) {
    ClawPanel {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(nativeString("AI"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
        ClawListItem(
          title = nativeString("AI access"),
          subtitle = accessSummary(state),
          leading = { Icon(Icons.Default.Key, null, Modifier.size(18.dp)) },
          trailing = {
            ClawTextButton(nativeString("Manage"), {
              setup?.actions?.changeAccess?.invoke()
              editorName = AiSettingsEditor.Access.name
            })
          },
        )
        ClawListItem(
          title = nativeString("Default model"),
          subtitle = modelSummary(state),
          leading = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
          trailing = {
            ClawTextButton(nativeString("Change"), {
              setup?.actions?.changeModel?.invoke()
              editorName = AiSettingsEditor.DefaultModel.name
            })
          },
        )
      }
    }
  }
}

private fun accessSummary(state: AiSetupState): String =
  when (state) {
    is AiSetupState.Ready -> nativeString("Ready")
    is AiSetupState.Working -> nativeString("Checking configuration…")
    is AiSetupState.Incompatible -> nativeString("OpenClaw update required")
    AiSetupState.Disconnected -> nativeString("Gateway not connected")
    else -> nativeString("Needs attention")
  }

private fun modelSummary(state: AiSetupState): String =
  when (state) {
    is AiSetupState.Ready -> state.modelRef
    is AiSetupState.Working -> nativeString("Checking configuration…")
    else -> nativeString("Not verified")
  }
