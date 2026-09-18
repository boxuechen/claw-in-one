package ai.openclaw.app.ui.terminal

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class TerminalAvailability { Ready, Preparing, Disconnected, AdminRequired, AuthorizationFailed }

@Composable
internal fun TerminalScreen(
  availability: TerminalAvailability,
  onBack: () -> Unit,
  onReconnect: () -> Unit,
  onOpenEnvironment: () -> Unit,
  terminal: @Composable () -> Unit,
) {
  ClawScaffold(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
    Column(Modifier.fillMaxSize().imePadding().testTag("terminal-screen")) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ClawFloatingIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativeString("Back"), onClick = onBack)
        Text(nativeString("Terminal"), style = ClawTheme.type.title, color = ClawTheme.colors.text, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        ClawFloatingIconButton(Icons.Outlined.Dns, nativeString("Local environment"), onOpenEnvironment, modifier = Modifier.testTag("terminal-environment"))
      }
      Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
        if (availability == TerminalAvailability.Ready) {
          terminal()
        } else {
          Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
              nativeString(
                when (availability) {
                  TerminalAvailability.Preparing -> "Preparing terminal…"
                  TerminalAvailability.AdminRequired -> "Full Access Required"
                  TerminalAvailability.AuthorizationFailed -> "Terminal authorization failed"
                  else -> "Connect OpenClaw to use the terminal"
                },
              ),
              style = ClawTheme.type.section,
              color = ClawTheme.colors.text,
            )
            Text(nativeString("Use the run environment to inspect the local service or open Android’s system Terminal."), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
            if (availability == TerminalAvailability.Disconnected || availability == TerminalAvailability.AuthorizationFailed) ClawPrimaryButton(text = nativeString("Reconnect"), onClick = onReconnect)
          }
        }
      }
    }
  }
}
