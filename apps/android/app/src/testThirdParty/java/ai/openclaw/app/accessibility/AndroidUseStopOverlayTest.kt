package ai.openclaw.app.accessibility

import ai.openclaw.app.androiduse.AndroidUseControlState
import ai.openclaw.app.androiduse.AndroidUseExecutionIdentity
import ai.openclaw.app.androiduse.AndroidUseLeaseController
import ai.openclaw.app.androiduse.AndroidUseLeaseDecision
import ai.openclaw.app.androiduse.AndroidUseLeaseIdentity
import ai.openclaw.app.androiduse.AndroidUseRevocation
import ai.openclaw.app.gateway.GatewaySession
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUseStopOverlayTest {
  @Test
  fun mainDisplayDozingRevokesEvenWhileAnotherDisplayIsOnAndWakeDoesNotResume() =
    runTest {
      val service = Robolectric.buildService(OpenClawAccessibilityService::class.java).create().get()
      val manager = service.getSystemService(DisplayManager::class.java)
      val primary = manager.getDisplay(Display.DEFAULT_DISPLAY)
      shadowOf(primary).setState(Display.STATE_ON)
      val secondary = manager.getDisplay(ShadowDisplayManager.addDisplay("w720dp-h1560dp"))
      shadowOf(secondary).setState(Display.STATE_ON)
      val controls = AndroidUseLeaseController(backgroundScope, consentGranted = { true })
      val overlay = AndroidUseStopOverlay(service, controls, backgroundScope, service)
      val node = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") }
      val identity = AndroidUseLeaseIdentity("control", "owner", "demo.app", AndroidUseExecutionIdentity("chat", "run", "generation"))
      try {
        assertTrue(overlay.attach())
        assertTrue(controls.acquire(node, null, identity, "demo.app") is AndroidUseLeaseDecision.Allowed)
        shadowOf(primary).setState(Display.STATE_DOZE)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Display.STATE_ON, secondary.state)
        assertEquals(AndroidUseControlState.Inactive(AndroidUseRevocation.StopSurfaceUnavailable), controls.state.value)
        shadowOf(primary).setState(Display.STATE_ON)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controls.state.value is AndroidUseControlState.Inactive)
        assertTrue(controls.acquire(node, null, identity, "demo.app") is AndroidUseLeaseDecision.Rejected)
      } finally {
        overlay.detach()
      }
    }
}
