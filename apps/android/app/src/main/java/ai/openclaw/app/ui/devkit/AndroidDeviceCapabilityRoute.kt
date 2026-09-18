package ai.openclaw.app.ui.devkit

import ai.openclaw.app.androiddevice.AndroidDeviceAvailability
import ai.openclaw.app.androiddevice.AndroidDeviceConnectionState
import ai.openclaw.app.androiddevice.AndroidDeviceOperation
import ai.openclaw.app.androiddevice.AndroidDeviceStatus
import ai.openclaw.app.devkit.AndroidDeviceCapabilityFeature
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.androiddevice.rememberAndroidDevicePairingUi
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun AndroidDeviceCapabilityRoute(
  feature: AndroidDeviceCapabilityFeature,
  onBack: () -> Unit,
) {
  val state by feature.state.collectAsState()
  val lifecycleOwner = LocalLifecycleOwner.current
  val pairingUi = rememberAndroidDevicePairingUi()

  LaunchedEffect(Unit) { feature.actions.refresh() }
  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) feature.actions.refresh()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  AndroidDeviceCapabilityScreen(
    state = state,
    pairingPromptIssue = pairingUi.issue,
    onOpenDeveloperOptions = pairingUi.openDeveloperOptions,
    onStartPairing = pairingUi.start,
    onOpenNotificationSettings = pairingUi.openNotificationSettings,
    onRefresh = feature.actions.refresh,
    onReconnect = feature.actions.reconnect,
    onForget = feature.actions.forget,
    onDismissNotice = feature.actions.dismissNotice,
    onBack = onBack,
  )
}

@Composable
internal fun AndroidDeviceCapabilityScreen(
  state: AndroidDeviceConnectionState,
  pairingPromptIssue: String?,
  onOpenDeveloperOptions: () -> Unit,
  onStartPairing: () -> Unit,
  onOpenNotificationSettings: () -> Unit,
  onRefresh: () -> Unit,
  onReconnect: (String?) -> Unit,
  onForget: () -> Unit,
  onDismissNotice: () -> Unit,
  onBack: () -> Unit,
) {
  var endpoint by remember { mutableStateOf("") }
  var showManualConnection by remember(state.snapshot?.status) { mutableStateOf(false) }
  var showWirelessHelp by remember { mutableStateOf(false) }
  var showForgetConfirmation by remember { mutableStateOf(false) }
  val busy = state.operation != null
  val snapshot = state.snapshot

  DevKitDetailFrame(
    title = nativeString("Android device connection"),
    subtitle = nativeString("Connect this phone to the local development runtime for app delivery and VScreen."),
    onBack = onBack,
  ) {
    ClawPanel {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(nativeString("Development connection"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
            Text(
              nativeString(androidDeviceStatusLabel(state)),
              style = ClawTheme.type.body,
              color = androidDeviceStatusColor(state),
              modifier = Modifier.testTag("deviceBridge-status"),
            )
          }
          if (state.refreshing || busy) {
            CircularProgressIndicator(modifier = Modifier.testTag("deviceBridge-progress"), color = ClawTheme.colors.primary)
          }
        }
        snapshot?.target?.let { target ->
          Text(
            "${target.model} · Android API ${target.androidApi}",
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.textMuted,
          )
        }
        state.notice?.let { notice ->
          Text(notice, style = ClawTheme.type.caption, color = ClawTheme.colors.danger)
          TextButton(onClick = onDismissNotice) { Text(nativeString("Dismiss")) }
        }
      }
    }

    when {
      state.availability == AndroidDeviceAvailability.GatewayOffline -> {
        GuidancePanel(
          title = nativeString("OpenClaw is offline"),
          body = nativeString("Restore the local engine connection, then check again."),
        )
        ClawPrimaryButton(nativeString("Check again"), onRefresh, Modifier.fillMaxWidth(), enabled = !busy)
      }
      state.availability == AndroidDeviceAvailability.Unsupported -> {
        GuidancePanel(
          title = nativeString("Development connection is unavailable"),
          body = nativeString("Update or repair the local OpenClaw installation to restore this product component."),
        )
        ClawPrimaryButton(nativeString("Check again"), onRefresh, Modifier.fillMaxWidth(), enabled = !busy)
      }
      snapshot?.status == AndroidDeviceStatus.Ready -> {
        GuidancePanel(
          title = nativeString("Ready"),
          body = nativeString("Pairing is saved. With Wireless debugging on, this phone reconnects automatically on an allowed network. New Chats do not need pairing again."),
        )
        TextButton(onClick = { showWirelessHelp = !showWirelessHelp }) {
          Text(nativeString(if (showWirelessHelp) "Hide wireless debugging help" else "Wireless debugging help"))
        }
        if (showWirelessHelp) {
          WirelessNetworkGuidance()
          ClawSecondaryButton(nativeString("Open Developer options"), onOpenDeveloperOptions, Modifier.fillMaxWidth(), enabled = !busy)
        }
        ClawSecondaryButton(
          nativeString("Forget development connection"),
          { showForgetConfirmation = true },
          Modifier.fillMaxWidth(),
          enabled = !busy,
        )
      }
      snapshot?.status == AndroidDeviceStatus.Offline || snapshot?.status == AndroidDeviceStatus.Connecting -> {
        GuidancePanel(
          title = nativeString("This phone is offline"),
          body = nativeString("Your pairing is saved. Turn on Wi-Fi and Wireless debugging; the saved connection can reconnect automatically. Try Reconnect before entering an address."),
        )
        ClawPrimaryButton(
          nativeString("Reconnect"),
          { onReconnect(null) },
          Modifier.fillMaxWidth(),
          enabled = !busy,
        )
        WirelessNetworkGuidance()
        ClawSecondaryButton(nativeString("Open Developer options"), onOpenDeveloperOptions, Modifier.fillMaxWidth(), enabled = !busy)
        TextButton(onClick = { showManualConnection = !showManualConnection }, enabled = !busy) {
          Text(nativeString(if (showManualConnection) "Hide manual connection" else "Enter address manually"))
        }
        if (showManualConnection) {
          Text(
            nativeString("Use the IP address and port on the Wireless debugging page, not the pairing-code dialog. No new pairing code is needed."),
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.textMuted,
          )
          ClawTextField(
            value = endpoint,
            onValueChange = { endpoint = it.take(300) },
            placeholder = nativeString("IP address:port"),
            label = nativeString("Device address"),
            enabled = !busy,
            modifier = Modifier.testTag("deviceBridge-connect-endpoint"),
          )
          ClawSecondaryButton(
            nativeString("Connect to address"),
            { onReconnect(endpoint.trim()) },
            Modifier.fillMaxWidth(),
            enabled = !busy && endpoint.isNotBlank(),
          )
        }
      }
      snapshot?.status == AndroidDeviceStatus.Unavailable -> {
        GuidancePanel(
          title = nativeString("Android device connection is unavailable"),
          body = nativeString("Repair the OpenClaw environment in DevKit, then check again."),
        )
        ClawPrimaryButton(nativeString("Check again"), onRefresh, Modifier.fillMaxWidth(), enabled = !busy)
      }
      else -> {
        WirelessNetworkGuidance()
        PairingGuidance()
        pairingPromptIssue?.let { issue ->
          GuidancePanel(title = nativeString("Notifications are unavailable"), body = issue)
          ClawSecondaryButton(
            nativeString("Open notification settings"),
            onOpenNotificationSettings,
            Modifier.fillMaxWidth(),
            enabled = !busy,
          )
        }
        ClawPrimaryButton(nativeString("Start pairing"), onStartPairing, Modifier.fillMaxWidth(), enabled = !busy)
      }
    }
  }

  if (showForgetConfirmation) {
    AlertDialog(
      onDismissRequest = { showForgetConfirmation = false },
      title = { Text(nativeString("Forget development connection?")) },
      text = { Text(nativeString("OpenClaw will delete its private ADB credential. Android will require a new pairing code next time.")) },
      confirmButton = {
        TextButton(
          onClick = {
            showForgetConfirmation = false
            onForget()
          },
        ) {
          Text(nativeString("Forget"))
        }
      },
      dismissButton = {
        TextButton(onClick = { showForgetConfirmation = false }) { Text(nativeString("Cancel")) }
      },
    )
  }
}

