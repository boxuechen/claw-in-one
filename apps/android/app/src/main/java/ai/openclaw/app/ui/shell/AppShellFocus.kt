package ai.openclaw.app.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Prevents a focused editor from asking the IME to reopen after an ordinary app return. */
@Composable
internal fun ReleaseInputFocusOnBackground() {
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  BackgroundInputFocusEffect {
    focusManager.clearFocus(force = true)
    keyboard?.hide()
  }
}

@Composable
internal fun BackgroundInputFocusEffect(onReleaseInputFocus: () -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentReleaseInputFocus by rememberUpdatedState(onReleaseInputFocus)

  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
          currentReleaseInputFocus()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
}
