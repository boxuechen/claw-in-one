package ai.openclaw.app.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginProtocolTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun catalogParserPreservesLifecycleAndInstallIdentity() {
    val summary =
      parsePluginCatalog(
        """{
          "plugins":[{
            "id":"workboard","name":"Workboard","packageName":"@openclaw/workboard",
            "kind":["tool","skills"],"installed":false,"enabled":false,
            "state":"not-installed","featured":true,"featuredAt":42,"order":3.5,"hasIcon":true,
            "install":{"source":"official","pluginId":"workboard"},"removable":false
          }],
          "diagnostics":[{"level":"warn"}],"mutationAllowed":true
        }""",
        json,
      )

    val plugin = summary.plugins.single()
    assertEquals(1, summary.diagnosticsCount)
    assertTrue(summary.mutationAllowed)
    assertEquals(GatewayPluginInstallAction.Official("workboard"), plugin.install)
    assertEquals(GatewayPluginState.NotInstalled, plugin.state)
    assertEquals(listOf("tool", "skills"), plugin.kinds)
    assertEquals(42L, plugin.featuredAt)
    assertEquals(3.5, plugin.order ?: 0.0, 0.0)
    assertTrue(plugin.hasIcon)
  }

  @Test
  fun searchParserKeepsPackageChannelAndVerification() {
    val results =
      parsePluginSearchResults(
        """{
          "results":[{"score":0.91,"package":{
            "name":"@acme/calendar","displayName":"Calendar","family":"bundle-plugin",
            "channel":"community","isOfficial":false,"summary":"Calendar tools",
            "latestVersion":"2.0.0","downloads":1200,"verificationTier":"verified"
          }}]
        }""",
        json,
      )

    assertEquals("@acme/calendar", results.single().packageName)
    assertEquals("community", results.single().channel)
    assertEquals(1200L, results.single().downloads)
    assertFalse(results.single().official)
  }

  @Test
  fun inspectParserKeepsSecurityContract() {
    val inspection =
      parsePluginInspection(
        """{
          "ok":true,
          "plugin":{"id":"workboard","name":"Workboard","version":"1.2.0","installed":true,"enabled":false},
          "source":{"kind":"clawhub","packageName":"@openclaw/workboard","integrity":"sha512-example","integrityKind":"ssri"},
          "declared":{"channels":[],"providers":[],"tools":["workboard.plan"],"contracts":[],
            "hooks":[],"mcpServers":[],"cliCommands":[],"cliBackends":[],"skills":["planning"],
            "dangerousConfigFlags":[]},
          "reviewToken":"transient-token",
          "grants":{"hooks":{"allowPromptInjection":{"effective":false},
            "allowConversationAccess":{"effective":true,"configured":true}},
            "llm":{"allowModelOverride":false,"allowedModels":["deepseek-chat"]}},
          "trust":{"disposition":"review-recommended","reasons":["community package"],"pending":false,"stale":true}
        }""",
        json,
      )

    requireNotNull(inspection)
    assertEquals("transient-token", inspection.reviewToken)
    assertEquals(listOf("workboard.plan"), inspection.declared.tools)
    assertEquals(true, inspection.grants.allowConversationAccess.configured)
    assertEquals(GatewayPluginTrustDisposition.ReviewRecommended, inspection.trust?.disposition)
    assertTrue(inspection.trust?.stale == true)
  }

  @Test
  fun challengeParserRecognizesCapabilityAndPolicyContracts() {
    val capability =
      parsePluginMutationChallenge(
        """{"capabilityConsentCode":"PLUGIN_CAPABILITY_CONSENT_REQUIRED",
          "pluginId":"workboard","reviewToken":"token with wire whitespace ",
          "widened":{"tools":["workboard.delete"]}}""",
        json,
      ) as PluginMutationChallenge.CapabilityConsent
    val policy =
      parsePluginMutationChallenge(
        """{"installPolicyCode":"install_policy_warning_acknowledgement_required",
          "targetName":"@acme/calendar","targetType":"plugin","requestMode":"install",
          "reason":"Review package findings.","findings":[{"ruleId":"dynamic-code",
          "severity":"warn","message":"Dynamic code","file":"index.js","line":12}]}""",
        json,
      ) as PluginMutationChallenge.InstallPolicy

    assertEquals("token with wire whitespace ", capability.value.reviewToken)
    assertEquals(listOf("workboard.delete"), capability.value.widened?.tools)
    assertEquals(
      PluginInstallPolicySeverity.Warning,
      policy.value.findings
        .single()
        .severity,
    )
    assertNull(parsePluginMutationChallenge("{}", json))
    assertNull(
      parsePluginMutationChallenge(
        """{"capabilityConsentCode":"PLUGIN_CAPABILITY_CONSENT_REQUIRED",
          "pluginId":"workboard","reviewToken":"token","widened":{"unknown":["value"]}}""",
        json,
      ),
    )
    assertNull(
      parsePluginMutationChallenge(
        """{"installPolicyCode":"install_policy_warning_acknowledgement_required",
          "targetName":"workboard","targetType":"plugin","requestMode":"install","reason":"Review",
          "findings":[{"ruleId":"rule","severity":"invalid","message":"Problem"}]}""",
        json,
      ),
    )
  }

  @Test
  fun requestBuildersMatchGatewayProtocol() {
    val install =
      json
        .parseToJsonElement(
          pluginInstallParams(
            GatewayPluginInstallAction.ClawHub("@acme/calendar"),
            version = "2.0.0",
            acknowledgeInstallPolicyWarning = true,
            capabilityReviewToken = "review-token",
          ),
        ).jsonObject

    assertEquals("clawhub", install.getValue("source").jsonPrimitive.content)
    assertEquals("@acme/calendar", install.getValue("packageName").jsonPrimitive.content)
    assertEquals(
      "review-token",
      install
        .getValue("acknowledgeCapabilities")
        .jsonObject
        .getValue("reviewToken")
        .jsonPrimitive.content,
    )
    val search = json.parseToJsonElement(pluginSearchParams("  calendar  ")).jsonObject
    assertEquals("calendar", search.getValue("query").jsonPrimitive.content)
    assertEquals("20", search.getValue("limit").jsonPrimitive.content)

    val enable =
      json
        .parseToJsonElement(
          pluginSetEnabledParams(
            pluginId = " workboard ",
            enabled = true,
            capabilityReviewToken = "review-token",
          ),
        ).jsonObject
    assertEquals("workboard", enable.getValue("pluginId").jsonPrimitive.content)
    assertEquals("true", enable.getValue("enabled").jsonPrimitive.content)
    assertEquals(
      "review-token",
      enable
        .getValue("acknowledgeCapabilities")
        .jsonObject
        .getValue("reviewToken")
        .jsonPrimitive
        .content,
    )
    val uninstall = json.parseToJsonElement(pluginUninstallParams(" workboard ")).jsonObject
    assertEquals("workboard", uninstall.getValue("pluginId").jsonPrimitive.content)
  }

  @Test
  fun mutationParsersRejectIncompleteLifecycleReceipts() {
    assertNull(
      parsePluginMutationResult(
        """{"ok":true,"plugin":${catalogEntry()},"warnings":[]}""",
        json,
      ),
    )
    assertNull(
      parsePluginUninstallResult(
        """{"ok":true,"pluginId":"workboard","restartRequired":true}""",
        json,
      ),
    )
    val uninstall =
      parsePluginUninstallResult(
        """{"ok":true,"pluginId":"workboard","restartRequired":true,"removed":["directory"]}""",
        json,
      )
    assertEquals(listOf("directory"), uninstall?.removed)
  }

  @Test
  fun gatewayCapabilitiesRemainIndependent() {
    val inventory = pluginGatewayCapabilities(setOf("plugins.list"))
    val mutation =
      pluginGatewayCapabilities(
        setOf("plugins.inspect", "plugins.install", "plugins.setEnabled", "plugins.uninstall"),
      )

    assertTrue(inventory.inventory)
    assertFalse(inventory.search)
    assertFalse(inventory.lifecycleMutation)
    assertTrue(mutation.inspect)
    assertTrue(mutation.lifecycleMutation)
    assertFalse(mutation.inventory)
  }

  private fun catalogEntry(): String =
    """{"id":"workboard","name":"Workboard","installed":true,"enabled":false,
      "state":"disabled","featured":false,"hasIcon":false,"removable":true}"""
}
