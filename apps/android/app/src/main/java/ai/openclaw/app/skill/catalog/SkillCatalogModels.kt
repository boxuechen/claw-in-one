package ai.openclaw.app.skill.catalog

import ai.openclaw.app.GatewaySkillSummary

internal data class OfficialSkillIdentity(
  val ownerHandle: String,
  val slug: String,
) {
  init {
    requireIdentifierPart("Skill owner", ownerHandle)
    requireIdentifierPart("Skill slug", slug)
  }

  val reference: String = "@$ownerHandle/$slug"
}

internal data class OfficialSkillSummary(
  val identity: OfficialSkillIdentity,
  val displayName: String,
  val summary: String?,
  val channel: String?,
  val iconUrl: String?,
  val latestVersion: String?,
  val categories: List<String>,
  val topics: List<String>,
  val verificationTier: String?,
  val downloads: Long?,
  val installs: Long?,
  val stars: Long?,
  val updatedAt: Long?,
)

internal data class OfficialSkillPage(
  val items: List<OfficialSkillSummary>,
  val nextCursor: String?,
)

internal data class OfficialSkillSearchMatch(
  val skill: OfficialSkillSummary,
  val score: Double?,
)

internal data class OfficialSkillDetail(
  val skill: OfficialSkillSummary,
  val description: String?,
  val ownerDisplayName: String?,
  val ownerImageUrl: String?,
  val version: String?,
  val license: String?,
  val supportedOperatingSystems: List<String>,
  val supportedSystems: List<String>,
)

internal data class SkillVersionPage(
  val items: List<SkillVersion>,
  val nextCursor: String?,
)

internal data class SkillVersion(
  val version: String,
  val createdAt: Long?,
  val changelog: String?,
  val license: String?,
)

internal sealed interface SkillCatalogResult<out T> {
  data class Success<T>(
    val value: T,
  ) : SkillCatalogResult<T>

  data class Failure(
    val reason: SkillCatalogFailure,
  ) : SkillCatalogResult<Nothing>
}

internal sealed interface SkillCatalogFailure {
  data class RateLimited(
    val retryAfterSeconds: Long?,
  ) : SkillCatalogFailure

  data class Http(
    val statusCode: Int,
  ) : SkillCatalogFailure

  data object Network : SkillCatalogFailure

  data object InvalidResponse : SkillCatalogFailure

  data object NotOfficial : SkillCatalogFailure
}

internal interface SkillCatalogRepository {
  suspend fun recommended(
    cursor: String? = null,
    limit: Int = DEFAULT_SKILL_PAGE_SIZE,
  ): SkillCatalogResult<OfficialSkillPage>

  suspend fun search(
    query: String,
    limit: Int = DEFAULT_SKILL_SEARCH_SIZE,
  ): SkillCatalogResult<List<OfficialSkillSearchMatch>>

  suspend fun detail(identity: OfficialSkillIdentity): SkillCatalogResult<OfficialSkillDetail>

  suspend fun versions(
    identity: OfficialSkillIdentity,
    cursor: String? = null,
    limit: Int = DEFAULT_SKILL_VERSION_PAGE_SIZE,
  ): SkillCatalogResult<SkillVersionPage>
}

internal sealed interface SkillLocalState {
  data object NotInstalled : SkillLocalState

  data class Installed(
    val skillKey: String,
    val enabled: Boolean,
    val eligible: Boolean,
    val blockedByAllowlist: Boolean,
    val blockedByAgentFilter: Boolean,
    val missingCount: Int,
    val version: String?,
    val source: String,
  ) : SkillLocalState
}

internal data class SkillCatalogSnapshot(
  val catalog: OfficialSkillSummary,
  val local: SkillLocalState,
)

internal fun overlaySkillLocalState(
  catalog: List<OfficialSkillSummary>,
  gatewaySkills: List<GatewaySkillSummary>,
): List<SkillCatalogSnapshot> =
  catalog.map { skill ->
    val installed = gatewaySkills.firstOrNull { it.matches(skill.identity) }
    SkillCatalogSnapshot(
      catalog = skill,
      local = installed?.toSkillLocalState() ?: SkillLocalState.NotInstalled,
    )
  }

private fun GatewaySkillSummary.matches(identity: OfficialSkillIdentity): Boolean {
  if (!clawHubValid) return false
  val installedIdentity =
    parseSkillReference(clawHubRequestedReference)
      ?: parseSkillReference(clawHubSlug)
      ?: clawHubSlug?.trim()?.takeIf(String::isNotEmpty)?.let { slug ->
        clawHubOwnerHandle?.trim()?.takeIf(String::isNotEmpty)?.let { owner ->
          owner to slug
        }
      }
      ?: return false
  return installedIdentity.first.equals(identity.ownerHandle, ignoreCase = true) &&
    installedIdentity.second.equals(identity.slug, ignoreCase = true)
}

private fun parseSkillReference(rawValue: String?): Pair<String, String>? {
  val value = rawValue?.trim()?.takeIf(String::isNotEmpty) ?: return null
  if (!value.startsWith("@")) return null
  val parts = value.drop(1).split('/')
  if (parts.size != 2 || parts.any(String::isBlank)) return null
  return parts[0] to parts[1]
}

private fun GatewaySkillSummary.toSkillLocalState(): SkillLocalState =
  SkillLocalState.Installed(
    skillKey = skillKey,
    enabled = !disabled,
    eligible = eligible,
    blockedByAllowlist = blockedByAllowlist,
    blockedByAgentFilter = blockedByAgentFilter,
    missingCount = missingCount,
    version = clawHubInstalledVersion,
    source = source,
  )

private fun requireIdentifierPart(
  label: String,
  value: String,
) {
  require(value.isNotBlank()) { "$label must not be blank." }
  require(value == value.trim()) { "$label must be normalized." }
  require(value.length <= MAX_SKILL_IDENTITY_PART_LENGTH) { "$label is too long." }
  require('/' !in value && '@' !in value) { "$label contains a reserved character." }
  require(value.none(Char::isISOControl)) { "$label contains a control character." }
}

internal const val DEFAULT_SKILL_PAGE_SIZE = 50
internal const val DEFAULT_SKILL_SEARCH_SIZE = 30
internal const val DEFAULT_SKILL_VERSION_PAGE_SIZE = 30
private const val MAX_SKILL_IDENTITY_PART_LENGTH = 128
