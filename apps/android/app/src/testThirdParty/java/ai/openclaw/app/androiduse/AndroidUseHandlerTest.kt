package ai.openclaw.app.androiduse

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.node.MobileUiExecutor
import ai.openclaw.app.node.MobileUiTarget
import ai.openclaw.app.vscreen.VScreenTargetCandidate
import ai.openclaw.app.vscreen.VScreenTargetRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidUseHandlerTest {
  private val nodeConnection = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") }

  @Test
  fun disabledConsentRejectsBeforeTargetWorkButAcknowledgesExactCleanup() =
    runTest {
      val controller = AndroidUseLeaseController(this)
      val executor = FakeMobileUiExecutor()
      val target = FakeTargetController()
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), executor, target) { TARGET }
      for (kind in listOf("main", "vscreen")) {
        val result = handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", display = kind).toString())
        assertEquals("ANDROID_USE_DISABLED", result.error?.code)
      }
      assertTrue(target.launches.isEmpty())
      assertEquals(0, executor.observeCalls)
      assertEquals(0L, testScheduler.currentTime)
      assertTrue(handler.handle(nodeConnection, envelope(acquire = false, operation = "revoke").toString()).ok)
    }

  @Test
  fun acquisitionKeepsBothConnectionsCapturedBeforeForegroundHandoff() =
    runTest {
      for (lostRole in listOf("node", "operator")) {
        val controls = AndroidUseLeaseController(backgroundScope, consentGranted = { true })
        controls.setStopSurfaceAvailable(true)
        var nodeReady = true
        var operatorReady = true
        val node = GatewaySession.RequestLease("gateway", isCurrentImpl = { nodeReady }) { _, _, _, _ -> error("No RPC expected") }
        val operator = GatewaySession.RequestLease("gateway", isCurrentImpl = { operatorReady }) { _, _, _, _ -> error("No RPC expected") }
        var currentOperator = operator
        val executor = FakeMobileUiExecutor()
        val target =
          object : AndroidUseTargetController {
            override suspend fun bringToForeground(packageName: String): AndroidUseTargetResult {
              if (lostRole == "node") nodeReady = false else operatorReady = false
              currentOperator = nodeConnection
              return AndroidUseTargetResult.Ready
            }
          }
        val handler =
          AndroidUseHandler(
            controls,
            VScreenTargetRegistry(),
            executor,
            target,
            currentOperatorConnection = { currentOperator },
            foregroundPackage = { TARGET },
          )
        val result = handler.handle(node, envelope(acquire = true, operation = "observe").toString())
        assertEquals("GATEWAY_DISCONNECTED", result.error?.code)
        assertEquals(0, executor.observeCalls)
        assertTrue(controls.state.value is AndroidUseControlState.Inactive)
      }
    }

  @Test
  fun `approved acquire delegates observation and retains authority in Android`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val executor = FakeMobileUiExecutor()
      val target = FakeTargetController()
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), executor, target) { TARGET }

      val result = handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString())

      assertTrue(result.ok)
      assertEquals(1, executor.observeCalls)
      assertEquals(listOf(TARGET), target.launches)
      assertFalse(result.payloadJson.orEmpty().contains(CONTROL))
      assertEquals(TARGET, (controller.state.value as AndroidUseControlState.Active).targetPackage)
    }

  @Test
  fun `preview acquisition waits for the exact run binding instead of falling back to main display`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry()
      val executor = FakeMobileUiExecutor()
      val target = FakeTargetController()
      val handler = AndroidUseHandler(controller, bindings, executor, target, vscreenTargetPackage = { it.targetRef }) { TARGET }

      val result =
        async {
          handler.handle(
            nodeConnection,
            envelope(acquire = true, operation = "observe", display = "vscreen").toString(),
          )
        }
      runCurrent()
      assertEquals(0, executor.observeCalls)
      bindings.publish(previewBinding())

      assertTrue(result.await().ok)
      assertTrue(executor.lastTarget is MobileUiTarget.VScreenDisplay)
      assertTrue(target.launches.isEmpty())
    }

  @Test
  fun `preview acquisition waits for its accessibility window before observing`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry().also { it.publish(previewBinding()) }
      val executor = FakeMobileUiExecutor().also { it.targetAvailable = false }
      val handler = AndroidUseHandler(controller, bindings, executor, FakeTargetController(), vscreenTargetPackage = { it.targetRef }) { TARGET }

      val result =
        async {
          handler.handle(
            nodeConnection,
            envelope(acquire = true, operation = "observe", display = "vscreen").toString(),
          )
        }
      runCurrent()
      assertEquals(0, executor.observeCalls)
      executor.targetAvailable = true

      assertTrue(result.await().ok)
      assertEquals(1, executor.observeCalls)
    }

  @Test
  fun `another chat cannot replace an active lease`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val handler =
        AndroidUseHandler(controller, VScreenTargetRegistry(), FakeMobileUiExecutor(), FakeTargetController()) {
          TARGET
        }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)

      val competing =
        handler.handle(
          nodeConnection,
          envelope(
            acquire = true,
            operation = "observe",
            controlId = "22222222-2222-4222-8222-222222222222",
            ownerKey = "c".repeat(64),
          ).toString(),
        )

      assertEquals("CONTROL_BUSY", competing.error?.code)
    }

  @Test
  fun `continuation requires every opaque binding`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val executor = FakeMobileUiExecutor()
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), executor, FakeTargetController()) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)

      val result =
        handler.handle(
          nodeConnection,
          envelope(acquire = false, operation = "activate", ownerKey = "e".repeat(64), request = actRequest())
            .toString(),
        )

      assertEquals("CONTROL_OWNER_MISMATCH", result.error?.code)
      assertEquals(0, executor.actCalls)
    }

  @Test
  fun `target change revokes before dispatch`() =
    runTest {
      var foreground = TARGET
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val executor = FakeMobileUiExecutor()
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), executor, FakeTargetController()) { foreground }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)

      foreground = "com.example.other"
      val result =
        handler.handle(
          nodeConnection,
          envelope(acquire = false, operation = "activate", request = actRequest()).toString(),
        )

      assertEquals("TARGET_CHANGED", result.error?.code)
      assertEquals(0, executor.actCalls)
      assertEquals(
        AndroidUseRevocation.TargetChanged,
        (controller.state.value as AndroidUseControlState.Inactive).lastRevocation,
      )
    }

  @Test
  fun `stop is authorized and retires the lease`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val handler =
        AndroidUseHandler(controller, VScreenTargetRegistry(), FakeMobileUiExecutor(), FakeTargetController()) {
          TARGET
        }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)

      val result = handler.handle(nodeConnection, envelope(acquire = false, operation = "stop").toString())

      assertTrue(result.ok)
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
    }

  @Test
  fun `host revoke cancels an in-flight action without waiting for its mutex`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val executor = FakeMobileUiExecutor()
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), executor, FakeTargetController()) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)
      val entered = CompletableDeferred<Unit>()
      executor.beforeAct = {
        entered.complete(Unit)
        awaitCancellation()
      }
      val action = async { handler.handle(nodeConnection, envelope(acquire = false, operation = "activate", request = actRequest()).toString()) }
      runCurrent()
      assertTrue(entered.isCompleted)

      assertTrue(handler.handle(nodeConnection, envelope(acquire = false, operation = "revoke", ownerKey = "c".repeat(64)).toString()).ok)
      runCurrent()
      assertTrue(action.isActive)
      assertTrue(controller.state.value is AndroidUseControlState.Active)

      assertTrue(handler.handle(nodeConnection, envelope(acquire = false, operation = "revoke").toString()).ok)
      runCurrent()
      assertEquals("CONTROL_REVOKED", action.await().error?.code)
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
    }

  @Test
  fun `successful host release retires control without reporting revocation`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), FakeMobileUiExecutor(), FakeTargetController()) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)

      val released = handler.handle(nodeConnection, envelope(acquire = false, operation = "release").toString())

      assertTrue(released.ok)
      assertTrue(released.payloadJson.orEmpty().contains("\"status\":\"released\""))
      assertEquals(null, (controller.state.value as AndroidUseControlState.Inactive).lastRevocation)
    }

  @Test
  fun `host revoke works after preview replacement without touching the new target`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry()
      bindings.publish(previewBinding())
      val executor = FakeMobileUiExecutor()
      val target = FakeTargetController()
      val handler = AndroidUseHandler(controller, bindings, executor, target, vscreenTargetPackage = { it.targetRef }) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", display = "vscreen").toString()).ok)
      bindings.publish(previewBinding(attachmentId = ATTACHMENT_2, targetGeneration = 2))

      assertTrue(handler.handle(nodeConnection, envelope(acquire = false, operation = "revoke", display = "vscreen").toString()).ok)
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
      assertEquals(1, executor.observeCalls)
      assertEquals(0, executor.actCalls)
      assertTrue(target.launches.isEmpty())
      assertEquals(ATTACHMENT_2, bindings.current()?.attachmentId)
    }

  @Test
  fun `closing the matching preview cancels its action and prevents continuation`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry { controller.revokeVScreen(it) }
      bindings.publish(previewBinding())
      val executor = FakeMobileUiExecutor()
      val handler = AndroidUseHandler(controller, bindings, executor, FakeTargetController(), vscreenTargetPackage = { it.targetRef }) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", display = "vscreen").toString()).ok)
      executor.beforeAct = { awaitCancellation() }
      val action = async { handler.handle(nodeConnection, envelope(acquire = false, operation = "activate", display = "vscreen", request = actRequest()).toString()) }
      runCurrent()
      assertTrue(action.isActive)
      bindings.clear(UNRELATED_ATTACHMENT, 1)
      runCurrent()
      assertTrue(action.isActive)
      bindings.clear(ATTACHMENT_1, 1)
      runCurrent()
      assertEquals("CONTROL_REVOKED", action.await().error?.code)
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
      assertFalse(handler.handle(nodeConnection, envelope(acquire = false, operation = "observe", display = "vscreen").toString()).ok)
      assertEquals(1, executor.observeCalls)
      assertEquals(1, executor.actCalls)
    }

  @Test
  fun `request and executor cancellation are not reported as local control revocation`() =
    runTest {
      for (cancelRequest in listOf(true, false)) {
        val controller = AndroidUseLeaseController(backgroundScope, consentGranted = { true }, clock = { testScheduler.currentTime })
        controller.setStopSurfaceAvailable(true)
        val executor = FakeMobileUiExecutor()
        val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), executor, FakeTargetController()) { TARGET }
        assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)
        executor.beforeAct = {
          if (cancelRequest) awaitCancellation() else throw CancellationException("Executor cancelled")
        }
        val action = async { handler.handle(nodeConnection, envelope(acquire = false, operation = "activate", request = actRequest()).toString()) }
        runCurrent()
        if (cancelRequest) {
          action.cancel()
          controller.revoke(AndroidUseRevocation.UserStop)
          runCurrent()
        }
        assertTrue(action.isCancelled)
        controller.revoke(AndroidUseRevocation.UserStop)
      }
    }

  @Test
  fun `rejected acquisition never launches another app`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val target = FakeTargetController()
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), FakeMobileUiExecutor(), target) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString()).ok)
      val rejected = handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", controlId = "22222222-2222-4222-8222-222222222222").toString())
      assertEquals("CONTROL_BUSY", rejected.error?.code)
      assertEquals(listOf(TARGET), target.launches)
    }

  @Test
  fun `new envelope requires host execution identity and rejects the old protocol`() =
    runTest {
      val full = envelope(acquire = true, operation = "observe")
      for (field in listOf("executionKey", "sessionId", "runId", "authorizationNonce")) {
        assertEquals(null, parseAndroidUseEnvelope(JsonObject(full - field).toString()))
      }
      assertEquals(null, parseAndroidUseEnvelope(JsonObject(full + ("protocolVersion" to JsonPrimitive(3))).toString()))
    }

  @Test
  fun `failed main display handoff rejects before minting lease or observing`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val executor = FakeMobileUiExecutor()
      val target =
        AndroidUseTargetController {
          AndroidUseTargetResult.Failed("TARGET_NOT_LAUNCHABLE", "Target cannot be opened")
        }
      val handler = AndroidUseHandler(controller, VScreenTargetRegistry(), executor, target) { "com.example.other" }

      val result = handler.handle(nodeConnection, envelope(acquire = true, operation = "observe").toString())

      assertEquals("TARGET_NOT_LAUNCHABLE", result.error?.code)
      assertEquals(0, executor.observeCalls)
      assertTrue(controller.state.value is AndroidUseControlState.Inactive)
    }

  @Test
  fun `matching VScreen workload binds observation without launching on the main display`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry()
      bindings.publish(previewBinding())
      val executor = FakeMobileUiExecutor()
      val target = FakeTargetController()
      val handler = AndroidUseHandler(controller, bindings, executor, target, vscreenTargetPackage = { it.targetRef }) { "com.example.main" }

      val result =
        handler.handle(
          nodeConnection,
          envelope(acquire = true, operation = "observe", display = "vscreen").toString(),
        )

      assertTrue(result.ok)
      assertTrue(target.launches.isEmpty())
      val vscreen = executor.lastTarget as MobileUiTarget.VScreenDisplay
      assertEquals(7, vscreen.displayId)
      assertEquals(ATTACHMENT_1, vscreen.attachmentId)
      assertEquals(bindings.current()?.revision, vscreen.bindingRevision)
    }

  @Test
  fun `VScreen package mismatch never falls back to the main display`() =
    runTest {
      val cases = listOf(previewBinding(targetPackage = "com.example.other") to "VSCREEN_TARGET_MISMATCH")

      cases.forEach { (binding, code) ->
        val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
        controller.setStopSurfaceAvailable(true)
        val bindings = VScreenTargetRegistry().also { it.publish(binding) }
        val executor = FakeMobileUiExecutor()
        val target = FakeTargetController()
        val handler = AndroidUseHandler(controller, bindings, executor, target, vscreenTargetPackage = { it.targetRef }) { TARGET }

        val result = handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", display = "vscreen").toString())

        assertEquals(code, result.error?.code)
        assertTrue(target.launches.isEmpty())
        assertEquals(0, executor.observeCalls)
      }
    }

  @Test
  fun `missing producer resolver rejects preview without falling back to main display`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry().also { it.publish(previewBinding()) }
      val executor = FakeMobileUiExecutor()
      val target = FakeTargetController()
      val handler = AndroidUseHandler(controller, bindings, executor, target) { TARGET }

      val result = handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", display = "vscreen").toString())

      assertEquals("VSCREEN_WORKLOAD_UNAVAILABLE", result.error?.code)
      assertTrue(target.launches.isEmpty())
      assertEquals(0, executor.observeCalls)
    }

  @Test
  fun `preview geometry updates preserve the same control authority`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry { controller.revokeVScreen(it) }
      val initial = bindings.publish(previewBinding())
      val executor = FakeMobileUiExecutor()
      val handler = AndroidUseHandler(controller, bindings, executor, FakeTargetController(), vscreenTargetPackage = { it.targetRef }) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", display = "vscreen").toString()).ok)

      val resized = bindings.publish(previewBinding().copy(width = 1280, height = 720, rotation = 1))
      val result =
        handler.handle(
          nodeConnection,
          envelope(acquire = false, operation = "activate", display = "vscreen", request = actRequest()).toString(),
        )

      assertEquals(initial.revision, resized.revision)
      assertTrue(result.ok)
      assertEquals(1, executor.actCalls)
      assertTrue(controller.state.value is AndroidUseControlState.Active)
    }

  @Test
  fun `changed preview binding invalidates the prior snapshot authority`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      controller.setStopSurfaceAvailable(true)
      val bindings = VScreenTargetRegistry()
      bindings.publish(previewBinding())
      val executor = FakeMobileUiExecutor()
      val handler = AndroidUseHandler(controller, bindings, executor, FakeTargetController(), vscreenTargetPackage = { it.targetRef }) { TARGET }
      assertTrue(handler.handle(nodeConnection, envelope(acquire = true, operation = "observe", display = "vscreen").toString()).ok)

      bindings.publish(previewBinding(attachmentId = ATTACHMENT_2, targetGeneration = 2))
      val result =
        handler.handle(
          nodeConnection,
          envelope(acquire = false, operation = "activate", display = "vscreen", request = actRequest()).toString(),
        )

      assertEquals("TARGET_CHANGED", result.error?.code)
      assertEquals(0, executor.actCalls)
      assertEquals(
        AndroidUseRevocation.TargetChanged,
        (controller.state.value as AndroidUseControlState.Inactive).lastRevocation,
      )
    }

  @Test
  fun `invalid envelope reports the failed field without echoing opaque values`() =
    runTest {
      val controller = AndroidUseLeaseController(this, consentGranted = { true }, clock = { testScheduler.currentTime })
      val handler =
        AndroidUseHandler(controller, VScreenTargetRegistry(), FakeMobileUiExecutor(), FakeTargetController()) {
          TARGET
        }
      val invalidOwner = "SECRET-OWNER-VALUE"
      val params = envelope(acquire = true, operation = "observe", ownerKey = invalidOwner)

      val result = handler.handle(nodeConnection, params.toString())

      assertEquals("INVALID_REQUEST", result.error?.code)
      assertTrue(
        result.error
          ?.message
          .orEmpty()
          .contains("ownerKey"),
      )
      assertFalse(
        result.error
          ?.message
          .orEmpty()
          .contains(invalidOwner),
      )
    }

  private fun envelope(
    acquire: Boolean,
    operation: String,
    controlId: String = CONTROL,
    ownerKey: String = OWNER,
    display: String = "main",
    request: JsonObject = buildJsonObject {},
  ): JsonObject =
    buildJsonObject {
      put("protocolVersion", ANDROID_USE_PROTOCOL_VERSION)
      put("acquire", acquire)
      put("operation", operation)
      put("controlId", controlId)
      put("ownerKey", ownerKey)
      put("executionKey", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
      put("sessionId", "session-a")
      put("runId", "run-a")
      put("authorizationNonce", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
      put("targetPackage", TARGET)
      put("display", display)
      if (display == "vscreen") {
        put("assignmentId", "92345678-1234-4123-8123-123456789abc")
      } else {
        put("assignmentId", kotlinx.serialization.json.JsonNull)
      }
      put("request", request)
    }

  private fun actRequest(): JsonObject =
    buildJsonObject {
      put("snapshotId", "snapshot-1")
      put(
        "action",
        buildJsonObject {
          put("type", "activate")
          put("ref", "n1")
        },
      )
    }

  private class FakeMobileUiExecutor : MobileUiExecutor {
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)
    var observeCalls = 0
    var actCalls = 0
    var beforeAct: suspend () -> Unit = {}
    var lastTarget: MobileUiTarget? = null
    var targetAvailable = true

    override suspend fun handleObserve(
      target: MobileUiTarget,
      paramsJson: String?,
    ): GatewaySession.InvokeResult {
      observeCalls += 1
      lastTarget = target
      return GatewaySession.InvokeResult.ok(
        buildJsonObject {
          put("snapshotId", "snapshot-1")
          put("package", TARGET)
        }.toString(),
      )
    }

    override suspend fun handleAct(
      target: MobileUiTarget,
      paramsJson: String?,
    ): GatewaySession.InvokeResult {
      actCalls += 1
      beforeAct()
      lastTarget = target
      return GatewaySession.InvokeResult.ok(
        buildJsonObject {
          put("code", "completed")
          put("message", JsonPrimitive(null as String?))
        }.toString(),
      )
    }

    override fun isTargetAvailable(target: MobileUiTarget): Boolean = targetAvailable
  }

  private class FakeTargetController : AndroidUseTargetController {
    val launches = mutableListOf<String>()

    override suspend fun bringToForeground(packageName: String): AndroidUseTargetResult {
      launches += packageName
      return AndroidUseTargetResult.Ready
    }
  }

  private fun previewBinding(
    attachmentId: String = ATTACHMENT_1,
    targetPackage: String = TARGET,
    targetGeneration: Long = 1,
  ) = VScreenTargetCandidate(
    producerId = "claw-in-one-android-vscreen-producer",
    workloadRequestId = "92345678-1234-4123-8123-123456789abc",
    attachmentId = attachmentId,
    targetRef = targetPackage,
    targetGeneration = targetGeneration,
    sourceGeneration = targetGeneration,
    displayId = 7,
    width = 720,
    height = 1280,
    dpi = 320,
  )

  private companion object {
    const val TARGET = "com.example.fixture"
    const val CONTROL = "11111111-1111-4111-8111-111111111111"
    const val ATTACHMENT_1 = "82345678-1234-4123-8123-123456789abc"
    const val ATTACHMENT_2 = "82345678-1234-4123-8123-123456789abd"
    const val UNRELATED_ATTACHMENT = "82345678-1234-4123-8123-123456789abe"
    val OWNER = "a".repeat(64)
  }
}
