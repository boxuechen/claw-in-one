package ai.openclaw.app.ui.eligibility

import ai.openclaw.app.eligibility.DeviceEligibilityAction
import ai.openclaw.app.eligibility.launchDeviceEligibilityAction
import ai.openclaw.app.entry.AppEntryFeature
import ai.openclaw.app.entry.AppEntryState
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun CompatibilityRoute(
  feature: AppEntryFeature,
  modifier: Modifier = Modifier,
) {
  val entry by feature.state.collectAsState()
  val compatibility = entry as? AppEntryState.Compatibility ?: return
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  LaunchedEffect(feature) { feature.refreshEligibility() }
  DisposableEffect(feature, lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) feature.refreshEligibility()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val state = compatibility.snapshot.toCompatibilityUiState()
  CompatibilityScreen(
    state = state,
    onAction = { action ->
      when (action) {
        CompatibilityAction.Refresh -> feature.refreshEligibility()
        CompatibilityAction.OpenSystemUpdate ->
          launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenSystemUpdate)
        CompatibilityAction.CopyDiagnostics -> {
          val clipboard = context.getSystemService(ClipboardManager::class.java)
          clipboard?.setPrimaryClip(ClipData.newPlainText("ClawInOne diagnostics", state.diagnostics))
        }
      }
    },
    modifier = modifier,
  )
}
