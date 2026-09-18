package ai.openclaw.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProtocolTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun setupDetectionKeepsGatewayOwnedChoicesAndPresentationMetadata() {
    val result =
      parseAiSetupDetection(
        """
        {
          "candidates":[{"kind":"codex-cli","brandId":"openai","label":"ChatGPT","detail":"Use subscription","modelRef":"openai/gpt","recommended":true,"credentials":true,"icon":"https://example.com/openai.png"}],
          "unavailableCandidates":[{"id":"provider-auto:deepseek","brandId":"deepseek","label":"DeepSeek","reason":"Install provider"}],
          "manualProviders":[{"id":"deepseek","brandId":"deepseek","label":"DeepSeek","hint":"API key"}],
          "authOptions":[{"id":"openai-codex","brandId":"openai","label":"Sign in","kind":"oauth","featured":true}],
          "workspace":"/home/user/.openclaw/workspace",
          "configuredModel":null,
          "setupComplete":false
        }
        """.trimIndent(),
        json,
      )

    requireNotNull(result)
    assertEquals("codex-cli", result.candidates.single().id)
    assertEquals(AiSetupAuthKind.OAuth, result.authOptions.single().kind)
    assertEquals("provider-auto:deepseek", result.unavailableCandidates.single().id)
    assertFalse(result.setupComplete)
  }

  @Test
  fun setupDetectionRejectsUnknownAuthKindsAndNonHttpsArtwork() {
    val base =
      """{"candidates":[],"unavailableCandidates":[],"manualProviders":[],"authOptions":[{"id":"x","label":"X","kind":"magic","featured":false}],"workspace":"/tmp","setupComplete":false}"""
    assertNull(parseAiSetupDetection(base, json))
    assertNull(
      parseAiSetupDetection(
        """{"candidates":[{"kind":"x","label":"X","detail":"X","recommended":false,"icon":"http://unsafe"}],"manualProviders":[],"workspace":"/tmp","setupComplete":false}""",
        json,
      ),
    )
  }

  @Test
  fun wizardStartOwnsTheSessionBeforeNextReturnsADeviceCodeStep() {
    val start =
      parseGatewayWizardStartResult(
        """{"sessionId":"setup-1","done":false,"status":"running"}""",
        json,
      )
    val next =
      parseGatewayWizardNextResult(
        """{"done":false,"status":"running","step":{"id":"login","type":"note","title":"Sign in","externalUrl":"https://auth.example/device","deviceCode":{"code":"ABCD-EFGH","expiresInMinutes":10,"message":"Open browser"}}}""",
        json,
      )

    requireNotNull(start)
    requireNotNull(next)
    assertEquals("setup-1", start.sessionId)
    assertNull(start.step)
    assertEquals("https://auth.example/device", next.step?.externalUrl)
    assertEquals("ABCD-EFGH", next.step?.deviceCode?.code)
    assertEquals("""{"sessionId":"setup-1"}""", wizardNextParams("setup-1"))
  }

  @Test
  fun completedWizardCarriesActivationAndRestartRequirement() {
    val result =
      parseGatewayWizardNextResult(
        """{"done":true,"status":"done","preparedModelRef":"deepseek/chat","modelActivation":{"modelRef":"deepseek/chat","gatewayRestartRequired":true}}""",
        json,
      )

    requireNotNull(result)
    assertTrue(result.done)
    assertEquals("deepseek/chat", result.modelActivation?.modelRef)
    assertTrue(result.modelActivation?.gatewayRestartRequired == true)
  }

  @Test
  fun modelCatalogRetainsAvailabilityThinkingAndProviderOutcomes() {
    val result =
      parseModelCatalog(
        """
        {
          "models":[{"id":"deepseek/chat","name":"Chat","provider":"deepseek","available":false,"unavailableReason":"missing-auth","reasoning":true,"supportsTools":true,"apiKeySupported":true,"tags":["recommended"],"thinkingLevels":[{"id":"off","label":"Off"},{"id":"high","label":"High"}],"thinkingDefault":"high"}],
          "refreshFailed":false,
          "providerOutcomes":[{"provider":"deepseek","status":"missing-auth"}]
        }
        """.trimIndent(),
        json,
      )

    requireNotNull(result)
    assertEquals(ModelUnavailableReason.MissingAuth, result.models.single().unavailableReason)
    assertEquals(
      listOf("off", "high"),
      result.models
        .single()
        .thinkingLevels
        .map { it.id },
    )
    assertEquals("high", result.models.single().thinkingDefault)
    assertEquals("missing-auth", result.providerOutcomes.single().status)
  }

  @Test
  fun setupAndRestartRequestsUseOnlyGatewayContractFields() {
    val activation =
      json
        .parseToJsonElement(
          aiSetupActivateStartParams(
            sessionId = "activation-1",
            activation = AiSetupActivation("deepseek", "temporary-secret", "deepseek/chat"),
            agentId = "main",
            workspace = "/workspace",
            nativeSessionCatalogsEnabled = false,
          ),
        ).jsonObject
    assertEquals("api-key", activation.getValue("kind").jsonPrimitive.content)
    assertEquals("deepseek", activation.getValue("authChoice").jsonPrimitive.content)
    assertEquals("temporary-secret", activation.getValue("apiKey").jsonPrimitive.content)
    assertEquals("deepseek/chat", activation.getValue("modelRef").jsonPrimitive.content)
    assertFalse(activation.containsKey("reviewToken"))

    val modelUpdate = json.parseToJsonElement(agentsUpdateModelParams("main", "deepseek/chat")).jsonObject
    assertEquals("main", modelUpdate.getValue("agentId").jsonPrimitive.content)
    assertEquals("deepseek/chat", modelUpdate.getValue("model").jsonPrimitive.content)

    val restart = json.parseToJsonElement(gatewayRestartRequestParams("provider installed")).jsonObject
    assertEquals("provider installed", restart.getValue("reason").jsonPrimitive.content)
    assertFalse(restart.getValue("skipDeferral").jsonPrimitive.boolean)
  }

  @Test
  fun restartResponsesRemainTyped() {
    val preflight = parseGatewayRestartPreflight("""{"safe":false,"counts":{"totalActive":2},"summary":"2 active tasks"}""", json)
    val request =
      parseGatewayRestartRequestResult(
        """{"ok":true,"status":"deferred","preflight":{"safe":false,"counts":{"totalActive":2},"summary":"2 active tasks"}}""",
        json,
      )

    assertEquals(2, preflight?.totalActive)
    assertEquals(GatewayRestartRequestStatus.Deferred, request?.status)
  }
}
