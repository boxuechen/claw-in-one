package ai.openclaw.app.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigProtocolTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun summaryProjectsTargetsWithoutCredentialsOrProcessSecrets() {
    val snapshot =
      parseMcpConfigSnapshot(
        """
        {
          "hash":"h1",
          "sourceConfig":{"mcp":{"servers":{
            "remote":{"url":"https://alice:secret@example.test/mcp?token=private","auth":"private","headers":{"Authorization":"Bearer private"}},
            "local":{"command":"npx","args":["--token","private"],"env":{"TOKEN":"private"}}
          }}}
        }
        """.trimIndent(),
        json,
      )

    val summary = checkNotNull(snapshot).toSummary()
    assertEquals("https://example.test/mcp?…", summary.servers.first { it.name == "remote" }.target)
    assertNull(summary.servers.first { it.name == "remote" }.auth)
    assertEquals("npx", summary.servers.first { it.name == "local" }.target)
    assertFalse(summary.toString().contains("secret"))
    assertFalse(summary.toString().contains("private"))
  }

  @Test
  fun patchUsesBaseHashAndRfc7396Nulls() {
    val snapshot =
      McpConfigSnapshot(
        hash = "base",
        servers = mapOf("docs" to buildJsonObject { put("url", JsonPrimitive("https://docs.test/mcp")) }),
      )

    val disable =
      buildMcpPatchParams(snapshot, GatewayMcpMutationIntent.SetEnabled("docs", enabled = false))
        as McpPatchBuildResult.Ready
    val disableParams = json.parseToJsonElement(disable.paramsJson).jsonObject
    assertEquals("base", disableParams.getValue("baseHash").jsonPrimitive.content)
    assertEquals(
      Json.parseToJsonElement("""{"mcp":{"servers":{"docs":{"enabled":false}}}}"""),
      Json.parseToJsonElement(disableParams.getValue("raw").jsonPrimitive.content),
    )

    val enable =
      buildMcpPatchParams(snapshot, GatewayMcpMutationIntent.SetEnabled("docs", enabled = true))
        as McpPatchBuildResult.Ready
    assertEquals(
      JsonNull,
      json
        .parseToJsonElement(
          json
            .parseToJsonElement(enable.paramsJson)
            .jsonObject
            .getValue("raw")
            .jsonPrimitive.content,
        ).jsonObject
        .getValue("mcp")
        .jsonObject
        .getValue("servers")
        .jsonObject
        .getValue("docs")
        .jsonObject
        .getValue("enabled"),
    )

    val remove =
      buildMcpPatchParams(snapshot, GatewayMcpMutationIntent.Remove("docs")) as McpPatchBuildResult.Ready
    assertEquals(
      JsonNull,
      json
        .parseToJsonElement(
          json
            .parseToJsonElement(remove.paramsJson)
            .jsonObject
            .getValue("raw")
            .jsonPrimitive.content,
        ).jsonObject
        .getValue("mcp")
        .jsonObject
        .getValue("servers")
        .jsonObject
        .getValue("docs"),
    )
  }

  @Test
  fun confirmationRequiresAuthoritativeServerState() {
    val missing = McpConfigSnapshot(hash = "h", servers = emptyMap())
    assertFalse(missing.confirms(GatewayMcpMutationIntent.SetEnabled("docs", enabled = true)))
    assertFalse(
      missing.confirms(
        GatewayMcpMutationIntent.Add(
          "docs",
          buildJsonObject { put("url", JsonPrimitive("https://docs.test/mcp")) },
          McpConnectorFollowUp.None,
        ),
      ),
    )
  }

  @Test
  fun pinnedConnectorCatalogMatchesUpstreamSnapshotShape() {
    assertEquals(28, pinnedConnectorSuggestions.size)
    assertEquals("notion", pinnedConnectorSuggestions.first().id)
    assertEquals("notes", pinnedConnectorSuggestions.last().id)
    val github = pinnedConnectorSuggestions.first { it.id == "github" }.action as McpConnectorAction.AddMcp
    assertEquals(McpConnectorFollowUp.Endpoint, github.template.followUp)
    assertNull(github.template.auth)
    assertTrue(pinnedConnectorSuggestions.first { it.id == "jira" }.action is McpConnectorAction.SearchClawHub)
  }
}
