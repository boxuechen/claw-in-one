package ai.openclaw.app.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCodecTest {
  private val codec = ApprovalCodec()

  @Test
  fun listPayloadDiscoversOnlyExactIdentityAndTiming() {
    val rows =
      codec.ids(
        """[{"approvalKind":"plugin","id":"plugin:one","request":{"private":"ignored"},"createdAtMs":10,"expiresAtMs":20}]""",
        ApprovalKind.Plugin,
      )

    assertEquals(listOf(ApprovalRow("plugin:one", ApprovalKind.Plugin, 10, 20)), rows)
  }

  @Test
  fun canonicalGetKeepsExecAndPluginPresentationsDistinct() {
    val exec = codec.get(getResult(execRecord()), "exec:one", ApprovalKind.Exec) as ApprovalSnapshot.Pending
    val plugin = codec.get(getResult(pluginRecord()), "plugin:one", ApprovalKind.Plugin) as ApprovalSnapshot.Pending

    assertEquals("printf safe", (exec.row.details as ApprovalDetails.Exec).command)
    val pluginDetails = plugin.row.details as ApprovalDetails.Plugin
    assertEquals("Publish release", pluginDetails.title)
    assertEquals("Verify in ClawInOne", pluginDetails.externalResolutionLabel)
    assertEquals(setOf("allow-once"), pluginDetails.externalDecisions)
    assertEquals(listOf("allow-once", "deny"), plugin.row.allowedDecisions)
    assertEquals(listOf("target" to "production", "visibility" to "restricted"), pluginDetails.scope)

    assertThrows(IllegalArgumentException::class.java) {
      codec.get(getResult(pluginRecord()), "plugin:one", ApprovalKind.Exec)
    }
  }

  @Test
  fun pluginTextLimitsCountUnicodeCodePoints() {
    val eightyEmoji = "🚀".repeat(80)
    val accepted = codec.get(getResult(pluginRecord(title = eightyEmoji)), "plugin:one", ApprovalKind.Plugin)
    assertTrue(accepted is ApprovalSnapshot.Pending)

    assertThrows(IllegalArgumentException::class.java) {
      codec.get(getResult(pluginRecord(title = "$eightyEmoji🚀")), "plugin:one", ApprovalKind.Plugin)
    }
  }

  @Test
  fun malformedReviewerDataFailsClosed() {
    assertThrows(IllegalArgumentException::class.java) {
      codec.get(getResult(pluginRecord().replace("\"severity\":\"critical\"", "\"severity\":\"urgent\"")), "plugin:one", ApprovalKind.Plugin)
    }
    assertThrows(IllegalArgumentException::class.java) {
      codec.get(getResult(pluginRecord().replace("\"visibility\":\"restricted\"", "\"visibility\":\"everyone\"")), "plugin:one", ApprovalKind.Plugin)
    }
    assertThrows(IllegalArgumentException::class.java) {
      codec.get(getResult(pluginRecord().replace("\"status\":\"pending\"", "\"status\":\"pending\",\"executionPlan\":\"private\"")), "plugin:one", ApprovalKind.Plugin)
    }
    assertThrows(IllegalStateException::class.java) {
      codec.get(getResult(pluginRecord().replace("\"detail\":\"artifact=app.apk\"", "\"detail\":null")), "plugin:one", ApprovalKind.Plugin)
    }
  }

  @Test
  fun canonicalResolutionRequiresMatchingKindDecisionAndTerminalShape() {
    val result = codec.resolve(resolveResult(pluginTerminal(), applied = true), "plugin:one", ApprovalKind.Plugin, "deny")
    assertTrue(result.applied)
    assertEquals(ApprovalStatus.Denied, result.approval.status)
    assertEquals("deny", result.approval.decision)

    assertThrows(IllegalArgumentException::class.java) {
      codec.resolve(resolveResult(pluginTerminal(), applied = true), "plugin:one", ApprovalKind.Exec, "deny")
    }
    assertThrows(IllegalArgumentException::class.java) {
      codec.resolve(resolveResult(pluginTerminal(), applied = true), "plugin:one", ApprovalKind.Plugin, "allow-once")
    }
    assertThrows(IllegalStateException::class.java) {
      codec.resolve(resolveResult(pluginRecord(), applied = true), "plugin:one", ApprovalKind.Plugin, "deny")
    }
  }

  @Test
  fun requestBuildersKeepExactIdAndTypedOwner() {
    assertEquals("""{"id":"plugin:🦞/percent%"}""", approvalGetParams("plugin:🦞/percent%"))
    assertEquals(
      """{"id":"plugin:🦞/percent%","kind":"plugin","decision":"deny"}""",
      approvalResolveParams("plugin:🦞/percent%", ApprovalKind.Plugin, "deny"),
    )
    assertFalse(validApprovalId("."))
    assertFalse(validApprovalId(".."))
    assertFalse(validApprovalId(String(charArrayOf('\uD800'))))
    assertTrue(validApprovalId("approval:🦞/percent%"))
  }

  private fun getResult(record: String): String = """{"approval":$record}"""

  private fun resolveResult(
    record: String,
    applied: Boolean,
  ): String = """{"applied":$applied,"approval":$record}"""

  private fun execRecord(): String = """{"id":"exec:one","urlPath":"/approve/exec%3Aone","createdAtMs":10,"expiresAtMs":10000,"presentation":{"kind":"exec","commandText":"printf safe","commandPreview":"printf","warningText":null,"host":"node","nodeId":"pixel","agentId":"main","allowedDecisions":["allow-once","allow-always","deny"]},"status":"pending","sourceSessionKey":"agent:main"}"""

  private fun pluginRecord(title: String = "Publish release"): String = """{"id":"plugin:one","urlPath":"/approve/plugin%3Aone","createdAtMs":10,"expiresAtMs":10000,"presentation":{"kind":"plugin","title":"$title","description":"Publish a reviewed build.","detail":"artifact=app.apk","severity":"critical","pluginId":"claw-in-one","toolName":"android_install","agentId":"main","scope":{"kind":"external-post","target":"production","visibility":"restricted"},"allowedDecisions":["allow-once","deny"],"externalResolution":{"label":"Verify in ClawInOne","decisions":["allow-once"]}},"status":"pending","sourceSessionKey":"agent:main"}"""

  private fun pluginTerminal(): String =
    pluginRecord()
      .replace("\"status\":\"pending\",\"sourceSessionKey\":\"agent:main\"", "\"status\":\"denied\",\"decision\":\"deny\",\"resolvedAtMs\":20,\"reason\":\"user\",\"source\":{\"agentId\":\"main\",\"sessionKey\":\"agent:main\"},\"resolver\":{\"kind\":\"device\",\"id\":\"pixel\"}")
}
