package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayMethod
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayWizardControllerTest {
  @Test
  fun startAcquiresOwnershipThenPullsTheFirstStep() =
    runTest {
      val transport = WizardTransport()
      var nextCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.auth.start" -> """{"sessionId":"setup-1","done":false,"status":"running"}"""
          "wizard.next" -> {
            nextCalls += 1
            if (nextCalls == 1) {
              """{"done":false,"status":"running","step":{"id":"secret","type":"text","title":"API key","sensitive":true}}"""
            } else {
              """{"done":true,"status":"done","modelActivation":{"modelRef":"deepseek/chat","gatewayRestartRequired":false}}"""
            }
          }
          else -> error("Unexpected method")
        }
      }
      val controller = GatewayWizardController(this, transport, Json)

      controller.start(authLaunch())
      runCurrent()

      val active = controller.state.value as GatewayWizardState.Active
      assertTrue(active.step.sensitive)
      assertEquals(listOf("openclaw.setup.auth.start", "wizard.next"), transport.requests.map { it.first })

      controller.answer("secret", JsonPrimitive("temporary-secret"))
      runCurrent()

      assertTrue(controller.state.value is GatewayWizardState.Finished)
      assertFalse(
        controller.state.value
          .toString()
          .contains("temporary-secret"),
      )
    }

  @Test
  fun lostStartReplyRecoversWithNonAnsweringNext() =
    runTest {
      val transport = WizardTransport()
      var start = true
      transport.respond = { method, _ ->
        when {
          start && method == "openclaw.setup.auth.start" -> {
            start = false
            throw GatewayRequestOutcomeUnknown("lost")
          }
          method == "wizard.next" ->
            """{"done":false,"status":"running","step":{"id":"confirm","type":"confirm","message":"Continue"}}"""
          else -> error("Unexpected method")
        }
      }
      val controller = GatewayWizardController(this, transport, Json)

      controller.start(authLaunch())
      runCurrent()
      assertTrue(controller.state.value is GatewayWizardState.Recovering)

      controller.reconcile()
      runCurrent()

      assertTrue(controller.state.value is GatewayWizardState.Active)
      assertEquals(listOf("openclaw.setup.auth.start", "wizard.next"), transport.requests.map { it.first })
      assertEquals("""{"sessionId":"setup-1"}""", transport.requests.last().second)
    }

  @Test
  fun restoredSessionPullsItsCurrentStepWithoutStartingAgain() =
    runTest {
      val transport = WizardTransport()
      transport.respond = { method, _ ->
        check(method == "wizard.next")
        """{"done":false,"status":"running","step":{"id":"device","type":"note","externalUrl":"https://auth.example/device","deviceCode":{"code":"ABCD-EFGH"}}}"""
      }
      val controller = GatewayWizardController(this, transport, Json)

      controller.restore("ai-setup", "setup-1")
      controller.reconcile()
      runCurrent()

      val active = controller.state.value as GatewayWizardState.Active
      assertEquals("ABCD-EFGH", active.step.deviceCode?.code)
      assertEquals(listOf("wizard.next"), transport.requests.map { it.first })
    }

  @Test
  fun progressPollTimeoutKeepsTheRenderedProgressAndAllowsAnotherPoll() =
    runTest {
      val transport = WizardTransport()
      var nextCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.auth.start" -> """{"sessionId":"setup-1","done":false,"status":"running"}"""
          "wizard.next" -> {
            nextCalls += 1
            if (nextCalls == 1) {
              """{"done":false,"status":"running","step":{"id":"progress-1","type":"progress","message":"Waiting for device authorization"}}"""
            } else {
              throw GatewayRequestOutcomeUnknown("poll timeout")
            }
          }
          else -> error("Unexpected method")
        }
      }
      val controller = GatewayWizardController(this, transport, Json)
      controller.start(authLaunch())
      runCurrent()

      controller.reconcile()
      runCurrent()

      val active = controller.state.value as GatewayWizardState.Active
      assertEquals("progress-1", active.step.id)
      assertFalse(active.submitting)
      controller.reconcile()
      runCurrent()
      assertEquals(3, nextCalls)
    }

  @Test
  fun browserAuthorizationKeepsReconcilingWhileManualFallbackIsVisible() =
    runTest {
      val transport = WizardTransport()
      var nextCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.auth.start" -> """{"sessionId":"setup-1","done":false,"status":"running"}"""
          "wizard.next" -> {
            nextCalls += 1
            when (nextCalls) {
              1 -> """{"done":false,"status":"running","step":{"id":"browser","type":"note","externalUrl":"https://auth.example/authorize"}}"""
              2 -> """{"done":false,"status":"running","step":{"id":"redirect","type":"text","sensitive":true}}"""
              else -> """{"done":true,"status":"done"}"""
            }
          }
          else -> error("Unexpected method")
        }
      }
      val controller = GatewayWizardController(this, transport, Json)
      controller.start(authLaunch())
      runCurrent()

      controller.browserOpened("browser")
      assertTrue((controller.state.value as GatewayWizardState.Active).waitingForBrowser)
      controller.answer("browser", null)
      runCurrent()

      val fallback = controller.state.value as GatewayWizardState.Active
      assertEquals("redirect", fallback.step.id)
      assertTrue(fallback.waitingForBrowser)
      controller.reconcile()
      runCurrent()

      assertTrue(controller.state.value is GatewayWizardState.Finished)
    }

  @Test
  fun cancellationWaitsForTheGatewayOutcomeAndClearsTheRenderedSecret() =
    runTest {
      val transport = WizardTransport()
      val cancellation = CompletableDeferred<String>()
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.auth.start" -> """{"sessionId":"setup-1","done":false,"status":"running"}"""
          "wizard.next" -> """{"done":false,"status":"running","step":{"id":"secret","type":"text","sensitive":true}}"""
          "wizard.cancel" -> cancellation.await()
          else -> error("Unexpected method")
        }
      }
      val controller = GatewayWizardController(this, transport, Json)
      controller.start(authLaunch())
      runCurrent()

      controller.cancel()
      assertTrue(controller.state.value is GatewayWizardState.Cancelling)
      cancellation.complete("""{"status":"cancelled","error":"cancelled"}""")
      runCurrent()

      assertEquals(GatewayWizardState.Cancelled("ai-setup"), controller.state.value)
      assertEquals(listOf("openclaw.setup.auth.start", "wizard.next", "wizard.cancel"), transport.requests.map { it.first })
    }

  private class WizardTransport : AiGatewayTransport {
    var connection = connection()
    val requests = mutableListOf<Pair<String, String>>()
    var respond: suspend (String, String) -> String = { _, _ -> error("No response") }

    override fun capture(): AiGatewayConnection = connection

    override fun publish(
      connection: AiGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (connection != this.connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: AiGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      requests += method to params
      return respond(method, params)
    }
  }

  companion object {
    private fun authLaunch() =
      GatewayWizardLaunch(
        ownerId = "ai-setup",
        sessionId = "setup-1",
        method = GatewayMethod.OpenclawSetupAuthStart,
        params = """{"sessionId":"setup-1","authChoice":"openai-codex"}""",
      )

    private fun connection() = AiGatewayConnection("gateway", 1, 1, requiredAiGatewayMethods, "main", true)
  }
}
