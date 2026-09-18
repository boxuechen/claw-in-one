package ai.openclaw.app.vscreen

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.vscreen.producer.ANDROID_VSCREEN_PRODUCER_ID
import ai.openclaw.app.vscreen.producer.AndroidVScreenProducer
import android.graphics.SurfaceTexture
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VScreenControllerStateTest {
  @Test
  fun homeAndWorkloadFrameAcknowledgementsHaveDistinctState() {
    assertFalse(hasReportedVScreenFrame(false, null, null))
    assertTrue(hasReportedVScreenFrame(true, null, null))
    assertFalse(hasReportedVScreenFrame(true, null, "workload-a"))
    assertTrue(hasReportedVScreenFrame(true, "workload-a", "workload-a"))
    assertFalse(hasReportedVScreenFrame(true, "workload-a", "workload-b"))
  }

  @Test
  fun workloadPresentationIsRequestedBeforeGatewayPlacement() =
    runTest {
      var presentationRequested = false
      val transport = FakeTransport { presentationRequested }
      val controller =
        controller(
          transport = transport,
          onWorkloadRequested = { presentationRequested = true },
        )

      controller.onGatewayEvent("agent", workloadEventJson())
      runCurrent()

      assertTrue(presentationRequested)
      assertEquals(listOf(VSCREEN_WORKLOAD_METHOD), transport.calls)
      controller.invalidateConnection(transport.connection.lease)
      runCurrent()
    }

  @Test
  fun workloadRequestFailureRecoversAHomeDisplay() =
    runTest {
      val failures = mutableListOf<String>()
      val transport = FakeTransport().apply { failWorkload = true }
      val controller =
        controller(
          transport = transport,
          onWorkloadFailed = { _, message -> failures += message },
        )

      controller.onGatewayEvent("agent", workloadEventJson())
      advanceUntilIdle()

      assertEquals(listOf(VSCREEN_WORKLOAD_METHOD, VSCREEN_ENSURE_METHOD), transport.calls)
      assertEquals(VScreenForeground.Home, controller.state.value.foreground)
      assertEquals(HOME_ATTACHMENT_ID, controller.state.value.attachmentId)
      assertEquals(null, controller.state.value.workloadRequestId)
      assertEquals(1, failures.size)
    }

  @Test
  fun workloadFirstFrameTimeoutRecoversBeforeGatewayReadinessDeadline() =
    runTest {
      val failures = mutableListOf<String>()
      val transport = FakeTransport()
      val controller =
        controller(
          transport = transport,
          onWorkloadFailed = { _, message -> failures += message },
          workloadFirstFrameTimeoutMs = 100,
        )

      controller.onGatewayEvent("agent", workloadEventJson())
      runCurrent()
      assertEquals(listOf(VSCREEN_WORKLOAD_METHOD), transport.calls)

      advanceTimeBy(100)
      runCurrent()

      assertEquals(listOf(VSCREEN_WORKLOAD_METHOD, VSCREEN_ENSURE_METHOD), transport.calls)
      assertEquals(VScreenForeground.Home, controller.state.value.foreground)
      assertEquals(HOME_ATTACHMENT_ID, controller.state.value.attachmentId)
      assertEquals(null, controller.state.value.workloadRequestId)
      assertTrue(failures.single().contains("first frame"))
    }

  @Test
  fun workloadDisplayFailureAlsoRecoversAHomeDisplay() =
    runTest {
      val failures = mutableListOf<String>()
      var observation = 0
      val observer =
        AndroidVScreenDisplayObserver { _, onChanged ->
          if (observation++ == 0) onChanged(null)
          AndroidVScreenDisplaySubscription {}
        }
      val transport = FakeTransport()
      val controller =
        controller(
          transport = transport,
          onWorkloadFailed = { _, message -> failures += message },
          displayObserver = observer,
        )

      controller.onGatewayEvent("agent", workloadEventJson())
      advanceUntilIdle()

      assertEquals(listOf(VSCREEN_WORKLOAD_METHOD, VSCREEN_ENSURE_METHOD), transport.calls)
      assertEquals(VScreenForeground.Home, controller.state.value.foreground)
      assertEquals(HOME_ATTACHMENT_ID, controller.state.value.attachmentId)
      assertTrue(failures.single().contains("display ended"))
    }

  @Test
  fun decoderFailureRecoversViewerWithoutReplacingTarget() =
    runTest {
      val transport = FakeTransport()
      val sinks = FakeVideoSinkFactory()
      val controller = controller(transport = transport, videoSinkFactory = sinks)

      controller.ensure()
      advanceUntilIdle()
      val attachmentId = controller.state.value.attachmentId!!
      val surfaceTexture = SurfaceTexture(0)
      val surface = Surface(surfaceTexture)
      controller.attachSurface(attachmentId, surface)

      assertEquals(1, sinks.created.size)
      sinks.created.single().frame()
      advanceUntilIdle()
      assertEquals(VScreenRuntime.Ready, controller.state.value.runtime)
      sinks.created.single().fail("decoder stalled")

      assertEquals(2, sinks.created.size)
      assertEquals(VScreenRuntime.Reconnecting, controller.state.value.runtime)
      assertEquals(attachmentId, controller.state.value.attachmentId)
      assertEquals(5L, controller.state.value.targetGeneration)
      assertEquals(10L, controller.state.value.sourceGeneration)
      assertEquals(listOf(VSCREEN_ENSURE_METHOD, VSCREEN_FRAME_PRESENTED_METHOD), transport.calls)

      sinks.created.last().frame()
      assertEquals(VScreenRuntime.Ready, controller.state.value.runtime)
      assertEquals(listOf(VSCREEN_ENSURE_METHOD, VSCREEN_FRAME_PRESENTED_METHOD), transport.calls)

      controller.invalidateConnection(transport.connection.lease)
      surface.release()
      surfaceTexture.release()
    }

  @Test
  fun renderingRequiresSurfacePresentationAndForegroundTogether() =
    runTest {
      val transport = FakeTransport()
      val sinks = FakeVideoSinkFactory()
      val controller = controller(transport = transport, videoSinkFactory = sinks)
      controller.setPresentationVisible(true)
      controller.ensure()
      advanceUntilIdle()
      val attachmentId = controller.state.value.attachmentId!!
      val owner = controller.surfaceOwner(attachmentId)!!
      val token = Any()
      val texture = SurfaceTexture(0)

      owner.attach(token, texture)
      val sink = sinks.created.single()
      assertTrue(sink.rendering)

      controller.setAppForeground(false)
      assertFalse(sink.rendering)
      owner.attach(token, texture)
      assertFalse(sink.rendering)

      controller.setAppForeground(true)
      assertTrue(sink.rendering)
      owner.detach(token, texture)
      assertFalse(sink.rendering)
      controller.setPresentationVisible(true)
      assertFalse(sink.rendering)

      controller.invalidateConnection(transport.connection.lease)
    }

  @Test
  fun closeRetiresTheExactDisplayBeforeHidingPresentation() =
    runTest {
      val transport = FakeTransport()
      val controller = controller(transport = transport)
      var presentationClosed = false

      controller.ensure()
      advanceUntilIdle()
      controller.close { presentationClosed = true }
      advanceUntilIdle()

      assertEquals(listOf(VSCREEN_ENSURE_METHOD, VSCREEN_CLOSE_METHOD), transport.calls)
      assertTrue(presentationClosed)
      assertEquals(VScreenRuntime.Unavailable, controller.state.value.runtime)
      assertEquals(null, controller.state.value.attachmentId)
    }

  @Test
  fun closeDuringStartupWaitsForIdentityThenRetiresIt() =
    runTest {
      val gate = CompletableDeferred<String>()
      val transport = FakeTransport().apply { ensureGate = gate }
      val controller = controller(transport = transport)
      var presentationClosed = false

      controller.ensure()
      runCurrent()
      controller.close { presentationClosed = true }
      runCurrent()
      assertEquals(VScreenRuntime.Closing, controller.state.value.runtime)
      assertFalse(presentationClosed)

      gate.complete(homeDescriptorJson())
      advanceUntilIdle()

      assertEquals(listOf(VSCREEN_ENSURE_METHOD, VSCREEN_CLOSE_METHOD), transport.calls)
      assertTrue(presentationClosed)
      assertEquals(VScreenRuntime.Unavailable, controller.state.value.runtime)
    }

  @Test
  fun lostCloseResponseUsesStatusReadbackBeforeHiding() =
    runTest {
      val transport = FakeTransport().apply { loseCloseResponse = true }
      val controller = controller(transport = transport)
      var presentationClosed = false

      controller.ensure()
      advanceUntilIdle()
      controller.close { presentationClosed = true }
      advanceUntilIdle()

      assertEquals(
        listOf(VSCREEN_ENSURE_METHOD, VSCREEN_CLOSE_METHOD, VSCREEN_STATUS_METHOD),
        transport.calls,
      )
      assertTrue(presentationClosed)
      assertEquals(VScreenRuntime.Unavailable, controller.state.value.runtime)
    }

  @Test
  fun unconfirmedCloseKeepsTheTargetAndPresentation() =
    runTest {
      val transport =
        FakeTransport().apply {
          loseCloseResponse = true
          failStatusReadback = true
        }
      val controller = controller(transport = transport)
      var presentationClosed = false

      controller.ensure()
      advanceUntilIdle()
      controller.close { presentationClosed = true }
      advanceUntilIdle()

      assertEquals(
        listOf(VSCREEN_ENSURE_METHOD, VSCREEN_CLOSE_METHOD, VSCREEN_STATUS_METHOD),
        transport.calls,
      )
      assertFalse(presentationClosed)
      assertEquals(VScreenRuntime.Ready, controller.state.value.runtime)
      assertEquals(HOME_ATTACHMENT_ID, controller.state.value.attachmentId)
    }

  private fun kotlinx.coroutines.test.TestScope.controller(
    transport: FakeTransport,
    onWorkloadRequested: (VScreenWorkloadEvent) -> Unit = {},
    onWorkloadFailed: (VScreenWorkloadEvent, String) -> Unit = { _, _ -> },
    workloadFirstFrameTimeoutMs: Long = 35_000,
    displayObserver: AndroidVScreenDisplayObserver = NoOpAndroidVScreenDisplayObserver,
    videoSinkFactory: AndroidVScreenVideoSinkFactory = AndroidMediaCodecVScreenSinkFactory,
  ) = VScreenController(
    scope = this,
    transport = transport,
    json = Json { ignoreUnknownKeys = true },
    targets = VScreenTargetRegistry(),
    producers = VScreenProducerRegistry(listOf(AndroidVScreenProducer)),
    producerId = ANDROID_VSCREEN_PRODUCER_ID,
    onWorkloadRequested = onWorkloadRequested,
    onWorkloadFailed = onWorkloadFailed,
    workloadFirstFrameTimeoutMs = workloadFirstFrameTimeoutMs,
    displayObserver = displayObserver,
    videoSinkFactory = videoSinkFactory,
    nowMs = { NOW_MS },
  )

  private class FakeTransport(
    private val presentationReady: () -> Boolean = { true },
  ) : VScreenTransport {
    val connection =
      VScreenGatewayConnection(
        stableId = "gateway",
        generation = 1,
        catalogRevision = 1,
        methods = vscreenMethods,
        lease = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No lease RPC expected") },
      )
    val calls = mutableListOf<String>()
    var failWorkload = false
    var ensureGate: CompletableDeferred<String>? = null
    var loseCloseResponse = false
    var failStatusReadback = false

    override fun capture(): VScreenGatewayConnection = connection

    override fun publish(
      connection: VScreenGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (connection != this.connection) return false
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
        VSCREEN_WORKLOAD_METHOD -> {
          check(presentationReady()) { "Presentation must be requested before workload placement" }
          if (failWorkload) error("Android could not start the requested VScreen workload")
          workloadDescriptorJson()
        }
        VSCREEN_ENSURE_METHOD -> ensureGate?.await() ?: homeDescriptorJson()
        VSCREEN_CLOSE_METHOD -> {
          if (loseCloseResponse) error("close response lost")
          closeResponseJson()
        }
        VSCREEN_STATUS_METHOD -> {
          if (failStatusReadback) error("status response lost")
          """{"protocolVersion":3,"status":"unavailable"}"""
        }
        VSCREEN_FRAME_PRESENTED_METHOD ->
          """{"protocolVersion":3,"status":"recorded","attachmentId":"$HOME_ATTACHMENT_ID","foreground":"home"}"""
        else -> error("Unexpected method $method")
      }
    }

    override fun openStream(
      connection: VScreenGatewayConnection,
      routePath: String,
      bearerToken: String,
      listener: VScreenStreamListener,
    ): VScreenStream =
      object : VScreenStream {
        override fun send(text: String): Boolean = true

        override fun cancel() = Unit
      }
  }

  private class FakeVideoSinkFactory : AndroidVScreenVideoSinkFactory {
    val created = mutableListOf<FakeVideoSink>()

    override fun create(
      surface: Surface,
      width: Int,
      height: Int,
      onFrame: () -> Unit,
      onFailure: (String) -> Unit,
    ): AndroidVScreenVideoSink = FakeVideoSink(onFrame, onFailure).also(created::add)
  }

  private class FakeVideoSink(
    private val onFrame: () -> Unit,
    private val onFailure: (String) -> Unit,
  ) : AndroidVScreenVideoSink {
    var rendering = true
    var closed = false

    override fun submit(packet: AndroidVScreenVideoPacket) = Unit

    override fun setRenderingEnabled(enabled: Boolean) {
      rendering = enabled
    }

    override fun close() {
      closed = true
    }

    fun frame() = onFrame()

    fun fail(message: String) = onFailure(message)
  }

  private companion object {
    const val NOW_MS = 1_000L
    const val WORKLOAD_ID = "92345678-1234-4123-8123-123456789abc"
    const val WORKLOAD_ATTACHMENT_ID = "82345678-1234-4123-8123-123456789abc"
    const val HOME_ATTACHMENT_ID = "72345678-1234-4123-8123-123456789abc"

    fun workloadEventJson(): String = """{"sessionKey":"agent:main:demo","runId":"run-1","stream":"claw-in-one-vscreen.workload","data":{"protocolVersion":3,"phase":"offered","producerId":"$ANDROID_VSCREEN_PRODUCER_ID","workloadRequestId":"$WORKLOAD_ID","executionSessionId":"session-1","toolCallId":"tool-1","producerPayload":{"targetPackage":"com.example.demo"}}}"""

    fun workloadDescriptorJson(): String = """{"protocolVersion":3,"status":"ready","kind":"display","attachmentId":"$WORKLOAD_ATTACHMENT_ID","producerId":"$ANDROID_VSCREEN_PRODUCER_ID","targetRef":"target:workload","targetGeneration":4,"sourceGeneration":9,"foreground":"workload","streamPath":"/claw-in-one/vscreen","codec":"h264","token":"${"a".repeat(64)}","expiresAtMs":2000,"display":{"id":7,"width":720,"height":1560,"dpi":320,"rotation":0},"capabilities":["video/h264","pointer-v1"],"workload":{"generation":3,"requestId":"$WORKLOAD_ID"}}"""

    fun homeDescriptorJson(): String = """{"protocolVersion":3,"status":"ready","kind":"display","attachmentId":"$HOME_ATTACHMENT_ID","producerId":"$ANDROID_VSCREEN_PRODUCER_ID","targetRef":"target:home","targetGeneration":5,"sourceGeneration":10,"foreground":"home","streamPath":"/claw-in-one/vscreen","codec":"h264","token":"${"b".repeat(64)}","expiresAtMs":2000,"display":{"id":8,"width":720,"height":1560,"dpi":320,"rotation":0},"capabilities":["video/h264","pointer-v1"]}"""

    fun closeResponseJson(): String = """{"protocolVersion":3,"status":"closed","attachmentId":"$HOME_ATTACHMENT_ID","targetGeneration":5,"sourceGeneration":10}"""
  }
}
