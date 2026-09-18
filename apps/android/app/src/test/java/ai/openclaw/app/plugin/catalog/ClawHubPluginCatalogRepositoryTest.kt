package ai.openclaw.app.plugin.catalog

import ai.openclaw.app.clawhub.ClawHubApiClient
import ai.openclaw.app.clawhub.ClawHubApiError
import ai.openclaw.app.clawhub.ClawHubApiResult
import ai.openclaw.app.clawhub.ClawHubDocument
import ai.openclaw.app.clawhub.ClawHubGetRequest
import ai.openclaw.app.clawhub.ClawHubJson
import ai.openclaw.app.clawhub.ClawHubResponseSource
import ai.openclaw.app.plugin.GatewayPluginCatalogEntry
import ai.openclaw.app.plugin.GatewayPluginState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawHubPluginCatalogRepositoryTest {
  @Test
  fun recommendedRequestsOfficialOrderAndDropsNonOfficialOrMalformedEntries() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "items": [
                {
                  "name": "@openclaw/whatsapp",
                  "displayName": "WhatsApp",
                  "family": "code-plugin",
                  "isOfficial": true,
                  "categories": [" channels ", "channels", ""],
                  "topics": null,
                  "runtimeId": "whatsapp",
                  "stats": {"downloads": 10}
                },
                {"name": "community", "family": "code-plugin", "isOfficial": false},
                {"family": "code-plugin", "isOfficial": true},
                {"name": "wrong-family", "family": "skill", "isOfficial": true}
              ],
              "nextCursor": " next "
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubPluginCatalogRepository(api)

      val result = repository.recommended(cursor = " page/2 ", limit = 500)

      val page = (result as PluginCatalogResult.Success).value
      assertEquals(listOf("@openclaw/whatsapp"), page.items.map { it.packageName.value })
      assertEquals(listOf("channels"), page.items.single().categories)
      assertEquals(emptyList<String>(), page.items.single().topics)
      assertEquals("next", page.nextCursor)
      assertEquals(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "plugins"),
          queryParameters =
            listOf(
              "sort" to "recommended",
              "isOfficial" to "true",
              "limit" to "100",
              "cursor" to "page/2",
            ),
        ),
        api.requests.single(),
      )
    }

  @Test
  fun searchHasNoNetworkSideEffectForBlankQueryAndRefiltersOfficialResults() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "results": [
                {"score": 9, "package": {"name": "official", "isOfficial": true}},
                {"score": 8, "package": {"name": "community", "isOfficial": false}},
                {"score": 7}
              ]
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubPluginCatalogRepository(api)

      val blank = repository.search("  ") as PluginCatalogResult.Success
      val result = repository.search(" Whats App ", limit = 0) as PluginCatalogResult.Success

      assertEquals(emptyList<OfficialPluginSearchMatch>(), blank.value)
      assertEquals(
        "official",
        result.value
          .single()
          .plugin.packageName.value,
      )
      assertEquals(9.0, result.value.single().score)
      assertEquals(
        listOf("q" to "Whats App", "isOfficial" to "true", "limit" to "1"),
        api.requests.single().queryParameters,
      )
    }

  @Test
  fun detailMapsCandidateMetadataWithoutClaimingLocalInstallation() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "package": {
                "name": "@openclaw/whatsapp",
                "displayName": "WhatsApp",
                "family": "code-plugin",
                "isOfficial": true,
                "latestVersion": "2026.8.2",
                "compatibility": {
                  "builtWithOpenClawVersion": "2026.8.2",
                  "minGatewayVersion": ">=2026.4.25",
                  "pluginApiRange": ">=2026.8.2"
                },
                "artifact": {"kind": "npm-pack", "sha256": "digest", "size": 132291},
                "verification": {
                  "tier": "source-linked",
                  "scanStatus": "clean",
                  "sourceRepo": "openclaw/openclaw",
                  "trustedOpenClawPlugin": true
                }
              },
              "owner": {"handle": "openclaw", "displayName": "OpenClaw"}
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubPluginCatalogRepository(api)
      val id = PluginPackageName("@openclaw/whatsapp")

      val detail = (repository.detail(id) as PluginCatalogResult.Success).value

      assertEquals("WhatsApp", detail.plugin.displayName)
      assertEquals("OpenClaw", detail.owner?.displayName)
      assertEquals(">=2026.4.25", detail.compatibility?.minimumGatewayVersion)
      assertEquals("digest", detail.artifact?.sha256)
      assertTrue(detail.verification?.trustedOpenClawPlugin == true)
      assertEquals(
        listOf("api", "v1", "packages", "@openclaw/whatsapp"),
        api.requests.single().pathSegments,
      )
    }

  @Test
  fun readinessVersionsAndSecurityRemainCatalogMetadata() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "package": {"name": "@openclaw/whatsapp", "isOfficial": true},
              "ready": true,
              "checks": [{"id": "scan", "status": "pass", "message": "Clean"}],
              "blockers": []
            }
            """.trimIndent(),
          ),
          success(
            """
            {
              "items": [{"version": "2026.8.2", "distTags": ["latest", "latest"]}],
              "nextCursor": "older"
            }
            """.trimIndent(),
          ),
          success(
            """
            {
              "overview": "Review access before installing.",
              "securityAuditUrl": "https://clawhub.ai/audit",
              "release": {"version": "2026.8.2", "artifactSha256": "digest"},
              "trust": {
                "scanStatus": "clean",
                "blockedFromDownload": false,
                "reasons": [],
                "pending": false,
                "stale": false
              }
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubPluginCatalogRepository(api)
      val id = PluginPackageName("@openclaw/whatsapp")

      val readiness = (repository.readiness(id) as PluginCatalogResult.Success).value
      val versions = (repository.versions(id) as PluginCatalogResult.Success).value
      val security = (repository.security(id, "2026.8.2") as PluginCatalogResult.Success).value

      assertTrue(readiness.ready)
      assertEquals("scan", readiness.checks.single().id)
      assertEquals("2026.8.2", versions.items.single().version)
      assertEquals(listOf("latest"), versions.items.single().distributionTags)
      assertEquals("digest", security.artifactSha256)
      assertFalse(security.blockedFromDownload)
      assertEquals(
        "security",
        api.requests
          .last()
          .pathSegments
          .last(),
      )
    }

  @Test
  fun mapsTransportFailuresAndRejectsNonOfficialDetail() =
    runTest {
      val api =
        RecordingClawHubApi(
          ClawHubApiResult.Failure(ClawHubApiError.RateLimited(15)),
          success("""{"package":{"name":"community","isOfficial":false}}"""),
        )
      val repository = ClawHubPluginCatalogRepository(api)

      val rateLimited = repository.recommended() as PluginCatalogResult.Failure
      val detail = repository.detail(PluginPackageName("community")) as PluginCatalogResult.Failure

      assertEquals(PluginCatalogFailure.RateLimited(15), rateLimited.reason)
      assertEquals(PluginCatalogFailure.NotOfficial, detail.reason)
    }

  @Test
  fun overlaysGatewayStateByPackageBeforeRuntimeId() {
    val catalog =
      listOf(
        plugin("@openclaw/whatsapp", runtimeId = "whatsapp"),
        plugin("@openclaw/matrix", runtimeId = "matrix"),
        plugin("@openclaw/missing", runtimeId = "missing"),
      )
    val gateway =
      listOf(
        gatewayPlugin(id = "wrong-runtime", packageName = "@openclaw/whatsapp", enabled = true),
        gatewayPlugin(id = "matrix", packageName = null, enabled = false),
      )

    val result = overlayPluginLocalState(catalog, gateway)

    val whatsapp = result[0].local as PluginLocalState.Installed
    val matrix = result[1].local as PluginLocalState.Installed
    assertEquals("wrong-runtime", whatsapp.gatewayPluginId)
    assertTrue(whatsapp.enabled)
    assertEquals("matrix", matrix.gatewayPluginId)
    assertFalse(matrix.enabled)
    assertEquals(PluginLocalState.NotInstalled, result[2].local)
  }

  private fun plugin(
    packageName: String,
    runtimeId: String?,
  ): OfficialPluginSummary =
    OfficialPluginSummary(
      packageName = PluginPackageName(packageName),
      displayName = packageName,
      summary = null,
      family = "code-plugin",
      channel = "official",
      iconUrl = null,
      latestVersion = null,
      ownerHandle = "openclaw",
      runtimeId = runtimeId,
      categories = emptyList(),
      topics = emptyList(),
      verificationTier = null,
      downloads = null,
      installs = null,
      stars = null,
      updatedAt = null,
    )

  private fun gatewayPlugin(
    id: String,
    packageName: String?,
    enabled: Boolean,
  ): GatewayPluginCatalogEntry =
    GatewayPluginCatalogEntry(
      id = id,
      name = id,
      packageName = packageName,
      description = null,
      version = "1.0.0",
      kinds = emptyList(),
      origin = "clawhub",
      installed = true,
      enabled = enabled,
      state = if (enabled) GatewayPluginState.Enabled else GatewayPluginState.Disabled,
      featured = false,
      featuredAt = null,
      order = null,
      hasIcon = false,
      category = null,
      removable = true,
      install = null,
      error = null,
    )

  private class RecordingClawHubApi(
    vararg results: ClawHubApiResult,
  ) : ClawHubApiClient {
    val requests = mutableListOf<ClawHubGetRequest>()
    private val results = ArrayDeque(results.toList())

    override suspend fun get(request: ClawHubGetRequest): ClawHubApiResult {
      requests += request
      return results.removeFirst()
    }
  }

  private companion object {
    fun success(json: String): ClawHubApiResult.Success =
      ClawHubApiResult.Success(
        ClawHubDocument(
          body = ClawHubJson.parseToJsonElement(json).jsonObject,
          source = ClawHubResponseSource.Network,
        ),
      )
  }
}
