package ai.openclaw.app.node

import ai.openclaw.app.androiduse.ANDROID_USE_NODE_COMMAND
import ai.openclaw.app.androiduse.AndroidUseHandler
import ai.openclaw.app.androiduse.AndroidUseLeaseController
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.vscreen.VScreenTargetRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InvokeDispatcherTest {
  @Test
  fun invocationRechecksAvailabilityAfterAdvertisement() =
    runTest {
      var available = true
      val dispatcher = newDispatcher { available }

      available = false

      assertEquals(
        "MOBILE_UI_UNAVAILABLE",
        dispatcher.handleInvoke(ANDROID_USE_NODE_COMMAND, null).error?.code,
      )
    }

  @Test
  fun unknownCallsDoNotReadAvailabilityOrReachAndroidUse() =
    runTest {
      val dispatcher = newDispatcher { error("must not read") }

      assertEquals("INVALID_REQUEST", dispatcher.handleInvoke("device.status", null).error?.code)
    }

  @Test
  fun availableAndroidUseRequiresTheOriginatingNodeConnection() =
    runTest {
      val dispatcher = newDispatcher { true }

      assertEquals(
        "GATEWAY_DISCONNECTED",
        dispatcher.handleInvoke(ANDROID_USE_NODE_COMMAND, null).error?.code,
      )
    }

  @Test
  fun availableAndroidUseDelegatesToItsHandler() =
    runTest {
      val dispatcher = newDispatcher { true }
      val lease = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") }

      assertEquals(
        "INVALID_REQUEST",
        dispatcher.handleInvoke(ANDROID_USE_NODE_COMMAND, null, lease).error?.code,
      )
    }

  private fun newDispatcher(androidUseAvailable: () -> Boolean): InvokeDispatcher =
    InvokeDispatcher(
      androidUseHandler =
        AndroidUseHandler(
          AndroidUseLeaseController(CoroutineScope(SupervisorJob())),
          VScreenTargetRegistry(),
        ),
      androidUseAvailable = androidUseAvailable,
    )
}
