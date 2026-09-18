package ai.openclaw.app.ui.vscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class VScreenPresentationCoordinatorTest {
  @Test
  fun presentationOwnerHidesOnlyAfterTargetClose() {
    val coordinator = VScreenPresentationCoordinator(VScreenPlacement(normalizedY = 2f))
    assertEquals(VScreenPresentation.Hidden, coordinator.placement.value.mode)
    assertEquals(1f, coordinator.placement.value.normalizedY)
    coordinator.openFloating()
    assertEquals(VScreenPresentation.Floating, coordinator.placement.value.mode)
    coordinator.openFullscreen()
    assertEquals(VScreenPresentation.Fullscreen, coordinator.placement.value.mode)
    coordinator.openFloating()
    assertEquals(VScreenPresentation.Fullscreen, coordinator.placement.value.mode)
    coordinator.minimize()
    assertEquals(VScreenPresentation.Floating, coordinator.placement.value.mode)
    coordinator.update(VScreenPlacement(VScreenPresentation.EdgeMinimized, VScreenEdge.Left, 0.4f))
    assertEquals(VScreenPresentation.EdgeMinimized, coordinator.placement.value.mode)
    coordinator.restoreFloating()
    assertEquals(VScreenPresentation.Floating, coordinator.placement.value.mode)
    coordinator.onTargetClosed()
    assertEquals(VScreenPresentation.Hidden, coordinator.placement.value.mode)
  }
}
