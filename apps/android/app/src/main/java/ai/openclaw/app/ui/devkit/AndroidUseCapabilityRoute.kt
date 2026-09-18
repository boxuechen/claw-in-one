package ai.openclaw.app.ui.devkit

import ai.openclaw.app.devkit.AndroidUseAuthorizationNotice
import ai.openclaw.app.devkit.AndroidUseAuthorizationStatus
import ai.openclaw.app.devkit.AndroidUseCapabilityFeature
import ai.openclaw.app.devkit.AndroidUseCapabilityState
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** User-controlled Android Use capability and its additional authorization. */
@Composable
internal fun AndroidUseCapabilityRoute(
  feature: AndroidUseCapabilityFeature,
  onBack: () -> Unit,
) {
  val state by feature.state.collectAsState()
  val lifecycleOwner = LocalLifecycleOwner.current

  LaunchedEffect(feature) { feature.actions.refreshAuthorization() }
  DisposableEffect(lifecycleOwner, feature) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) feature.actions.refreshAuthorization()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DevKitDetailFrame(
    title = nativeString("Android Use"),
    subtitle = nativeString("Ask OpenClaw to use Android capabilities."),
    onBack = onBack,
  ) {
    DevKitTogglePanel(
      rows =
        listOf(
          DevKitToggleRow(
            nativeString("Keep Awake"),
            nativeString("Keep the node available during active work."),
            Icons.Default.Bolt,
            state.preventSleep,
            feature.actions.setPreventSleep,
          ),
        ),
    )
    AndroidUseAuthorizationPanel(
      state = state,
      onAuthorize = feature.actions.authorizeCurrentPhone,
      onRefresh = feature.actions.refreshAuthorization,
      onDismissNotice = feature.actions.dismissNotice,
    )
    FlavorAndroidUseCapability(feature)
  }
}

@Composable
private fun AndroidUseAuthorizationPanel(
  state: AndroidUseCapabilityState,
  onAuthorize: () -> Unit,
  onRefresh: () -> Unit,
  onDismissNotice: () -> Unit,
) {
  if (!state.enabled) return
  val pending =
    state.authorization == AndroidUseAuthorizationStatus.ApprovalRequired ||
      state.authorization == AndroidUseAuthorizationStatus.ReapprovalRequired
  if (!pending && state.authorization != AndroidUseAuthorizationStatus.Unapproved && state.authorization != AndroidUseAuthorizationStatus.Unsupported && state.notice == null) return

  ClawPanel {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            nativeString(if (pending) "Authorize Android Use" else "Android Use needs attention"),
            style = ClawTheme.type.section,
            color = ClawTheme.colors.text,
          )
          Text(
            nativeString(
              when (state.authorization) {
                AndroidUseAuthorizationStatus.ApprovalRequired ->
                  "The local Gateway is waiting for this phone to approve its Android Use capability."
                AndroidUseAuthorizationStatus.ReapprovalRequired ->
                  "Android Use changed on this phone. Approve the updated capability before Chat can use it."
                AndroidUseAuthorizationStatus.Unapproved ->
                  "Reconnect this phone to create a fresh Android Use authorization request."
                AndroidUseAuthorizationStatus.Unsupported ->
                  "Repair or update the local OpenClaw environment, then check again."
                AndroidUseAuthorizationStatus.Checking,
                AndroidUseAuthorizationStatus.Approved,
                -> ""
              },
            ),
            style = ClawTheme.type.body,
            color = ClawTheme.colors.textMuted,
          )
        }
        if (state.authorizing) {
          CircularProgressIndicator(color = ClawTheme.colors.primary)
        }
      }
      if (pending) {
        ClawPrimaryButton(
          nativeString("Authorize this phone"),
          onAuthorize,
          Modifier.fillMaxWidth(),
          enabled = !state.authorizing,
        )
      } else {
        ClawSecondaryButton(
          nativeString("Check again"),
          onRefresh,
          Modifier.fillMaxWidth(),
          enabled = !state.authorizing,
        )
      }
      if (state.notice == AndroidUseAuthorizationNotice.ApprovalFailed) {
        Text(
          nativeString("Could not authorize Android Use. Check the local environment and try again."),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.danger,
        )
        TextButton(onClick = onDismissNotice) { Text(nativeString("Dismiss")) }
      }
    }
  }
}

internal fun androidUseCapabilityStatusText(state: AndroidUseCapabilityState) =
  nativeText(
    when {
      !state.enabled -> "Disabled"
      else ->
        when (state.authorization) {
          AndroidUseAuthorizationStatus.ApprovalRequired,
          AndroidUseAuthorizationStatus.ReapprovalRequired,
          AndroidUseAuthorizationStatus.Unapproved,
          -> "Needs authorization"
          AndroidUseAuthorizationStatus.Unsupported -> "Needs repair"
          AndroidUseAuthorizationStatus.Checking -> "Checking…"
          AndroidUseAuthorizationStatus.Approved,
          -> if (state.available) "Ready" else "Needs setup"
        }
    },
  )

internal fun AndroidUseCapabilityState.needsAuthorizationAttention(): Boolean =
  enabled &&
    (
      authorization == AndroidUseAuthorizationStatus.ApprovalRequired ||
        authorization == AndroidUseAuthorizationStatus.ReapprovalRequired ||
        authorization == AndroidUseAuthorizationStatus.Unapproved ||
        authorization == AndroidUseAuthorizationStatus.Unsupported
    )
