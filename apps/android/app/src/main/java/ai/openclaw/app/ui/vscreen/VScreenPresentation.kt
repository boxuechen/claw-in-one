package ai.openclaw.app.ui.vscreen

internal enum class VScreenPresentation {
  Hidden,
  Fullscreen,
  Floating,
  EdgeMinimized,
}

internal enum class VScreenEdge {
  Left,
  Right,
}

/** Presentation-only placement; target and workload identities remain in the domain feature. */
internal data class VScreenPlacement(
  val mode: VScreenPresentation = VScreenPresentation.Hidden,
  val edge: VScreenEdge = VScreenEdge.Right,
  val normalizedY: Float = 0.12f,
)
