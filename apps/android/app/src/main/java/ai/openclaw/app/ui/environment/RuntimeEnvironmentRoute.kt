package ai.openclaw.app.ui.environment

import ai.openclaw.app.eligibility.DeviceEligibilityAction
import ai.openclaw.app.eligibility.launchDeviceEligibilityAction
import ai.openclaw.app.runtime.RuntimeAction
import ai.openclaw.app.runtime.RuntimeFeature
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Shared UI action owner for the global runtime gate, DevKit, Settings, and Terminal. */
internal class RuntimeEnvironmentRouteActions(
  val perform: (RuntimeAction) -> Boolean,
)

@Composable
internal fun rememberRuntimeEnvironmentRouteActions(feature: RuntimeFeature): RuntimeEnvironmentRouteActions {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  return remember(feature, context, scope) {
    RuntimeEnvironmentRouteActions { action ->
      when (action) {
        RuntimeAction.OpenDeviceInfo ->
          launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenDeviceInfo)
        RuntimeAction.OpenDeveloperSettings ->
          launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenDeveloperSettings)
        RuntimeAction.OpenSystemUpdate ->
          launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenSystemUpdate)
        RuntimeAction.OpenSystemTerminal ->
          launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenTerminal)
        else -> {
          scope.launch(Dispatchers.IO) { feature.perform(action) }
          true
        }
      }
    }
  }
}

/** Terminal, Settings, and DevKit use the same environment feature and action owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuntimeEnvironmentRoute(
  feature: RuntimeFeature,
  actions: RuntimeEnvironmentRouteActions,
  onClose: () -> Unit,
  sheet: Boolean = true,
  onBack: (() -> Unit)? = null,
  showClose: Boolean = true,
  additionalContent: @Composable ColumnScope.() -> Unit = {},
) {
  val state by feature.state.collectAsState()
  var launchFailed by remember { mutableStateOf(false) }
  val perform: (RuntimeAction) -> Unit = { action ->
    launchFailed = !actions.perform(action)
  }
  if (sheet) {
    ModalBottomSheet(
      onDismissRequest = onClose,
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
      containerColor = ClawTheme.colors.surface,
      contentColor = ClawTheme.colors.text,
      dragHandle = null,
    ) {
      RuntimeEnvironmentScreen(state, launchFailed, perform, onClose, modifier = Modifier.fillMaxHeight(0.88f), showClose = showClose, additionalContent = additionalContent)
    }
  } else {
    RuntimeEnvironmentScreen(state, launchFailed, perform, onClose, onBack = onBack, showClose = showClose, additionalContent = additionalContent)
  }
}
