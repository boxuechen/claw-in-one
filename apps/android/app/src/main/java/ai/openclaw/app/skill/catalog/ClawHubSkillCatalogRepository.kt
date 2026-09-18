package ai.openclaw.app.skill.catalog

import ai.openclaw.app.clawhub.ClawHubApiClient
import ai.openclaw.app.clawhub.ClawHubApiError
import ai.openclaw.app.clawhub.ClawHubApiResult
import ai.openclaw.app.clawhub.ClawHubGetRequest
import ai.openclaw.app.clawhub.ClawHubJson
import ai.openclaw.app.clawhub.ClawHubPackageDetailWire
import ai.openclaw.app.clawhub.ClawHubPackagePageWire
import ai.openclaw.app.clawhub.ClawHubPackageWire
import ai.openclaw.app.clawhub.ClawHubSearchPageWire
import ai.openclaw.app.clawhub.ClawHubSkillDetailWire
import ai.openclaw.app.clawhub.ClawHubVersionsWire
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

internal class ClawHubSkillCatalogRepository(
  private val api: ClawHubApiClient,
) : SkillCatalogRepository {
  override suspend fun recommended(
    cursor: String?,
    limit: Int,
  ): SkillCatalogResult<OfficialSkillPage> {
    val query =
      mutableListOf(
        "family" to "skill",
        "sort" to "recommended",
        "isOfficial" to "true",
        "limit" to limit.apiLimit().toString(),
      )
    cursor.clean()?.let { query += "cursor" to it }
    return api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "packages"),
          queryParameters = query,
        ),
      ).decode(ClawHubPackagePageWire.serializer())
      .map { page ->
        OfficialSkillPage(
          items = page.items.mapNotNull(ClawHubPackageWire::toOfficialSkillSummary),
          nextCursor = page.nextCursor.clean(),
        )
      }
  }

  override suspend fun search(
    query: String,
    limit: Int,
  ): SkillCatalogResult<List<OfficialSkillSearchMatch>> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return SkillCatalogResult.Success(emptyList())
    return api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "packages", "search"),
          queryParameters =
            listOf(
              "q" to normalizedQuery,
              "family" to "skill",
              "isOfficial" to "true",
              "limit" to limit.apiLimit().toString(),
            ),
        ),
      ).decode(ClawHubSearchPageWire.serializer())
      .map { page ->
        page.results.mapNotNull { result ->
          result.packageValue?.toOfficialSkillSummary()?.let { skill ->
            OfficialSkillSearchMatch(skill = skill, score = result.score)
          }
        }
      }
  }

  override suspend fun detail(identity: OfficialSkillIdentity): SkillCatalogResult<OfficialSkillDetail> {
    val officialPackage = confirmOfficialPackage(identity)
    if (officialPackage is SkillCatalogResult.Failure) return officialPackage
    val summary = (officialPackage as SkillCatalogResult.Success).value
    return api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "skills", identity.slug),
          queryParameters = listOf("ownerHandle" to identity.ownerHandle),
        ),
      ).decode(ClawHubSkillDetailWire.serializer())
      .flatMap { detail ->
        val skill = detail.skill ?: return@flatMap SkillCatalogResult.Failure(SkillCatalogFailure.InvalidResponse)
        val owner = detail.owner
        if (
          skill.slug.clean() != identity.slug ||
          !owner?.handle.clean().equals(identity.ownerHandle, ignoreCase = true)
        ) {
          return@flatMap SkillCatalogResult.Failure(SkillCatalogFailure.InvalidResponse)
        }
        SkillCatalogResult.Success(
          OfficialSkillDetail(
            skill = summary,
            description = skill.description.clean(),
            ownerDisplayName = owner?.displayName.clean(),
            ownerImageUrl = owner?.image.clean(),
            version = detail.latestVersion?.version.clean() ?: summary.latestVersion,
            license = detail.latestVersion?.license.clean(),
            supportedOperatingSystems =
              detail.metadata
                ?.os
                .orEmpty()
                .cleanList(),
            supportedSystems =
              detail.metadata
                ?.systems
                .orEmpty()
                .cleanList(),
          ),
        )
      }
  }

  override suspend fun versions(
    identity: OfficialSkillIdentity,
    cursor: String?,
    limit: Int,
  ): SkillCatalogResult<SkillVersionPage> {
    val query =
      mutableListOf(
        "ownerHandle" to identity.ownerHandle,
        "limit" to limit.apiLimit().toString(),
      )
    cursor.clean()?.let { query += "cursor" to it }
    return api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "skills", identity.slug, "versions"),
          queryParameters = query,
        ),
      ).decode(ClawHubVersionsWire.serializer())
      .map { page ->
        SkillVersionPage(
          items =
            page.items.mapNotNull { version ->
              version.version.clean()?.let { value ->
                SkillVersion(
                  version = value,
                  createdAt = version.createdAt,
                  changelog = version.changelog.clean(),
                  license = version.license.clean(),
                )
              }
            },
          nextCursor = page.nextCursor.clean(),
        )
      }
  }

  private suspend fun confirmOfficialPackage(
    identity: OfficialSkillIdentity,
  ): SkillCatalogResult<OfficialSkillSummary> =
    api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "packages", identity.slug),
          queryParameters = listOf("ownerHandle" to identity.ownerHandle),
        ),
      ).decode(ClawHubPackageDetailWire.serializer())
      .flatMap { detail ->
        val summary =
          detail.packageValue?.toOfficialSkillSummary()
            ?: return@flatMap SkillCatalogResult.Failure(SkillCatalogFailure.NotOfficial)
        if (summary.identity != identity) {
          SkillCatalogResult.Failure(SkillCatalogFailure.InvalidResponse)
        } else {
          SkillCatalogResult.Success(summary)
        }
      }
}

