package ai.openclaw.app.ui.terminal

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.terminal.GatewayControlPage
import ai.openclaw.app.terminal.TerminalControlAccess
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.terminal.web.MobileTerminalWebView
import ai.openclaw.app.ui.terminal.web.TerminalBrowserPhase
import ai.openclaw.app.ui.terminal.web.TerminalViewRetention
import ai.openclaw.app.ui.web.ControlUiWebView
import android.content.ClipboardManager
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Owns only mobile presentation/input. No Gateway RPCs, process management, or terminal queue. */
@Composable
internal fun TerminalSurface(
  page: GatewayControlPage,
  bootstrapToken: String?,
  onBootstrapConsumed: (String) -> Unit,
  retention: TerminalViewRetention,
  renewAccess: suspend () -> TerminalControlAccess,
) {
  var view by remember { mutableStateOf<MobileTerminalWebView?>(null) }
  val phase = view?.phase?.collectAsState()?.value ?: TerminalBrowserPhase.Preparing
  val context = LocalContext.current
  var pendingPaste by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  var renewing by remember { mutableStateOf(false) }
  var renewalFailed by remember { mutableStateOf(false) }
  val nativeError = phase == TerminalBrowserPhase.AuthorizationRequired || phase == TerminalBrowserPhase.Unavailable
  Column(Modifier.fillMaxSize()) {
    if (phase in setOf(TerminalBrowserPhase.Exited, TerminalBrowserPhase.Replaced, TerminalBrowserPhase.Unavailable, TerminalBrowserPhase.AuthorizationRequired)) {
      Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(
          nativeString(
            when (phase) {
              TerminalBrowserPhase.Exited -> "This terminal session has ended."
              TerminalBrowserPhase.Replaced -> "The previous shell was lost. A new shell is ready; no commands were replayed."
              TerminalBrowserPhase.AuthorizationRequired -> "Terminal authorization expired. Reconnect to continue."
              else -> "This Gateway has not enabled terminal access."
            },
          ),
          color = ClawTheme.colors.textMuted,
          style = ClawTheme.type.body,
        )
        if (phase != TerminalBrowserPhase.Unavailable) {
          TextButton(enabled = !renewing, onClick = {
            when (phase) {
              TerminalBrowserPhase.Exited -> view?.newSession()
              TerminalBrowserPhase.AuthorizationRequired ->
                scope.launch {
                  renewing = true
                  renewalFailed = false
                  try {
                    view?.renewAccess(renewAccess())
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (_: Exception) {
                    renewalFailed = true
                  } finally {
                    renewing = false
                  }
                }
              else -> view?.acknowledgeReplacement()
            }
          }) {
            Text(
              nativeString(
                when (phase) {
                  TerminalBrowserPhase.Exited -> "Open new shell"
                  TerminalBrowserPhase.AuthorizationRequired -> "Reconnect"
                  else -> "Continue in new shell"
                },
              ),
            )
          }
        }
        if (renewalFailed) Text(nativeString("Terminal authorization failed"), color = ClawTheme.colors.danger, style = ClawTheme.type.caption)
      }
    }
    ControlUiWebView(
      page = page,
      url = terminalUrl(page.baseUrl),
      bootstrapToken = bootstrapToken,
      onBootstrapConsumed = onBootstrapConsumed,
      retention = retention.browser,
      viewFactory = { MobileTerminalWebView(it, retention, page) },
      onViewAvailable = {
        view = it as MobileTerminalWebView
        it.importantForAccessibility = if (nativeError) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
      },
      // Keep the browser alive for recovery, but do not show a contradictory upstream
      // "terminal unavailable" placeholder underneath our authorization/error state.
      modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (nativeError) 0f else 1f),
    )
    Row(
      Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .heightIn(min = 48.dp)
        .testTag("terminal-controls"),
    ) {
      TextButton(onClick = { view?.showKeyboard() }, enabled = phase == TerminalBrowserPhase.Live, modifier = Modifier.testTag("terminal-keyboard")) { Icon(Icons.Outlined.Keyboard, nativeString("Keyboard")) }
      listOf("Ctrl-C" to "\u0003", "Tab" to "\t", "Esc" to "\u001b", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "Ctrl-D" to "\u0004").forEach { (label, text) ->
        TextButton(onClick = { view?.sendInput(text) }, enabled = phase == TerminalBrowserPhase.Live) { Text(label) }
      }
      TextButton(onClick = { view?.copySelection() }) { Text(nativeString("Copy")) }
      TextButton(onClick = {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val text =
          clip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        if (text.isEmpty()) {
          Toast.makeText(context, nativeString("Clipboard is empty"), Toast.LENGTH_SHORT).show()
        } else if (terminalPasteNeedsConfirmation(text)) {
          pendingPaste = text
        } else {
          view?.paste(text)
        }
      }, enabled = phase == TerminalBrowserPhase.Live) { Text(nativeString("Paste")) }
    }
  }
  pendingPaste?.let { text ->
    AlertDialog(
      onDismissRequest = { pendingPaste = null },
      title = { Text(nativeString("Paste into terminal?")) },
      text = { Text(nativeString("This text contains line breaks or control characters and may execute commands.") + "\n\n" + text.take(240)) },
      confirmButton = {
        TextButton(onClick = {
          view?.paste(text)
          pendingPaste = null
        }) { Text(nativeString("Paste")) }
      },
      dismissButton = { TextButton(onClick = { pendingPaste = null }) { Text(nativeString("Cancel")) } },
    )
  }
}

internal fun terminalPasteNeedsConfirmation(text: String): Boolean = text.any { it.code < 32 || it.code == 127 }
