package ai.openclaw.app.ui.vscreen

import kotlin.math.max

internal data class VScreenViewport(
  val width: Float,
  val height: Float,
) {
  init {
    require(width >= 0f && height >= 0f)
  }
}

internal data class VScreenSize(
  val width: Float,
  val height: Float,
)

internal data class VScreenPoint(
  val x: Float,
  val y: Float,
)

internal data class VScreenBounds(
  val left: Float,
  val top: Float,
  val width: Float,
  val height: Float,
)

internal fun vscreenFloatingSize(
  viewport: VScreenViewport,
  minimumWidth: Float,
  maximumWidth: Float,
): VScreenSize {
  if (viewport.width == 0f || viewport.height == 0f) return VScreenSize(0f, 0f)
  val desiredWidth = (viewport.width * FLOATING_WIDTH_FRACTION).coerceIn(minimumWidth, maximumWidth)
  val desiredHeight = desiredWidth / PHONE_PORTRAIT_ASPECT_RATIO
  val height = minOf(desiredHeight, viewport.height)
  val width = minOf(desiredWidth, height * PHONE_PORTRAIT_ASPECT_RATIO)
  return VScreenSize(width, height)
}

internal fun vscreenBounds(
  viewport: VScreenViewport,
  floatingSize: VScreenSize,
  placement: VScreenPlacement,
  handleSize: VScreenSize,
): VScreenBounds {
  val size =
    when (placement.mode) {
      VScreenPresentation.Hidden -> VScreenSize(0f, 0f)
      VScreenPresentation.Floating -> floatingSize
      VScreenPresentation.EdgeMinimized -> handleSize
      VScreenPresentation.Fullscreen -> VScreenSize(viewport.width, viewport.height)
    }
  if (placement.mode == VScreenPresentation.Hidden) {
    return VScreenBounds(0f, 0f, 0f, 0f)
  }
  if (placement.mode == VScreenPresentation.Fullscreen) {
    return VScreenBounds(0f, 0f, size.width, size.height)
  }
  val maxLeft = max(0f, viewport.width - size.width)
  val maxTop = max(0f, viewport.height - size.height)
  return VScreenBounds(
    left = if (placement.edge == VScreenEdge.Left) 0f else maxLeft,
    top = maxTop * placement.normalizedY.coerceIn(0f, 1f),
    width = size.width,
    height = size.height,
  )
}

internal fun clampVScreenDrag(
  viewport: VScreenViewport,
  floatingSize: VScreenSize,
  raw: VScreenPoint,
): VScreenPoint =
  VScreenPoint(
    x = raw.x.coerceIn(0f, max(0f, viewport.width - floatingSize.width)),
    y = raw.y.coerceIn(0f, max(0f, viewport.height - floatingSize.height)),
  )

internal fun settleVScreenDrag(
  viewport: VScreenViewport,
  floatingSize: VScreenSize,
  raw: VScreenPoint,
  outwardMinimizeDistance: Float,
): VScreenPlacement {
  val clamped = clampVScreenDrag(viewport, floatingSize, raw)
  val maxTop = max(0f, viewport.height - floatingSize.height)
  val normalizedY = if (maxTop == 0f) 0f else clamped.y / maxTop
  val leftOvershoot = -raw.x
  val rightOvershoot = raw.x + floatingSize.width - viewport.width
  val edge =
    when {
      leftOvershoot > 0f -> VScreenEdge.Left
      rightOvershoot > 0f -> VScreenEdge.Right
      clamped.x + floatingSize.width / 2f <= viewport.width / 2f -> VScreenEdge.Left
      else -> VScreenEdge.Right
    }
  val outward =
    when (edge) {
      VScreenEdge.Left -> leftOvershoot
      VScreenEdge.Right -> rightOvershoot
    }
  return VScreenPlacement(
    mode =
      if (outward >= outwardMinimizeDistance) {
        VScreenPresentation.EdgeMinimized
      } else {
        VScreenPresentation.Floating
      },
    edge = edge,
    normalizedY = normalizedY.coerceIn(0f, 1f),
  )
}

internal const val PHONE_PORTRAIT_ASPECT_RATIO = 9f / 19.5f
private const val FLOATING_WIDTH_FRACTION = 0.3f
