package ai.openclaw.app.ui.vscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-shell presentation owner. It never owns the Android display target. */
internal class VScreenPresentationCoordinator(
  initial: VScreenPlacement = VScreenPlacement(),
) {
  private val mutablePlacement = MutableStateFlow(initial.sanitized())
  val placement = mutablePlacement.asStateFlow()

  fun update(value: VScreenPlacement) {
    mutablePlacement.value = value.sanitized()
  }

  fun openFullscreen() {
    mutablePlacement.value = mutablePlacement.value.copy(mode = VScreenPresentation.Fullscreen)
  }

  fun openFloating() {
    val current = mutablePlacement.value
    if (current.mode != VScreenPresentation.Fullscreen) {
      mutablePlacement.value = current.copy(mode = VScreenPresentation.Floating)
    }
  }

  fun minimize() {
    mutablePlacement.value = mutablePlacement.value.copy(mode = VScreenPresentation.Floating)
  }

  fun restoreFloating() = minimize()

  fun onTargetClosed() {
    mutablePlacement.value = mutablePlacement.value.copy(mode = VScreenPresentation.Hidden)
  }
}

private fun VScreenPlacement.sanitized() = copy(normalizedY = normalizedY.coerceIn(0f, 1f))