@Composable
private fun WirelessNetworkGuidance() {
  GuidancePanel(
    title = nativeString("Allow this network once"),
    body = nativeString("In Android's Wireless debugging prompt, select Always allow on this network, then Allow for a Wi-Fi network you trust. Android remembers that network; a different network may ask again. Changing ports does not require this permission again."),
  )
}

@Composable
private fun PairingGuidance() {
  GuidancePanel(
    title = nativeString("Pair this phone once"),
    body =
      nativeString(
        "1. Open Wireless debugging and tap Pair device with pairing code.\n2. Keep Android's pairing dialog open and pull down notifications.\n3. Reply to Pair this phone with the IPv4 address and code, separated by a space.",
      ),
  )
}

@Composable
private fun GuidancePanel(
  title: String,
  body: String,
) {
  ClawPanel {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
      Text(title, style = ClawTheme.type.section, color = ClawTheme.colors.text)
      Text(body, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
  }
}

internal fun androidDeviceStatusLabel(state: AndroidDeviceConnectionState): String =
  when (state.availability) {
    AndroidDeviceAvailability.GatewayOffline -> nativeString("OpenClaw offline")
    AndroidDeviceAvailability.Unsupported -> nativeString("Component unavailable")
    AndroidDeviceAvailability.Available ->
      when (state.operation) {
        AndroidDeviceOperation.Pairing -> nativeString("Pairing…")
        AndroidDeviceOperation.Connecting -> nativeString("Connecting…")
        AndroidDeviceOperation.VerifyingReconnect -> nativeString("Verifying reconnect…")
        AndroidDeviceOperation.Forgetting -> nativeString("Forgetting…")
        null ->
          when (state.snapshot?.status) {
            AndroidDeviceStatus.SetupRequired -> nativeString("Not paired")
            AndroidDeviceStatus.Pairing -> nativeString("Pairing…")
            AndroidDeviceStatus.Ready -> nativeString("Ready")
            AndroidDeviceStatus.Offline -> nativeString("Phone offline")
            AndroidDeviceStatus.Connecting -> nativeString("Connecting…")
            AndroidDeviceStatus.Forgetting -> nativeString("Forgetting…")
            AndroidDeviceStatus.Revoked -> nativeString("Not paired")
            AndroidDeviceStatus.Unavailable -> nativeString("Tools unavailable")
            null -> if (state.refreshing) nativeString("Checking…") else nativeString("Not checked")
          }
      }
  }

@Composable
private fun androidDeviceStatusColor(state: AndroidDeviceConnectionState) =
  when {
    state.snapshot?.status == AndroidDeviceStatus.Ready -> ClawTheme.colors.success
    state.availability == AndroidDeviceAvailability.Available && state.notice == null -> ClawTheme.colors.textMuted
    else -> ClawTheme.colors.warning
  }