private fun ClawHubPackageWire.toOfficialSkillSummary(): OfficialSkillSummary? {
  if (isOfficial != true || family.clean() != "skill") return null
  val normalizedOwner = ownerHandle.clean() ?: return null
  val normalizedSlug = name.clean() ?: return null
  val identity =
    runCatching {
      OfficialSkillIdentity(ownerHandle = normalizedOwner, slug = normalizedSlug)
    }.getOrNull() ?: return null
  return OfficialSkillSummary(
    identity = identity,
    displayName = displayName.clean() ?: normalizedSlug,
    summary = summary.clean(),
    channel = channel.clean(),
    iconUrl = icon.clean(),
    latestVersion = latestVersion.clean(),
    categories = categories.orEmpty().cleanList(),
    topics = topics.orEmpty().cleanList(),
    verificationTier = verificationTier.clean(),
    downloads = stats?.downloads,
    installs = stats?.installs,
    stars = stats?.stars,
    updatedAt = updatedAt,
  )
}

private fun Int.apiLimit(): Int = coerceIn(1, 100)

private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun List<String>.cleanList(): List<String> = mapNotNull { it.clean() }.distinct()

private fun <T> ClawHubApiResult.decode(
  deserializer: DeserializationStrategy<T>,
): SkillCatalogResult<T> =
  when (this) {
    is ClawHubApiResult.Failure -> SkillCatalogResult.Failure(error.toSkillFailure())
    is ClawHubApiResult.Success ->
      try {
        SkillCatalogResult.Success(
          ClawHubJson.decodeFromJsonElement(deserializer, document.body),
        )
      } catch (_: SerializationException) {
        SkillCatalogResult.Failure(SkillCatalogFailure.InvalidResponse)
      } catch (_: IllegalArgumentException) {
        SkillCatalogResult.Failure(SkillCatalogFailure.InvalidResponse)
      }
  }

private fun ClawHubApiError.toSkillFailure(): SkillCatalogFailure =
  when (this) {
    is ClawHubApiError.RateLimited -> SkillCatalogFailure.RateLimited(retryAfterSeconds)
    is ClawHubApiError.Http -> SkillCatalogFailure.Http(statusCode)
    ClawHubApiError.Network -> SkillCatalogFailure.Network
    ClawHubApiError.InvalidResponse,
    ClawHubApiError.ResponseTooLarge,
    -> SkillCatalogFailure.InvalidResponse
  }

private inline fun <T, R> SkillCatalogResult<T>.map(transform: (T) -> R): SkillCatalogResult<R> =
  when (this) {
    is SkillCatalogResult.Failure -> this
    is SkillCatalogResult.Success -> SkillCatalogResult.Success(transform(value))
  }

private inline fun <T, R> SkillCatalogResult<T>.flatMap(
  transform: (T) -> SkillCatalogResult<R>,
): SkillCatalogResult<R> =
  when (this) {
    is SkillCatalogResult.Failure -> this
    is SkillCatalogResult.Success -> transform(value)
  }
