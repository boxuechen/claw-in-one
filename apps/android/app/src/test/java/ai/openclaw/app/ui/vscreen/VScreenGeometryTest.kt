package ai.openclaw.app.ui.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VScreenGeometryTest {
  @Test
  fun floatingWidthUsesThirtyPercentWithinThePhoneClamp() {
    assertEquals(108f, vscreenFloatingSize(viewport(320f, 800f), 108f, 136f).width, 0.001f)
    assertEquals(120f, vscreenFloatingSize(viewport(400f, 800f), 108f, 136f).width, 0.001f)
    assertEquals(136f, vscreenFloatingSize(viewport(800f, 800f), 108f, 136f).width, 0.001f)

    val landscape = vscreenFloatingSize(viewport(800f, 160f), 108f, 136f)
    assertTrue(landscape.height <= 160f)
    assertEquals(PHONE_PORTRAIT_ASPECT_RATIO, landscape.width / landscape.height, 0.001f)
  }

  @Test
  fun edgeAndNormalizedPositionSurviveViewportChanges() {
    val floating = VScreenSize(120f, 260f)
    val handle = VScreenSize(48f, 72f)
    val placement =
      VScreenPlacement(
        mode = VScreenPresentation.Floating,
        edge = VScreenEdge.Right,
        normalizedY = 0.25f,
      )
    val portrait = vscreenBounds(viewport(400f, 800f), floating, placement, handle)
    val withIme = vscreenBounds(viewport(400f, 480f), floating, placement, handle)

    assertEquals(280f, portrait.left, 0.001f)
    assertEquals((800f - 260f) * 0.25f, portrait.top, 0.001f)
    assertEquals(280f, withIme.left, 0.001f)
    assertEquals((480f - 260f) * 0.25f, withIme.top, 0.001f)
  }

  @Test
  fun dragClampsVisuallyAndSettlesToTheNearestEdge() {
    val viewport = viewport(400f, 800f)
    val floating = VScreenSize(120f, 260f)
    assertEquals(VScreenPoint(0f, 540f), clampVScreenDrag(viewport, floating, VScreenPoint(-20f, 900f)))

    val left = settleVScreenDrag(viewport, floating, VScreenPoint(80f, 270f), 32f)
    val right = settleVScreenDrag(viewport, floating, VScreenPoint(220f, 270f), 32f)
    assertEquals(VScreenEdge.Left, left.edge)
    assertEquals(VScreenEdge.Right, right.edge)
    assertEquals(VScreenPresentation.Floating, left.mode)
    assertEquals(0.5f, left.normalizedY, 0.001f)
  }

  @Test
  fun onlyAnOutwardPushPastTheAttachedEdgeMinimizes() {
    val viewport = viewport(400f, 800f)
    val floating = VScreenSize(120f, 260f)
    val left = settleVScreenDrag(viewport, floating, VScreenPoint(-32f, 0f), 32f)
    val right = settleVScreenDrag(viewport, floating, VScreenPoint(312f, 540f), 32f)
    val shortPush = settleVScreenDrag(viewport, floating, VScreenPoint(-31f, 0f), 32f)

    assertEquals(VScreenPlacement(VScreenPresentation.EdgeMinimized, VScreenEdge.Left, 0f), left)
    assertEquals(VScreenPlacement(VScreenPresentation.EdgeMinimized, VScreenEdge.Right, 1f), right)
    assertEquals(VScreenPresentation.Floating, shortPush.mode)
  }

  @Test
  fun fullscreenAndHandleUseTheSamePlacementCoordinateSystem() {
    val viewport = viewport(400f, 800f)
    val floating = VScreenSize(120f, 260f)
    val handle = VScreenSize(48f, 72f)
    val fullscreen =
      vscreenBounds(
        viewport,
        floating,
        VScreenPlacement(VScreenPresentation.Fullscreen, VScreenEdge.Left, 0.75f),
        handle,
      )
    val minimized =
      vscreenBounds(
        viewport,
        floating,
        VScreenPlacement(VScreenPresentation.EdgeMinimized, VScreenEdge.Right, 0.75f),
        handle,
      )

    assertEquals(VScreenBounds(0f, 0f, 400f, 800f), fullscreen)
    assertEquals(352f, minimized.left, 0.001f)
    assertEquals(48f, minimized.width, 0.001f)
    assertEquals((800f - 72f) * 0.75f, minimized.top, 0.001f)
  }

  @Test
  fun pointerCoordinatesRejectLetterboxAndMapRotation() {
    assertEquals(VScreenPoint(0.25f, 0.75f), normalizeVScreenPointer(25f, 75f, 100f, 100f, 0))
    assertEquals(VScreenPoint(0.75f, 0.75f), normalizeVScreenPointer(25f, 75f, 100f, 100f, 1))
    assertNull(normalizeVScreenPointer(-1f, 20f, 100f, 100f, 0))
  }

  private fun viewport(
    width: Float,
    height: Float,
  ) = VScreenViewport(width, height)
}
