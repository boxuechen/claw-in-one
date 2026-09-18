package ai.openclaw.app.ui.vscreen

import ai.openclaw.app.vscreen.VScreenFeature
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Adapts the process-owned VScreen feature to the App-global presentation shell. */
@Composable
internal fun VScreenRoute(
  feature: VScreenFeature?,
  coordinator: VScreenPresentationCoordinator,
  modifier: Modifier = Modifier,
) {
  if (feature == null) return
  key(feature) {
    val state by feature.state.collectAsState()
    val placement by coordinator.placement.collectAsState()
    val visible = placement.mode != VScreenPresentation.Hidden
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
      feature.setPresentationVisible(visible)
      if (visible) feature.ensure()
    }
    LaunchedEffect(placement.mode) {
      if (placement.mode == VScreenPresentation.Fullscreen) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
      }
    }
    AnimatedVisibility(
      visible = visible,
      enter = slideInVertically(initialOffsetY = { it }),
      exit = slideOutVertically(targetOffsetY = { it }),
      modifier = modifier.fillMaxSize(),
    ) {
      VScreenScreen(
        state = state,
        placement = placement,
        actions =
          VScreenActions(
            surfaceOwner = feature.surfaceOwner,
            sendPointer = feature.sendPointer,
            onPlacementChanged = coordinator::update,
            onFullscreen = coordinator::openFullscreen,
            onMinimize = coordinator::minimize,
            onRestoreFloating = coordinator::restoreFloating,
            onClose = { feature.close(coordinator::onTargetClosed) },
          ),
      )
    }
  }
}
