package ai.openclaw.app.ui.environment

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.runtime.LocalServiceState
import ai.openclaw.app.runtime.RuntimeAction
import ai.openclaw.app.runtime.RuntimeState
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

internal fun localServiceLabel(state: LocalServiceState): String =
  when (state) {
    LocalServiceState.Unchecked -> nativeString("Not checked")
    LocalServiceState.Checking -> nativeString("Checking…")
    LocalServiceState.Responding -> nativeString("Responding")
    LocalServiceState.LastKnown -> nativeString("Last known state")
    LocalServiceState.Unreachable -> nativeString("Unable to reach local service")
    LocalServiceState.NotPaired -> nativeString("Local service is not paired")
  }

internal fun environmentActionLabel(action: RuntimeAction): String =
  when (action) {
    RuntimeAction.Refresh -> nativeString("Check again")
    RuntimeAction.Reconnect -> nativeString("Reconnect")
    RuntimeAction.EnsureGateway -> nativeString("Start OpenClaw")
    RuntimeAction.RequestGatewayPairing -> nativeString("Pair this app again")
    RuntimeAction.RepairCapabilityPlan -> nativeString("Repair environment")
    RuntimeAction.RetryCapabilityPlan -> nativeString("Retry installation")
    RuntimeAction.SkipOptionalCapability -> nativeString("Continue without this capability")
    RuntimeAction.RefreshDeviceEligibility -> nativeString("Check again")
    RuntimeAction.OpenDeviceInfo -> nativeString("Open About phone")
    RuntimeAction.OpenDeveloperSettings -> nativeString("Open developer options")
    RuntimeAction.OpenSystemUpdate -> nativeString("Open system update")
    RuntimeAction.OpenSystemTerminal -> nativeString("Open system Terminal")
  }

/** Display only. Service operations and authoritative progress are supplied by the Route. */
@Composable
internal fun RuntimeEnvironmentScreen(
  state: RuntimeState,
  launchFailed: Boolean,
  onAction: (RuntimeAction) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  showClose: Boolean = true,
  additionalContent: @Composable ColumnScope.() -> Unit = {},
) {
  Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).testTag("runtime-environment")) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      onBack?.let { ClawFloatingIconButton(Icons.AutoMirrored.Filled.ArrowBack, nativeString("Back"), it) }
      Text(nativeString("Local environment"), style = ClawTheme.type.title, color = ClawTheme.colors.text, modifier = Modifier.weight(1f))
      if (showClose) {
        ClawFloatingIconButton(Icons.Default.Close, nativeString("Close"), onClose, modifier = Modifier.testTag("environment-close"))
      }
    }
    Column(
      Modifier
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(vertical = 20.dp)
        .testTag("environment-content"),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      EnvironmentStatusRow(nativeString("Local service"), nativeString(localServiceLabel(state.localService)))
      EnvironmentStatusRow(nativeString("OpenClaw"), nativeString(if (state.gatewayConnected) "Connected" else "Not connected"))
      if (!state.gatewayConnected) {
        Text(nativeString("A connection failure does not mean Linux has stopped. Check the local service or open system Terminal."), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
      }
      state.setup?.let { setup ->
        HorizontalDivider(color = ClawTheme.colors.border)
        Text(nativeString("Installation"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text(nativeString(environmentSetupLabel(setup.stage)), style = ClawTheme.type.body, color = ClawTheme.colors.text)
        setup.currentComponent?.let { EnvironmentStatusRow(nativeString("Current component"), nativeString(environmentComponentLabel(it))) }
        Text(setup.resolvedComponents.joinToString(" · ") { nativeString(environmentComponentLabel(it)) }, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        val total = setup.totalBytes
        val completed = setup.completedBytes
        if (total != null && completed != null && total > 0) {
          val fraction = (completed.toDouble() / total).toFloat().coerceIn(0f, 1f)
          LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
          Text("%.1f / %.1f MB · %d%%".format(completed / 1048576.0, total / 1048576.0, (fraction * 100).toInt()), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        }
        if (setup.exitCode != 0) Text(nativeString("Exit code") + ": ${setup.exitCode}", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }
      val primary =
        listOf(
          RuntimeAction.OpenDeviceInfo,
          RuntimeAction.OpenDeveloperSettings,
          RuntimeAction.OpenSystemUpdate,
          RuntimeAction.RefreshDeviceEligibility,
          RuntimeAction.RetryCapabilityPlan,
          RuntimeAction.RequestGatewayPairing,
          RuntimeAction.EnsureGateway,
          RuntimeAction.Reconnect,
        ).firstOrNull { it in state.actions }
      primary?.let { ClawPrimaryButton(nativeString(environmentActionLabel(it)), { onAction(it) }, modifier = Modifier.fillMaxWidth()) }
      listOf(
        RuntimeAction.RepairCapabilityPlan,
        RuntimeAction.SkipOptionalCapability,
        RuntimeAction.Refresh,
        RuntimeAction.OpenSystemTerminal,
      ).filter { it in state.actions }.forEach { action ->
        ClawSecondaryButton(nativeString(environmentActionLabel(action)), { onAction(action) }, modifier = Modifier.fillMaxWidth())
      }
      if (state.operationInProgress) Text(nativeString("Following the current operation…"), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      if (launchFailed) Text(nativeString("System Terminal could not be opened. Check that Linux is enabled in Developer options."), style = ClawTheme.type.body, color = ClawTheme.colors.danger)
      additionalContent()
      HorizontalDivider(color = ClawTheme.colors.border)
      Text(nativeString("Details"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
      Text("Supervisor · OpenClaw Gateway", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      state.gatewayVersion?.let { EnvironmentStatusRow(nativeString("Version"), it) }
      state.lastResponseEpochSeconds?.let { EnvironmentStatusRow(nativeString("Last response"), DateFormat.getTimeInstance().format(Date(it * 1_000))) }
    }
  }
}

@Composable
private fun EnvironmentStatusRow(
  title: String,
  value: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(nativeString(title), style = ClawTheme.type.section, color = ClawTheme.colors.text)
    Text(value, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
  }
}
