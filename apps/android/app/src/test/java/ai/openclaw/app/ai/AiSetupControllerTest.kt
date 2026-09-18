package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiSetupControllerTest {
  @Test
  fun unknownDefaultModelOutcomeReadsBackBeforeItVerifiesAndNeverRetriesTheMutation() =
    runTest {
      val transport = SetupTransport()
      var detectCalls = 0
      var verifyCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> {
            detectCalls += 1
            if (detectCalls == 1) configured() else configured("anthropic/claude-sonnet")
          }
          "models.list" -> allModels()
          "agents.update" -> throw GatewayRequestOutcomeUnknown("ack lost")
          "openclaw.setup.verify" -> {
            verifyCalls += 1
            val model = if (verifyCalls == 1) "openai-codex/gpt-5.6" else "anthropic/claude-sonnet"
            """{"ok":true,"modelRef":"$model","latencyMs":20}"""
          }
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()

      harness.controller.feature.actions
        .changeModel()
      runCurrent()
      harness.controller.feature.actions
        .chooseModel("anthropic/claude-sonnet")
      runCurrent()

      assertEquals("anthropic/claude-sonnet", (harness.controller.state.value as AiSetupState.Ready).modelRef)
      assertEquals(1, transport.requests.count { it.first == "agents.update" })
      assertEquals(2, detectCalls)
    }

  @Test
  fun chatGptSignInContinuesToProviderModelChoiceThenVerifies() =
    runTest {
      val transport = SetupTransport()
      var nextCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> choices()
          "openclaw.setup.auth.start" -> """{"sessionId":"session-1","done":false,"status":"running"}"""
          "wizard.next" -> {
            nextCalls += 1
            if (nextCalls == 1) {
              """{"done":false,"status":"running","step":{"id":"device","type":"note","externalUrl":"https://auth.example/device","deviceCode":{"code":"ABCD-EFGH"}}}"""
            } else {
              """{"done":true,"status":"done","modelActivation":{"modelRef":"openai-codex/gpt-5.6","gatewayRestartRequired":false}}"""
            }
          }
          "models.list" -> openAiModels()
          "openclaw.setup.verify" -> """{"ok":true,"modelRef":"openai-codex/gpt-5.6","latencyMs":37}"""
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)

      harness.controller.onConnectionChanged()
      runCurrent()
      harness.controller.feature.actions
        .chooseAuth("openai-codex")
      runCurrent()
      assertEquals("ABCD-EFGH", (harness.controller.state.value as AiSetupState.Wizard).step.deviceCode?.code)

      harness.controller.feature.actions
        .answerWizard("device", null)
      runCurrent()
      val modelState = harness.controller.state.value
      val models = modelState as? AiSetupState.ModelChoices ?: error("state=$modelState requests=${transport.requests}")
      assertEquals("openai-codex/gpt-5.6", models.currentModelRef)
      assertEquals(2, models.models.size)

      harness.controller.feature.actions
        .chooseModel("openai-codex/gpt-5.6")
      runCurrent()
      assertEquals("openai-codex/gpt-5.6", (harness.controller.state.value as AiSetupState.Ready).modelRef)
      assertEquals(0, transport.requests.count { it.first == "agents.update" })
    }

  @Test
  fun apiKeyStaysRequestLocalAndActivatesTheExactSelectedModel() =
    runTest {
      val transport = SetupTransport()
      var nextCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> choices()
          "models.list" -> deepSeekModels()
          "openclaw.setup.activate.start" -> """{"sessionId":"session-1","done":false,"status":"running"}"""
          "wizard.next" -> {
            nextCalls += 1
            if (nextCalls == 1) {
              """{"done":false,"status":"running","step":{"id":"confirm","type":"confirm"}}"""
            } else {
              """{"done":true,"status":"done","modelActivation":{"modelRef":"deepseek/deepseek-reasoner","gatewayRestartRequired":false}}"""
            }
          }
          "openclaw.setup.verify" -> """{"ok":true,"modelRef":"deepseek/deepseek-reasoner","latencyMs":31}"""
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()

      harness.controller.feature.actions
        .enterApiKey("deepseek")
      harness.controller.feature.actions
        .submitApiKey("temporary-secret")
      runCurrent()
      assertTrue(harness.controller.state.value is AiSetupState.ModelChoices)
      assertFalse(
        harness.controller.state.value
          .toString()
          .contains("temporary-secret"),
      )

      harness.controller.feature.actions
        .chooseModel("deepseek/deepseek-reasoner")
      runCurrent()
      val activation = transport.requests.single { it.first == "openclaw.setup.activate.start" }.second
      assertTrue(activation.contains("temporary-secret"))
      assertTrue(activation.contains("deepseek/deepseek-reasoner"))
      assertFalse(
        harness.controller.state.value
          .toString()
          .contains("temporary-secret"),
      )

      harness.controller.feature.actions
        .answerWizard("confirm", null)
      runCurrent()
      assertTrue(harness.controller.state.value is AiSetupState.Ready)
    }

  @Test
  fun apiKeyActivationRejectsADifferentReturnedModel() =
    runTest {
      val transport = SetupTransport()
      var nextCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> choices()
          "models.list" -> deepSeekModels()
          "openclaw.setup.activate.start" -> """{"sessionId":"session-1","done":false,"status":"running"}"""
          "wizard.next" -> {
            nextCalls += 1
            if (nextCalls == 1) {
              """{"done":false,"status":"running","step":{"id":"confirm","type":"confirm"}}"""
            } else {
              """{"done":true,"status":"done","modelActivation":{"modelRef":"deepseek/deepseek-chat","gatewayRestartRequired":false}}"""
            }
          }
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()

      harness.controller.feature.actions
        .enterApiKey("deepseek")
      harness.controller.feature.actions
        .submitApiKey("temporary-secret")
      runCurrent()
      harness.controller.feature.actions
        .chooseModel("deepseek/deepseek-reasoner")
      runCurrent()
      harness.controller.feature.actions
        .answerWizard("confirm", null)
      runCurrent()

      assertEquals(
        "OpenClaw activated a different model.",
        (harness.controller.state.value as AiSetupState.Failed).message,
      )
      assertFalse(transport.requests.any { it.first == "openclaw.setup.verify" })
    }

  @Test
  fun failedDefaultModelVerificationRollsBackThePreviousModel() =
    runTest {
      val transport = SetupTransport()
      var verifyCalls = 0
      transport.respond = { method, params ->
        when (method) {
          "openclaw.setup.detect" -> configured()
          "models.list" -> allModels()
          "agents.update" -> """{"ok":true,"agentId":"main"}"""
          "openclaw.setup.verify" -> {
            verifyCalls += 1
            if (verifyCalls == 1) {
              """{"ok":true,"modelRef":"openai-codex/gpt-5.6","latencyMs":20}"""
            } else {
              check(params.contains("main"))
              """{"ok":false,"status":"error","error":"provider rejected model"}"""
            }
          }
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()
      assertTrue(harness.controller.state.value is AiSetupState.Ready)

      harness.controller.feature.actions
        .changeModel()
      runCurrent()
      harness.controller.feature.actions
        .chooseModel("anthropic/claude-sonnet")
      runCurrent()

      assertEquals("provider rejected model", (harness.controller.state.value as AiSetupState.Failed).message)
      val updates = transport.requests.filter { it.first == "agents.update" }.map { it.second }
      assertEquals(2, updates.size)
      assertTrue(updates.first().contains("anthropic/claude-sonnet"))
      assertTrue(updates.last().contains("openai-codex/gpt-5.6"))
    }

  @Test
  fun failedRollbackIsReportedInsteadOfClaimingThePreviousModelWasRestored() =
    runTest {
      val transport = SetupTransport()
      var verifyCalls = 0
      var updateCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> configured()
          "models.list" -> allModels()
          "agents.update" -> {
            updateCalls += 1
            if (updateCalls == 1) """{"ok":true,"agentId":"main"}""" else error("rollback failed")
          }
          "openclaw.setup.verify" -> {
            verifyCalls += 1
            if (verifyCalls == 1) {
              """{"ok":true,"modelRef":"openai-codex/gpt-5.6","latencyMs":20}"""
            } else {
              """{"ok":false,"status":"error","error":"provider rejected model"}"""
            }
          }
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()

      harness.controller.feature.actions
        .changeModel()
      runCurrent()
      harness.controller.feature.actions
        .chooseModel("anthropic/claude-sonnet")
      runCurrent()

      assertEquals(
        "provider rejected model The previous default model could not be restored automatically.",
        (harness.controller.state.value as AiSetupState.Failed).message,
      )
      assertEquals(2, updateCalls)
    }

  @Test
  fun cancelledWizardReturnsToAccessChoices() =
    runTest {
      val transport = SetupTransport()
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> choices()
          "openclaw.setup.auth.start" -> """{"sessionId":"session-1","done":false,"status":"running"}"""
          "wizard.next" -> """{"done":false,"status":"running","step":{"id":"device","type":"confirm"}}"""
          "wizard.cancel" -> """{"status":"cancelled","error":"cancelled"}"""
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()
      harness.controller.feature.actions
        .chooseAuth("openai-codex")
      runCurrent()
      harness.controller.feature.actions
        .cancel()
      runCurrent()
      assertTrue(harness.controller.state.value is AiSetupState.Choices)
    }

  @Test
  fun dismissingAnActiveWizardCancelsThenRestoresVerifiedConfiguration() =
    runTest {
      val transport = SetupTransport()
      var detectCalls = 0
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> {
            detectCalls += 1
            if (detectCalls == 1) choices() else configured()
          }
          "openclaw.setup.auth.start" -> """{"sessionId":"session-1","done":false,"status":"running"}"""
          "wizard.next" -> """{"done":false,"status":"running","step":{"id":"device","type":"confirm"}}"""
          "wizard.cancel" -> """{"status":"cancelled","error":"cancelled"}"""
          "openclaw.setup.verify" -> """{"ok":true,"modelRef":"openai-codex/gpt-5.6","latencyMs":20}"""
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()
      harness.controller.feature.actions
        .chooseAuth("openai-codex")
      runCurrent()

      harness.controller.feature.actions
        .dismiss()
      runCurrent()

      assertEquals("openai-codex/gpt-5.6", (harness.controller.state.value as AiSetupState.Ready).modelRef)
      assertEquals(2, detectCalls)
    }

  @Test
  fun dismissingAccessEditorRestoresVerifiedConfiguration() =
    runTest {
      val transport = SetupTransport()
      transport.respond = { method, _ ->
        when (method) {
          "openclaw.setup.detect" -> configured()
          "openclaw.setup.verify" -> """{"ok":true,"modelRef":"openai-codex/gpt-5.6","latencyMs":20}"""
          else -> error("Unexpected method $method")
        }
      }
      val harness = Harness(backgroundScope, transport)
      harness.controller.onConnectionChanged()
      runCurrent()

      harness.controller.feature.actions
        .changeAccess()
      runCurrent()
      assertTrue(harness.controller.state.value is AiSetupState.Choices)

      harness.controller.feature.actions
        .dismiss()
      runCurrent()
      assertEquals("openai-codex/gpt-5.6", (harness.controller.state.value as AiSetupState.Ready).modelRef)
    }

  private class Harness(
    scope: kotlinx.coroutines.CoroutineScope,
    transport: SetupTransport,
  ) {
    private val wizard = GatewayWizardController(scope, transport, Json)
    private val restart = GatewayRestartCoordinator(scope, transport, { transport.identity() }, Json)
    val controller =
      AiSetupController(
        scope = scope,
        transport = transport,
        wizard = wizard,
        restart = restart,
        json = Json,
        newSessionId = { "session-1" },
      )
  }

  private class SetupTransport : AiGatewayTransport {
    var connection = AiGatewayConnection("gateway", 1, 1, requiredAiGatewayMethods, "main", true)
    val requests = mutableListOf<Pair<String, String>>()
    var respond: suspend (String, String) -> String = { method, _ -> error("No response for $method") }

    override fun capture(): AiGatewayConnection = connection

    fun identity() = GatewayRuntimeIdentity("supervisor-1", "gateway-1", connection)

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
    private fun choices() =
      """
      {
        "candidates":[],
        "unavailableCandidates":[],
        "manualProviders":[{"id":"deepseek","brandId":"deepseek","label":"DeepSeek API key","hint":"Connect DeepSeek"}],
        "authOptions":[
          {"id":"openai","brandId":"openai","label":"ChatGPT Login","hint":"Browser sign-in","kind":"oauth","featured":true},
          {"id":"openai-codex","brandId":"openai","label":"Continue with ChatGPT","hint":"Use your ChatGPT subscription","kind":"device-code","featured":false}
        ],
        "workspace":"/workspace",
        "setupComplete":false
      }
      """.trimIndent()

    private fun configured(modelRef: String = "openai-codex/gpt-5.6") = """{"candidates":[],"unavailableCandidates":[],"manualProviders":[],"authOptions":[],"workspace":"/workspace","configuredModel":"$modelRef","setupComplete":true}"""

    private fun openAiModels() = """{"models":[{"id":"gpt-5.6","name":"GPT-5.6","provider":"openai-codex","available":true,"reasoning":true},{"id":"gpt-5.6-mini","name":"GPT-5.6 mini","provider":"openai-codex","available":true,"reasoning":true}],"providerOutcomes":[]}"""

    private fun deepSeekModels() = """{"models":[{"id":"deepseek-chat","name":"DeepSeek Chat","provider":"deepseek","available":false,"reasoning":false,"apiKeySupported":true},{"id":"deepseek-reasoner","name":"DeepSeek Reasoner","provider":"deepseek","available":false,"reasoning":true,"apiKeySupported":true}],"providerOutcomes":[]}"""

    private fun allModels() = """{"models":[{"id":"gpt-5.6","name":"GPT-5.6","provider":"openai-codex","available":true,"reasoning":true},{"id":"claude-sonnet","name":"Claude Sonnet","provider":"anthropic","available":true,"reasoning":true}],"providerOutcomes":[]}"""
  }
}
