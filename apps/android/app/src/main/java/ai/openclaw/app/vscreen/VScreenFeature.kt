package ai.openclaw.app.vscreen

import kotlinx.coroutines.flow.StateFlow

/** Native Route-facing state/actions for one App-global VScreen target. */
internal class VScreenFeature(
  val state: StateFlow<VScreenState>,
  val ensure: () -> Unit,
  val close: (onClosed: () -> Unit) -> Unit,
  val setPresentationVisible: (Boolean) -> Unit,
  val surfaceOwner: (attachmentId: String) -> AndroidVScreenSurfaceOwner?,
  val sendPointer: (VScreenPointerEvent) -> Boolean,
)
