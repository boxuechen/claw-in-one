package ai.openclaw.app.vscreen

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSurfaceProbeControllerTest {
  @Test
  fun authenticatedFrameAndExactCleanupPublishReady() =
    runTest {
      val transport = FakeTransport()
      val rendered = mutableListOf<PreviewSurfaceProbeDescriptor>()
      val controller = controller(this, transport) { _, _, descriptor -> rendered += descriptor }

      controller.check(TARGET_ID, PHONE_VERIFICATION_ID)
      assertEquals(PreviewSurfaceProbeState.Checking, controller.state.value)
      advanceUntilIdle()

      assertEquals(
        PreviewSurfaceProbeState.Ready(TARGET_ID, PHONE_VERIFICATION_ID, PROBE_ID),
        controller.state.value,
      )
      assertEquals(listOf(PREVIEW_SURFACE_PROBE_START_METHOD, PREVIEW_SURFACE_PROBE_FINISH_METHOD), transport.calls)
      assertEquals(PROBE_ID, rendered.single().probeId)
    }

  @Test
  fun lostStartResponseUsesReadbackWithoutRepeatingTheMutation() =
    runTest {
      val transport = FakeTransport().apply { loseStartResponse = true }
      val controller = controller(this, transport) { _, _, _ -> }

      controller.check(TARGET_ID, PHONE_VERIFICATION_ID)
      advanceUntilIdle()

      assertTrue(controller.state.value is PreviewSurfaceProbeState.Ready)
      assertEquals(1, transport.calls.count { it == PREVIEW_SURFACE_PROBE_START_METHOD })
      assertEquals(1, transport.calls.count { it == PREVIEW_SURFACE_PROBE_STATUS_METHOD })
      assertEquals(1, transport.calls.count { it == PREVIEW_SURFACE_PROBE_FINISH_METHOD })
    }

  @Test
  fun lostFinishResponseUsesIdleReadbackWithoutRepeatingTheMutation() =
    runTest {
      val transport = FakeTransport().apply { loseFinishResponse = true }
      val controller = controller(this, transport) { _, _, _ -> }

      controller.check(TARGET_ID, PHONE_VERIFICATION_ID)
      advanceUntilIdle()

      assertTrue(controller.state.value is PreviewSurfaceProbeState.Ready)
      assertEquals(1, transport.calls.count { it == PREVIEW_SURFACE_PROBE_FINISH_METHOD })
      assertEquals(1, transport.calls.count { it == PREVIEW_SURFACE_PROBE_STATUS_METHOD })
    }

  @Test
  fun decoderFailureClosesTheExactProbeAndRequiresUserRetry() =
    runTest {
      val transport = FakeTransport()
      val controller =
        controller(this, transport) { _, _, _ ->
          error("decoder failed")
        }

      controller.check(TARGET_ID, PHONE_VERIFICATION_ID)
      advanceUntilIdle()

      assertTrue(controller.state.value is PreviewSurfaceProbeState.NeedsAction)
      assertEquals(1, transport.calls.count { it == PREVIEW_SURFACE_PROBE_START_METHOD })
      assertEquals(1, transport.calls.count { it == PREVIEW_SURFACE_PROBE_FINISH_METHOD })
    }

  @Test
  fun staleConnectionCannotLeaveTheProbeCheckingForever() =
    runTest {
      val transport = FakeTransport().apply { allowPublish = false }
      val controller = controller(this, transport) { _, _, _ -> }

      controller.check(TARGET_ID, PHONE_VERIFICATION_ID)
      advanceUntilIdle()

      assertTrue(controller.state.value is PreviewSurfaceProbeState.NeedsAction)
    }

  private fun controller(
    scope: kotlinx.coroutines.CoroutineScope,
    transport: FakeTransport,
    renderer: PreviewSurfaceProbeRenderer,
  ) = PreviewSurfaceProbeController(
    scope = scope,
    transport = transport,
    producerId = PRODUCER_ID,
    json = Json { ignoreUnknownKeys = true },
    renderer = renderer,
    createProbeId = { PROBE_ID },
    nowMs = { NOW_MS },
  )

  private class FakeTransport : VScreenTransport {
    val connection =
      VScreenGatewayConnection(
        stableId = "gateway",
        generation = 1,
        catalogRevision = 1,
        methods = previewSurfaceProbeMethods,
        lease = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") },
      )
    val calls = mutableListOf<String>()
    var loseStartResponse = false
    var loseFinishResponse = false
    var allowPublish = true
    private var probeActive = false

    override fun capture(): VScreenGatewayConnection = connection

    override fun publish(
      connection: VScreenGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (!allowPublish || connection != this.connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: VScreenGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      calls += method
      return when (method) {
        PREVIEW_SURFACE_PROBE_START_METHOD -> {
          probeActive = true
          if (loseStartResponse) {
            loseStartResponse = false
            error("response lost")
          }
          descriptorJson()
        }
        PREVIEW_SURFACE_PROBE_STATUS_METHOD -> if (probeActive) descriptorJson() else idleJson()
        PREVIEW_SURFACE_PROBE_FINISH_METHOD -> {
          probeActive = false
          if (loseFinishResponse) {
            loseFinishResponse = false
            error("response lost")
          }
          idleJson()
        }
        else -> error("Unexpected method $method")
      }
    }

    override fun openStream(
      connection: VScreenGatewayConnection,
      routePath: String,
      bearerToken: String,
      listener: VScreenStreamListener,
    ): VScreenStream = error("The fake renderer owns frame delivery")
  }

  private companion object {
    const val PROBE_ID = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    const val PHONE_VERIFICATION_ID = "cccccccccccccccccccccccccccccccc"
    const val TARGET_ID = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    const val NOW_MS = 1_000L
    const val PRODUCER_ID = "claw-in-one-android-vscreen-producer"
    const val ATTACHMENT_ID = "82345678-1234-4123-8123-123456789abc"

    fun descriptorJson(): String = """{"protocolVersion":3,"status":"ready","kind":"probe","attachmentId":"$ATTACHMENT_ID","producerId":"$PRODUCER_ID","probeId":"$PROBE_ID","targetRef":"$TARGET_ID","streamPath":"/claw-in-one/vscreen","codec":"h264","token":"${"a".repeat(64)}","expiresAtMs":2000,"display":{"id":7,"width":720,"height":1560,"dpi":320},"capabilities":["video/h264"]}"""

    fun idleJson(): String = """{"protocolVersion":3,"status":"unavailable","probeId":"$PROBE_ID"}"""
  }
}
