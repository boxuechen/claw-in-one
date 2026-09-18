package ai.openclaw.app.ui.vscreen

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.vscreen.AndroidVScreenSurfaceOwner
import ai.openclaw.app.vscreen.VScreenPointerEvent
import ai.openclaw.app.vscreen.VScreenPointerPhase
import ai.openclaw.app.vscreen.VScreenRuntime
import ai.openclaw.app.vscreen.VScreenState
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

internal data class VScreenActions(
  val surfaceOwner: (String) -> AndroidVScreenSurfaceOwner?,
  val sendPointer: (VScreenPointerEvent) -> Boolean,
  val onPlacementChanged: (VScreenPlacement) -> Unit,
  val onFullscreen: () -> Unit,
  val onMinimize: () -> Unit,
  val onRestoreFloating: () -> Unit,
  val onClose: () -> Unit,
)

/** Presentation-only shell for the one process-owned VScreen display. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VScreenScreen(
  state: VScreenState,
  placement: VScreenPlacement,
  actions: VScreenActions,
  modifier: Modifier = Modifier,
) {
  if (placement.mode == VScreenPresentation.Hidden) return
  VScreenHostSystemBars(fullscreen = placement.mode == VScreenPresentation.Fullscreen)
  BackHandler(enabled = placement.mode == VScreenPresentation.Fullscreen, onBack = actions.onMinimize)
  BoxWithConstraints(modifier.fillMaxSize().testTag("vscreen-layer")) {
    val density = LocalDensity.current
    val viewport = with(density) { VScreenViewport(maxWidth.toPx(), maxHeight.toPx()) }
    val floatingSize =
      vscreenFloatingSize(
        viewport,
        minimumWidth = with(density) { 108.dp.toPx() },
        maximumWidth = with(density) { 136.dp.toPx() },
      )
    val handleSize =
      VScreenSize(
        width = minOf(viewport.width, with(density) { 48.dp.toPx() }),
        height = minOf(viewport.height, with(density) { 72.dp.toPx() }),
      )
    val committed = vscreenBounds(viewport, floatingSize, placement, handleSize)
    var rawDrag by remember(placement.mode, placement.edge, placement.normalizedY) { mutableStateOf<VScreenPoint?>(null) }
    val dragged = rawDrag?.let { clampVScreenDrag(viewport, floatingSize, it) }
    val bounds =
      if (dragged != null && placement.mode == VScreenPresentation.Floating) {
        committed.copy(left = dragged.x, top = dragged.y)
      } else {
        committed
      }
    val minimized = placement.mode == VScreenPresentation.EdgeMinimized
    val shape =
      when (placement.mode) {
        VScreenPresentation.Hidden,
        VScreenPresentation.Fullscreen,
        -> RoundedCornerShape(0.dp)
        VScreenPresentation.Floating -> RoundedCornerShape(18.dp)
        VScreenPresentation.EdgeMinimized ->
          if (placement.edge == VScreenEdge.Left) {
            RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
          } else {
            RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
          }
      }
    val dragModifier =
      if (placement.mode == VScreenPresentation.Floating) {
        Modifier.pointerInput(viewport, floatingSize, placement) {
          detectDragGestures(
            onDragStart = { rawDrag = VScreenPoint(committed.left, committed.top) },
            onDragCancel = { rawDrag = null },
            onDragEnd = {
              rawDrag?.let {
                actions.onPlacementChanged(
                  settleVScreenDrag(viewport, floatingSize, it, with(density) { 32.dp.toPx() }),
                )
              }
              rawDrag = null
            },
            onDrag = { change, delta ->
              change.consume()
              val current = rawDrag ?: VScreenPoint(committed.left, committed.top)
              rawDrag = VScreenPoint(current.x + delta.x, current.y + delta.y)
            },
          )
        }
      } else {
        Modifier
      }
    val tapAction =
      when (placement.mode) {
        VScreenPresentation.Floating -> actions.onFullscreen
        VScreenPresentation.EdgeMinimized -> actions.onRestoreFloating
        VScreenPresentation.Hidden,
        VScreenPresentation.Fullscreen,
        -> null
      }
    val tapModifier =
      tapAction?.let {
        Modifier.clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = it,
        )
      } ?: Modifier
    val borderModifier =
      if (placement.mode == VScreenPresentation.Floating) {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), shape)
      } else {
        Modifier
      }

    Box(
      modifier =
        Modifier
          .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
          .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
          .clip(shape)
          .background(Color.Black)
          .then(borderModifier)
          .then(dragModifier)
          .then(tapModifier)
          .testTag(vscreenTag(placement.mode)),
    ) {
      if (!minimized) {
        VScreenVideo(
          state = state,
          fullscreen = placement.mode == VScreenPresentation.Fullscreen,
          surfaceOwner = actions.surfaceOwner,
          sendPointer = actions.sendPointer,
        )
        when (state.runtime) {
          VScreenRuntime.Starting,
          VScreenRuntime.Closing,
          VScreenRuntime.Reconnecting,
          -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
          VScreenRuntime.Unavailable ->
            Text(
              text = nativeString(state.message ?: "VScreen unavailable"),
              color = Color.White,
              style = ClawTheme.type.body,
              modifier = Modifier.align(Alignment.Center),
            )
          VScreenRuntime.Ready -> Unit
        }
        val chromeModifier =
          if (placement.mode == VScreenPresentation.Fullscreen) {
            Modifier
              .fillMaxSize()
              .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top))
          } else {
            Modifier.fillMaxSize()
          }
        Box(chromeModifier) {
          if (placement.mode == VScreenPresentation.Fullscreen) {
            Row(Modifier.align(Alignment.TopEnd)) {
              VScreenChromeButton(
                icon = {
                  Icon(
                    Icons.Default.PictureInPictureAlt,
                    contentDescription = nativeString("Minimize VScreen"),
                    modifier = Modifier.size(28.dp),
                  )
                },
                onClick = actions.onMinimize,
                modifier = Modifier.testTag("vscreen-minimize"),
              )
              VScreenChromeButton(
                icon = {
                  Icon(
                    Icons.Default.Close,
                    contentDescription = nativeString("Close VScreen"),
                    modifier = Modifier.size(28.dp),
                  )
                },
                onClick = actions.onClose,
                modifier = Modifier.testTag("vscreen-close"),
              )
            }
          } else {
            VScreenChromeButton(
              icon = {
                Icon(
                  Icons.Default.Close,
                  contentDescription = nativeString("Close VScreen"),
                  modifier = Modifier.size(28.dp),
                )
              },
              onClick = actions.onClose,
              modifier = Modifier.align(Alignment.TopEnd).testTag("vscreen-close"),
            )
          }
        }
      } else {
        Box(
          Modifier
            .align(if (placement.edge == VScreenEdge.Left) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            .size(width = 20.dp, height = 72.dp)
            .background(Color.White.copy(alpha = 0.82f)),
        )
      }
    }
  }
}

@Composable
private fun VScreenHostSystemBars(fullscreen: Boolean) {
  val view = LocalView.current
  DisposableEffect(view, fullscreen) {
    val window = view.context.findActivity()?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    if (fullscreen) {
      controller?.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      controller?.hide(WindowInsetsCompat.Type.systemBars())
    }
    onDispose {
      if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
    }
  }
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

@Composable
private fun VScreenVideo(
  state: VScreenState,
  fullscreen: Boolean,
  surfaceOwner: (String) -> AndroidVScreenSurfaceOwner?,
  sendPointer: (VScreenPointerEvent) -> Boolean,
) {
  val attachmentId = state.attachmentId ?: return
  val width = state.width ?: return
  val height = state.height ?: return
  val owner = surfaceOwner(attachmentId) ?: return
  var sequence by remember(attachmentId) { mutableLongStateOf(0L) }
  var activePointer by remember(attachmentId) { mutableLongStateOf(-1L) }

  fun emit(
    phase: VScreenPointerPhase,
    pointerId: Long,
    x: Float,
    y: Float,
    pressure: Float,
    visualWidth: Float,
    visualHeight: Float,
  ): Boolean {
    val normalized = normalizeVScreenPointer(x, y, visualWidth, visualHeight, state.rotation) ?: return false
    sequence += 1
    return sendPointer(
      VScreenPointerEvent(attachmentId, sequence, pointerId, phase, normalized.x, normalized.y, pressure.coerceIn(0f, 1f)),
    )
  }
  DisposableEffect(fullscreen, attachmentId) {
    onDispose {
      if (activePointer >= 0) {
        sequence += 1
        sendPointer(VScreenPointerEvent(attachmentId, sequence, activePointer, VScreenPointerPhase.Cancel, 0f, 0f, 0f))
        activePointer = -1
      }
    }
  }
  BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val fitted = vscreenSurfaceSize(width, height, state.rotation, DpSize(maxWidth, maxHeight))
    val rotated = state.rotation % 2 != 0
    val density = LocalDensity.current
    val visualWidthPx = with(density) { (if (rotated) fitted.height else fitted.width).toPx() }
    val visualHeightPx = with(density) { (if (rotated) fitted.width else fitted.height).toPx() }
    val input =
      if (fullscreen && state.runtime == VScreenRuntime.Ready && state.interactive) {
        Modifier.pointerInteropFilter { event ->
          val pointerId = event.getPointerId(event.actionIndex).toLong()
          when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
              activePointer = pointerId
              emit(VScreenPointerPhase.Down, pointerId, event.x, event.y, event.pressure, visualWidthPx, visualHeightPx)
            }
            MotionEvent.ACTION_MOVE ->
              emit(VScreenPointerPhase.Move, activePointer, event.x, event.y, event.pressure, visualWidthPx, visualHeightPx)
            MotionEvent.ACTION_UP -> {
              val accepted = emit(VScreenPointerPhase.Up, activePointer, event.x, event.y, event.pressure, visualWidthPx, visualHeightPx)
              activePointer = -1
              accepted
            }
            MotionEvent.ACTION_CANCEL -> {
              val accepted = emit(VScreenPointerPhase.Cancel, activePointer, event.x, event.y, 0f, visualWidthPx, visualHeightPx)
              activePointer = -1
              accepted
            }
            else -> false
          }
        }
      } else {
        Modifier
      }
    Box(
      Modifier
        .size(fitted)
        .graphicsLayer(rotationZ = -90f * state.rotation)
        .then(input),
    ) {
      VScreenSurface(attachmentId, owner, Modifier.fillMaxSize())
    }
  }
}

internal fun vscreenSurfaceSize(
  width: Int,
  height: Int,
  rotation: Int,
  viewport: DpSize,
): DpSize {
  val rotated = rotation % 2 != 0
  val visualWidth = if (rotated) height else width
  val visualHeight = if (rotated) width else height
  val scale = minOf(viewport.width.value / visualWidth, viewport.height.value / visualHeight)
  return DpSize((width * scale).dp, (height * scale).dp)
}

internal fun normalizeVScreenPointer(
  x: Float,
  y: Float,
  visualWidth: Float,
  visualHeight: Float,
  rotation: Int,
): VScreenPoint? {
  if (visualWidth <= 0f || visualHeight <= 0f || x !in 0f..visualWidth || y !in 0f..visualHeight) return null
  val nx = x / visualWidth
  val ny = y / visualHeight
  return when (rotation) {
    0 -> VScreenPoint(nx, ny)
    1 -> VScreenPoint(ny, 1f - nx)
    2 -> VScreenPoint(1f - nx, 1f - ny)
    3 -> VScreenPoint(1f - ny, nx)
    else -> null
  }
}

@Composable
private fun VScreenSurface(
  attachmentId: String,
  owner: AndroidVScreenSurfaceOwner,
  modifier: Modifier,
) {
  key(attachmentId, owner) {
    AndroidView(
      modifier = modifier,
      factory = { context ->
        TextureView(context).apply {
          isClickable = false
          isFocusable = false
          importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
          owner.bind(this)
        }
      },
    )
  }
}

@Composable
private fun VScreenChromeButton(
  icon: @Composable () -> Unit,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    onClick = onClick,
    modifier = modifier.size(48.dp),
    shape = CircleShape,
    color = Color.Transparent,
    contentColor = Color.White,
  ) {
    Box(contentAlignment = Alignment.Center) { icon() }
  }
}

private fun vscreenTag(mode: VScreenPresentation): String =
  when (mode) {
    VScreenPresentation.Hidden -> "vscreen-hidden"
    VScreenPresentation.Fullscreen -> "vscreen-fullscreen"
    VScreenPresentation.Floating -> "vscreen-floating"
    VScreenPresentation.EdgeMinimized -> "vscreen-edge-handle"
  }
