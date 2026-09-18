package ai.openclaw.app.ui.runtime

import ai.openclaw.app.runtime.RuntimeAction
import ai.openclaw.app.runtime.RuntimeFeature
import ai.openclaw.app.runtime.RuntimeGatePresentation
import ai.openclaw.app.ui.environment.rememberRuntimeEnvironmentRouteActions
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-level runtime owner. It remains composed independently from Chat navigation. */
@Composable
internal fun RuntimeGateRoute(
  feature: RuntimeFeature,
  prepareSupervisorRepairCommand: suspend () -> Boolean,
  modifier: Modifier = Modifier,
) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val state by feature.state.collectAsState()
  val actions = rememberRuntimeEnvironmentRouteActions(feature)
  val scope = rememberCoroutineScope()
  var repairPreparing by remember { mutableStateOf(false) }
  var repairFailed by remember { mutableStateOf(false) }
  LaunchedEffect(feature, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      withContext(Dispatchers.IO) { feature.observe() }
    }
  }

  val uiState = state.toRuntimeGateUiState(repairPreparing, repairFailed)
  val repairSupervisor: () -> Unit = {
    if (!repairPreparing) {
      repairPreparing = true
      repairFailed = false
      scope.launch {
        val copied =
          withContext(Dispatchers.IO) {
            runCatching { prepareSupervisorRepairCommand() }.getOrDefault(false)
          }
        repairPreparing = false
        repairFailed = !copied || !actions.perform(RuntimeAction.OpenSystemTerminal)
      }
    }
  }
  val onAction: (RuntimeGateUiAction) -> Unit = { action ->
    when (action) {
      RuntimeGateUiAction.Confirm -> feature.confirmReady()
      RuntimeGateUiAction.Refresh -> actions.perform(RuntimeAction.Refresh)
      RuntimeGateUiAction.Reconnect -> actions.perform(RuntimeAction.Reconnect)
      RuntimeGateUiAction.EnsureGateway -> actions.perform(RuntimeAction.EnsureGateway)
      RuntimeGateUiAction.RequestGatewayPairing -> actions.perform(RuntimeAction.RequestGatewayPairing)
      RuntimeGateUiAction.RefreshDeviceEligibility -> actions.perform(RuntimeAction.RefreshDeviceEligibility)
      RuntimeGateUiAction.OpenDeviceInfo -> actions.perform(RuntimeAction.OpenDeviceInfo)
      RuntimeGateUiAction.OpenDeveloperSettings -> actions.perform(RuntimeAction.OpenDeveloperSettings)
      RuntimeGateUiAction.OpenSystemUpdate -> actions.perform(RuntimeAction.OpenSystemUpdate)
      RuntimeGateUiAction.RepairSupervisor -> repairSupervisor()
      RuntimeGateUiAction.OpenSystemTerminal -> actions.perform(RuntimeAction.OpenSystemTerminal)
    }
  }

  when (uiState.presentation) {
    RuntimeGatePresentation.Hidden -> Unit
    RuntimeGatePresentation.TransientNotice -> RuntimeTransientNotice(uiState, modifier)
    RuntimeGatePresentation.Startup,
    RuntimeGatePresentation.Recovery,
    -> {
      BackHandler(enabled = true) {}
      RuntimeGateScreen(
        state = uiState,
        onAction = onAction,
        modifier = modifier,
      )
    }
  }
}
