package ai.openclaw.app.skill.catalog

import ai.openclaw.app.GatewaySkillSummary
import ai.openclaw.app.clawhub.ClawHubApiClient
import ai.openclaw.app.clawhub.ClawHubApiError
import ai.openclaw.app.clawhub.ClawHubApiResult
import ai.openclaw.app.clawhub.ClawHubDocument
import ai.openclaw.app.clawhub.ClawHubGetRequest
import ai.openclaw.app.clawhub.ClawHubJson
import ai.openclaw.app.clawhub.ClawHubResponseSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClawHubSkillCatalogRepositoryTest {
  @Test
  fun recommendedUsesOfficialSkillPackageCatalogAndRequiresOwnerIdentity() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "items": [
                {
                  "name": "mcporter",
                  "ownerHandle": "steipete",
                  "displayName": "Mcporter",
                  "family": "skill",
                  "isOfficial": true,
                  "categories": [" other ", "other"],
                  "topics": []
                },
                {"name": "not-official", "ownerHandle": "other", "family": "skill", "isOfficial": false},
                {"name": "missing-owner", "family": "skill", "isOfficial": true},
                {"name": "wrong-family", "ownerHandle": "openclaw", "family": "code-plugin", "isOfficial": true}
              ],
              "nextCursor": "next"
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubSkillCatalogRepository(api)

      val page = (repository.recommended(cursor = " page ", limit = 500) as SkillCatalogResult.Success).value

      assertEquals(listOf("@steipete/mcporter"), page.items.map { it.identity.reference })
      assertEquals(listOf("other"), page.items.single().categories)
      assertEquals("next", page.nextCursor)
      assertEquals(
        listOf(
          "family" to "skill",
          "sort" to "recommended",
          "isOfficial" to "true",
          "limit" to "100",
          "cursor" to "page",
        ),
        api.requests.single().queryParameters,
      )
    }

  @Test
  fun searchIsSeparateFromPluginSearchAndRefiltersOfficialSkills() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "results": [
                {
                  "score": 11,
                  "package": {
                    "name": "mcporter",
                    "ownerHandle": "steipete",
                    "family": "skill",
                    "isOfficial": true
                  }
                },
                {
                  "score": 10,
                  "package": {
                    "name": "community",
                    "ownerHandle": "someone",
                    "family": "skill",
                    "isOfficial": false
                  }
                }
              ]
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubSkillCatalogRepository(api)

      val blank = repository.search(" ") as SkillCatalogResult.Success
      val result = repository.search(" MCP ") as SkillCatalogResult.Success

      assertEquals(emptyList<OfficialSkillSearchMatch>(), blank.value)
      assertEquals(
        "@steipete/mcporter",
        result.value
          .single()
          .skill.identity.reference,
      )
      assertEquals(
        listOf("q" to "MCP", "family" to "skill", "isOfficial" to "true", "limit" to "30"),
        api.requests.single().queryParameters,
      )
    }

  @Test
  fun detailConfirmsOfficialPublisherThenUsesOwnerScopedSkillEndpoint() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "package": {
                "name": "mcporter",
                "ownerHandle": "steipete",
                "displayName": "Mcporter",
                "summary": "MCP client workflow",
                "family": "skill",
                "channel": "official",
                "isOfficial": true,
                "latestVersion": "1.0.0"
              }
            }
            """.trimIndent(),
          ),
          success(
            """
            {
              "skill": {
                "slug": "mcporter",
                "displayName": "Mcporter",
                "description": "Full instructions"
              },
              "latestVersion": {"version": "1.0.0", "license": "MIT"},
              "metadata": {"os": ["linux"], "systems": ["openclaw"]},
              "owner": {
                "handle": "steipete",
                "displayName": "Peter Steinberger",
                "image": "https://example.com/avatar.png"
              }
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubSkillCatalogRepository(api)
      val identity = OfficialSkillIdentity(ownerHandle = "steipete", slug = "mcporter")

      val detail = (repository.detail(identity) as SkillCatalogResult.Success).value

      assertEquals("@steipete/mcporter", detail.skill.identity.reference)
      assertEquals("Full instructions", detail.description)
      assertEquals("Peter Steinberger", detail.ownerDisplayName)
      assertEquals("MIT", detail.license)
      assertEquals(listOf("linux"), detail.supportedOperatingSystems)
      assertEquals(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "packages", "mcporter"),
          queryParameters = listOf("ownerHandle" to "steipete"),
        ),
        api.requests[0],
      )
      assertEquals(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "skills", "mcporter"),
          queryParameters = listOf("ownerHandle" to "steipete"),
        ),
        api.requests[1],
      )
    }

  @Test
  fun nonOfficialDetailStopsBeforeRichSkillRequest() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "package": {
                "name": "skill",
                "ownerHandle": "community",
                "family": "skill",
                "isOfficial": false
              }
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubSkillCatalogRepository(api)

      val result =
        repository.detail(
          OfficialSkillIdentity(ownerHandle = "community", slug = "skill"),
        ) as SkillCatalogResult.Failure

      assertEquals(SkillCatalogFailure.NotOfficial, result.reason)
      assertEquals(1, api.requests.size)
    }

  @Test
  fun versionsAlwaysCarryOwnerHandleToAvoidAmbiguousSlugs() =
    runTest {
      val api =
        RecordingClawHubApi(
          success(
            """
            {
              "items": [{"version": "1.0.0", "license": "MIT"}],
              "nextCursor": null
            }
            """.trimIndent(),
          ),
        )
      val repository = ClawHubSkillCatalogRepository(api)
      val identity = OfficialSkillIdentity(ownerHandle = "steipete", slug = "mcporter")

      val result = (repository.versions(identity, limit = 0) as SkillCatalogResult.Success).value

      assertEquals("MIT", result.items.single().license)
      assertEquals(
        listOf("ownerHandle" to "steipete", "limit" to "1"),
        api.requests.single().queryParameters,
      )
    }

  @Test
  fun overlaysOnlyExactValidOwnerAndSlugFromGatewayStatus() {
    val official = skill(owner = "steipete", slug = "mcporter")
    val wrongOwner = gatewaySkill(reference = "@someone/mcporter", valid = true)
    val builtIn = gatewaySkill(reference = "@steipete/mcporter", valid = false)
    val installed =
      gatewaySkill(
        reference = "@steipete/mcporter",
        valid = true,
        disabled = true,
        missingCount = 2,
      )

    val unmatched = overlaySkillLocalState(listOf(official), listOf(wrongOwner, builtIn))
    val matched = overlaySkillLocalState(listOf(official), listOf(wrongOwner, installed))

    assertEquals(SkillLocalState.NotInstalled, unmatched.single().local)
    val state = matched.single().local as SkillLocalState.Installed
    assertFalse(state.enabled)
    assertEquals(2, state.missingCount)
    assertEquals("1.0.0", state.version)
  }

  @Test
  fun mapsTransportFailureIntoSkillCatalogDomain() =
    runTest {
      val repository =
        ClawHubSkillCatalogRepository(
          RecordingClawHubApi(
            ClawHubApiResult.Failure(ClawHubApiError.RateLimited(9)),
          ),
        )

      val result = repository.recommended() as SkillCatalogResult.Failure

      assertEquals(SkillCatalogFailure.RateLimited(9), result.reason)
    }

  private fun skill(
    owner: String,
    slug: String,
  ): OfficialSkillSummary =
    OfficialSkillSummary(
      identity = OfficialSkillIdentity(ownerHandle = owner, slug = slug),
      displayName = slug,
      summary = null,
      channel = "official",
      iconUrl = null,
      latestVersion = "1.0.0",
      categories = emptyList(),
      topics = emptyList(),
      verificationTier = null,
      downloads = null,
      installs = null,
      stars = null,
      updatedAt = null,
    )

  private fun gatewaySkill(
    reference: String,
    valid: Boolean,
    disabled: Boolean = false,
    missingCount: Int = 0,
  ): GatewaySkillSummary =
    GatewaySkillSummary(
      skillKey = "mcporter",
      name = "mcporter",
      description = null,
      source = "managed",
      emoji = null,
      disabled = disabled,
      eligible = missingCount == 0,
      blockedByAllowlist = false,
      blockedByAgentFilter = false,
      bundled = !valid,
      missingCount = missingCount,
      installCount = 1,
      clawHubSlug = reference,
      clawHubValid = valid,
      clawHubRequestedReference = reference,
      clawHubOwnerHandle = null,
      clawHubInstalledVersion = "1.0.0",
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
