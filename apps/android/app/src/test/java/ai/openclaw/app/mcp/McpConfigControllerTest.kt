package ai.openclaw.app.mcp

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpConfigControllerTest {
  @Test
  fun oauthConnectorWritesConfigThenRequiresTerminalAuthorization() =
    runTest {
      val transport = FakeTransport()
      val controller = McpConfigController(this, transport, Json { ignoreUnknownKeys = true })
      val notion =
        (pinnedConnectorSuggestions.first { it.id == "notion" }.action as McpConnectorAction.AddMcp).template

      controller.addConnector(notion)
      advanceUntilIdle()

      assertEquals(listOf("config.get", "config.patch", "config.get"), transport.calls.map { it.first })
      assertTrue(transport.calls[1].second.contains("\"baseHash\":\"before\""))
      val result = controller.feature.state.value.config.mutation as GatewayMcpMutationState.Succeeded
      assertTrue(result.message.contains("not complete"))
      assertTrue(result.message.contains("openclaw mcp login notion"))
    }

  @Test
  fun unknownWriteOutcomeReconcilesWithReadOnlyRequest() =
    runTest {
      val transport = FakeTransport(losePatchResponse = true)
      val controller = McpConfigController(this, transport, Json { ignoreUnknownKeys = true })
      val context7 =
        (pinnedConnectorSuggestions.first { it.id == "context7" }.action as McpConnectorAction.AddMcp).template

      controller.addConnector(context7)
      advanceUntilIdle()
      assertTrue(controller.feature.state.value.config.mutation is GatewayMcpMutationState.UnknownOutcome)

      transport.installedServer = "context7"
      controller.reconcileMutation()
      advanceUntilIdle()

      assertTrue(controller.feature.state.value.config.mutation is GatewayMcpMutationState.Succeeded)
      assertEquals(1, transport.calls.count { it.first == "config.patch" })
      assertEquals(2, transport.calls.count { it.first == "config.get" })
    }

  @Test
  fun mutationRequiresAdminAndBothConfigMethods() =
    runTest {
      val transport = FakeTransport().apply { admin = false }
      val controller = McpConfigController(this, transport, Json { ignoreUnknownKeys = true })
      val context7 =
        (pinnedConnectorSuggestions.first { it.id == "context7" }.action as McpConnectorAction.AddMcp).template

      controller.addConnector(context7)
      advanceUntilIdle()

      assertTrue(controller.feature.state.value.config.mutation is GatewayMcpMutationState.Failed)
      assertTrue(transport.calls.isEmpty())
    }

  private class FakeTransport(
    private val losePatchResponse: Boolean = false,
  ) : McpConfigTransport {
    val calls = mutableListOf<Pair<String, String>>()
    var admin = true
    var installedServer: String? = null
    private val epoch = McpGatewayEpoch("gateway", 1, 1)

    override fun captureEpoch(): McpGatewayEpoch = epoch

    override fun isCurrent(epoch: McpGatewayEpoch): Boolean = epoch == this.epoch

    override fun isConnected(): Boolean = true

    override fun hasAdminScope(): Boolean = admin

    override fun canReadConfig(): Boolean = true

    override fun canPatchConfig(): Boolean = true

    override suspend fun request(
      epoch: McpGatewayEpoch,
      method: String,
      paramsJson: String,
      timeoutMs: Long,
    ): String {
      calls += method to paramsJson
      return when (method) {
        "config.get" -> snapshot(installedServer)
        "config.patch" -> {
          if (losePatchResponse) throw GatewayRequestOutcomeUnknown("response lost")
          installedServer = if (paramsJson.contains("notion")) "notion" else "context7"
          "{}"
        }
        else -> error("Unexpected method $method")
      }
    }

    private fun snapshot(server: String?): String =
      if (server == null) {
        """{"hash":"before","sourceConfig":{"mcp":{"servers":{}}}}"""
      } else {
        val template =
          (pinnedConnectorSuggestions.first { it.id == server }.action as McpConnectorAction.AddMcp).template
        """{"hash":"after","sourceConfig":{"mcp":{"servers":{"$server":{"url":"${template.url}","transport":"streamable-http"${if (template.auth == null) "" else ",\"auth\":\"${template.auth}\""}}}}}}"""
      }
  }
}
