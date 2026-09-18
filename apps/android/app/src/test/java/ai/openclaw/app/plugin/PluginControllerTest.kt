package ai.openclaw.app.plugin

import ai.openclaw.app.ai.AiGatewayConnection
import ai.openclaw.app.ai.AiGatewayTransport
import ai.openclaw.app.ai.GatewayRestartCoordinator
import ai.openclaw.app.ai.GatewayRestartState
import ai.openclaw.app.ai.GatewayRuntimeIdentity
import ai.openclaw.app.extensions.ExtensionGatewayConnection
import ai.openclaw.app.extensions.ExtensionGatewayTransport
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PluginControllerTest {
  @Test
  fun inspectionIsTokenFreeAndConnectionReplacementRetiresIt() =
    runTest {
      val transport = FakeTransport(backgroundScope)
      val controller = controller(transport)
      transport.handler = { method, _ ->
        assertEquals("plugins.inspect", method)
        inspectionPayload()
      }

      controller.feature.actions.inspect("workboard")
      runCurrent()
      val ready = controller.state.value.inspection as GatewayPluginInspectionState.Ready
      assertEquals(listOf("workboard.plan"), ready.details.declared.tools)

      transport.connection = transport.connection?.copy(catalogRevision = 2)
      controller.onConnectionChanged()
      assertEquals(GatewayPluginInspectionState.Idle, controller.state.value.inspection)
    }

  @Test
  fun installRequiresConfirmationAndAuthoritativeReadback() =
    runTest {
      val transport = FakeTransport(backgroundScope)
      val controller = controller(transport)
      seedCatalog(controller, transport)
      transport.handler = { method, _ ->
        when (method) {
          "plugins.install" -> installResult()
          "plugins.list" -> catalogResult(installed = true)
          else -> error("unexpected method $method")
        }
      }

      val intent = installIntent()
      controller.feature.actions.requestInstall(intent)
      assertEquals(GatewayPluginMutationState.Confirmation(intent), controller.state.value.mutation)
      controller.feature.actions.confirmMutation()
      controller.feature.actions.confirmMutation()
      runCurrent()

      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.Succeeded)
      assertEquals(listOf("plugins.install", "plugins.list"), transport.calls.takeLast(2).map { it.first })
      assertEquals(PLUGIN_MUTATION_REQUEST_TIMEOUT_MS, transport.timeouts["plugins.install"])
      assertTrue(
        controller.state.value.summary.plugins
          .single()
          .installed,
      )
    }

  @Test
  fun policyAndCapabilityChallengesRetryOnlyAfterConsent() =
    runTest {
      val transport = FakeTransport(backgroundScope)
      val controller = controller(transport)
      seedCatalog(controller, transport)
      val installCalls = AtomicInteger()
      val installParams = mutableListOf<String>()
      transport.handler = { method, params ->
        when (method) {
          "plugins.install" -> {
            installParams += params
            when (installCalls.incrementAndGet()) {
              1 -> throw GatewayRequestRejected(policyError())
              2 -> throw GatewayRequestRejected(capabilityError())
              else -> installResult()
            }
          }
          "plugins.inspect" -> inspectionPayload("inspection-token")
          "plugins.list" -> catalogResult(installed = true)
          else -> error("unexpected method $method")
        }
      }

      controller.feature.actions.requestInstall(installIntent())
      controller.feature.actions.confirmMutation()
      runCurrent()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.PolicyReview)

      controller.feature.actions.confirmInstallPolicy()
      runCurrent()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.CapabilityReview)

      controller.feature.actions.confirmCapabilities()
      runCurrent()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.Succeeded)
      val finalParams = Json.parseToJsonElement(installParams.last()).jsonObject
      assertEquals("true", finalParams.getValue("acknowledgeInstallPolicyWarning").jsonPrimitive.content)
      assertEquals(
        "inspection-token",
        finalParams
          .getValue("acknowledgeCapabilities")
          .jsonObject
          .getValue("reviewToken")
          .jsonPrimitive
          .content,
      )
    }

  @Test
  fun unknownOutcomeReconcilesWithoutRepeatingWrite() =
    runTest {
      val transport = FakeTransport(backgroundScope)
      val controller = controller(transport)
      seedCatalog(controller, transport)
      val installs = AtomicInteger()
      transport.handler = { method, _ ->
        when (method) {
          "plugins.install" -> {
            installs.incrementAndGet()
            throw GatewayRequestOutcomeUnknown("response lost")
          }
          "plugins.list" -> catalogResult(installed = true)
          else -> error("unexpected method $method")
        }
      }

      controller.feature.actions.requestInstall(installIntent())
      controller.feature.actions.confirmMutation()
      runCurrent()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.UnknownOutcome)

      controller.feature.actions.reconcileMutation()
      runCurrent()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.Succeeded)
      assertEquals(1, installs.get())
    }

  @Test
  fun enablementUsesRequestLocalReviewToken() =
    runTest {
      val transport = FakeTransport(backgroundScope)
      val controller = controller(transport)
      seedCatalog(controller, transport, installed = true)
      val calls = AtomicInteger()
      val params = mutableListOf<String>()
      transport.handler = { method, value ->
        when (method) {
          "plugins.setEnabled" -> {
            params += value
            if (calls.incrementAndGet() == 1) throw GatewayRequestRejected(capabilityError())
            setEnabledResult()
          }
          "plugins.inspect" -> inspectionPayload("enable-token")
          "plugins.list" -> catalogResult(installed = true, enabled = true)
          else -> error("unexpected method $method")
        }
      }

      controller.feature.actions.requestSetEnabled("workboard", "Workboard", true)
      runCurrent()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.CapabilityReview)

      controller.feature.actions.confirmCapabilities()
      runCurrent()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.Succeeded)
      assertEquals(
        "enable-token",
        Json
          .parseToJsonElement(params.last())
          .jsonObject
          .getValue("acknowledgeCapabilities")
          .jsonObject
          .getValue("reviewToken")
          .jsonPrimitive
          .content,
      )
    }

  @Test
  fun incompatibleApiErrorDropsTransportPayload() =
    runTest {
      val transport = FakeTransport(backgroundScope)
      val controller = controller(transport)
      seedCatalog(controller, transport)
      transport.handler = { method, _ ->
        check(method == "plugins.install")
        throw GatewayRequestRejected(
          GatewaySession.ErrorShape(
            code = "INVALID_REQUEST",
            message = "requires new runtime | {\"code\":\"incompatible_plugin_api\"}",
          ),
        )
      }

      controller.feature.actions.requestInstall(installIntent())
      controller.feature.actions.confirmMutation()
      runCurrent()

      val failure = controller.state.value.mutation as GatewayPluginMutationState.Failed
      assertEquals(
        "This Plugin requires a newer OpenClaw Runtime. Update OpenClaw, then try again.",
        failure.message,
      )
    }

  @Test
  fun restartRequiredMutationResumesOnlyAfterANewGatewayGenerationAndReadback() =
    runTest {
      val transport = FakeTransport(backgroundScope)
      val restartRuntime = FakeRestartRuntime()
      val restart =
        GatewayRestartCoordinator(
          scope = backgroundScope,
          transport = restartRuntime,
          identitySource = { restartRuntime.identity },
          json = Json,
        )
      val controller = controller(transport, restart)
      seedCatalog(controller, transport)
      transport.handler = { method, _ ->
        when (method) {
          "plugins.install" -> installResult(restartRequired = true)
          "plugins.list" -> catalogResult(installed = true)
          else -> error("unexpected method $method")
        }
      }

      controller.feature.actions.requestInstall(installIntent())
      controller.feature.actions.confirmMutation()
      runCurrent()

      val restarting = controller.state.value.mutation as GatewayPluginMutationState.Working
      assertEquals(GatewayPluginMutationStage.Restarting, restarting.stage)
      assertEquals(listOf("gateway.restart.preflight", "gateway.restart.request"), restartRuntime.calls)
      assertFalse(controller.state.value.mutation is GatewayPluginMutationState.Succeeded)

      transport.connection = null
      restartRuntime.identity = null
      controller.clear()
      restart.onRuntimeIdentityChanged()
      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.Working)

      transport.connection = transport.newConnection(generation = 2, catalogRevision = 2)
      restartRuntime.identity = restartRuntime.newIdentity(generation = 2)
      controller.onConnectionChanged()
      restart.onRuntimeIdentityChanged()
      runCurrent()

      assertTrue(controller.state.value.mutation is GatewayPluginMutationState.Succeeded)
      assertEquals(GatewayRestartState.Idle, restart.state.value)
      assertEquals(1, restartRuntime.calls.count { it == "gateway.restart.request" })
    }

  private fun controller(
    transport: FakeTransport,
    restart: GatewayRestartCoordinator = FakeRestartRuntime().coordinator(transport.scope),
  ): PluginController =
    PluginController(
      scope = transport.scope,
      transport = transport,
      json = Json { ignoreUnknownKeys = true },
      iconLoader = { _, _ -> null },
      restart = restart,
    ).also(PluginController::onConnectionChanged)

  private fun seedCatalog(
    controller: PluginController,
    transport: FakeTransport,
    installed: Boolean = false,
  ) {
    transport.handler = { method, _ ->
      check(method == "plugins.list")
      catalogResult(installed)
    }
    controller.feature.actions.refresh()
  }

  private fun installIntent() =
    GatewayPluginMutationIntent.Install(
      action = GatewayPluginInstallAction.Official("workboard"),
      displayName = "Workboard",
    )

  private class FakeTransport(
    val scope: CoroutineScope,
  ) : ExtensionGatewayTransport {
    var connection: ExtensionGatewayConnection? =
      newConnection(generation = 1, catalogRevision = 1)

    fun newConnection(
      generation: Long,
      catalogRevision: Long,
    ) = ExtensionGatewayConnection(
      stableId = "gateway",
      generation = generation,
      catalogRevision = catalogRevision,
      methods =
        setOf(
          "plugins.list",
          "plugins.search",
          "plugins.inspect",
          "plugins.install",
          "plugins.setEnabled",
          "plugins.uninstall",
        ),
      adminScope = true,
    )

    val calls = mutableListOf<Pair<String, String>>()
    val timeouts = mutableMapOf<String, Long>()
    var handler: suspend (String, String) -> String = { _, _ -> error("handler not set") }

    override fun capture(): ExtensionGatewayConnection? = connection

    override fun publish(
      connection: ExtensionGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (this.connection != connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: ExtensionGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      check(this.connection == connection)
      calls += method to params
      timeouts[method] = timeoutMs
      return handler(method, params)
    }
  }

  private class FakeRestartRuntime : AiGatewayTransport {
    private fun connection(generation: Long) =
      AiGatewayConnection(
        stableId = "gateway",
        generation = generation,
        catalogRevision = generation,
        methods = setOf("gateway.restart.preflight", "gateway.restart.request"),
        defaultAgentId = "main",
        adminScope = true,
      )

    var identity: GatewayRuntimeIdentity? = newIdentity(generation = 1)
    val calls = mutableListOf<String>()

    fun newIdentity(generation: Long) =
      GatewayRuntimeIdentity(
        supervisorBootId = "supervisor-1",
        supervisorGatewayGeneration = "gateway-$generation",
        connection = connection(generation),
      )

    fun coordinator(scope: CoroutineScope) =
      GatewayRestartCoordinator(
        scope = scope,
        transport = this,
        identitySource = { identity },
        json = Json,
      )

    override fun capture(): AiGatewayConnection? = identity?.connection

    override fun publish(
      connection: AiGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (capture() != connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: AiGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      check(capture() == connection)
      calls += method
      return when (method) {
        "gateway.restart.preflight" -> """{"safe":true,"counts":{"totalActive":0},"summary":"Ready"}"""
        "gateway.restart.request" -> """{"ok":true,"status":"scheduled","preflight":{"safe":true,"counts":{"totalActive":0},"summary":"Ready"}}"""
        else -> error("unexpected method $method")
      }
    }
  }

  private fun inspectionPayload(reviewToken: String = "secret-token"): String =
    """{"ok":true,"plugin":{"id":"workboard","name":"Workboard","installed":true,"enabled":false},
      "declared":{"channels":[],"providers":[],"tools":["workboard.plan"],"contracts":[],
      "hooks":[],"mcpServers":[],"cliCommands":[],"cliBackends":[],"skills":[],"dangerousConfigFlags":[]},
      "reviewToken":"$reviewToken","grants":{"hooks":{"allowPromptInjection":{"effective":false},
      "allowConversationAccess":{"effective":false}}}}"""

  private fun installResult(restartRequired: Boolean = false): String = """{"ok":true,"plugin":${pluginEntry(true)},"restartRequired":$restartRequired,"warnings":[]}"""

  private fun setEnabledResult(): String = """{"ok":true,"plugin":${pluginEntry(true, true)},"restartRequired":false,"warnings":[]}"""

  private fun catalogResult(
    installed: Boolean,
    enabled: Boolean = false,
  ): String = """{"plugins":[${pluginEntry(installed, enabled)}],"diagnostics":[],"mutationAllowed":true}"""

  private fun pluginEntry(
    installed: Boolean,
    enabled: Boolean = false,
  ): String =
    """{"id":"workboard","name":"Workboard","packageName":"@openclaw/workboard",
      "installed":$installed,"enabled":$enabled,
      "state":"${if (!installed) {
      "not-installed"
    } else if (enabled) {
      "enabled"
    } else {
      "disabled"
    }}",
      "install":{"source":"official","pluginId":"workboard"},"removable":$installed}"""

  private fun policyError() =
    GatewaySession.ErrorShape(
      code = "INVALID_REQUEST",
      message = "install requires policy review",
      rawDetailsJson =
        """{"installPolicyCode":"install_policy_warning_acknowledgement_required",
          "targetName":"workboard","targetType":"plugin","requestMode":"install",
          "reason":"Review findings before installing."}""",
    )

  private fun capabilityError() =
    GatewaySession.ErrorShape(
      code = "INVALID_REQUEST",
      message = "install requires capability review",
      rawDetailsJson =
        """{"capabilityConsentCode":"PLUGIN_CAPABILITY_CONSENT_REQUIRED",
          "pluginId":"workboard","reviewToken":"challenge-token","widened":{"tools":["workboard.plan"]}}""",
    )
}
