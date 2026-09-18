package ai.openclaw.app.plugin.catalog

import ai.openclaw.app.clawhub.ClawHubApiClient
import ai.openclaw.app.clawhub.ClawHubApiError
import ai.openclaw.app.clawhub.ClawHubApiResult
import ai.openclaw.app.clawhub.ClawHubArtifactWire
import ai.openclaw.app.clawhub.ClawHubCompatibilityWire
import ai.openclaw.app.clawhub.ClawHubGetRequest
import ai.openclaw.app.clawhub.ClawHubJson
import ai.openclaw.app.clawhub.ClawHubPackageDetailWire
import ai.openclaw.app.clawhub.ClawHubPackagePageWire
import ai.openclaw.app.clawhub.ClawHubPackageWire
import ai.openclaw.app.clawhub.ClawHubReadinessWire
import ai.openclaw.app.clawhub.ClawHubSearchPageWire
import ai.openclaw.app.clawhub.ClawHubSecurityWire
import ai.openclaw.app.clawhub.ClawHubVerificationWire
import ai.openclaw.app.clawhub.ClawHubVersionsWire
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException

internal class ClawHubPluginCatalogRepository(
  private val api: ClawHubApiClient,
) : PluginCatalogRepository {
  override suspend fun recommended(
    cursor: String?,
    limit: Int,
  ): PluginCatalogResult<OfficialPluginPage> {
    val query =
      mutableListOf(
        "sort" to "recommended",
        "isOfficial" to "true",
        "limit" to limit.apiLimit().toString(),
      )
    cursor?.trim()?.takeIf(String::isNotEmpty)?.let { query += "cursor" to it }
    return api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "plugins"),
          queryParameters = query,
        ),
      ).decode(ClawHubPackagePageWire.serializer())
      .map { page ->
        OfficialPluginPage(
          items = page.items.mapNotNull(ClawHubPackageWire::toOfficialPluginSummary),
          nextCursor = page.nextCursor.clean(),
        )
      }
  }

  override suspend fun search(
    query: String,
    limit: Int,
  ): PluginCatalogResult<List<OfficialPluginSearchMatch>> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return PluginCatalogResult.Success(emptyList())
    return api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "plugins", "search"),
          queryParameters =
            listOf(
              "q" to normalizedQuery,
              "isOfficial" to "true",
              "limit" to limit.apiLimit().toString(),
            ),
        ),
      ).decode(ClawHubSearchPageWire.serializer())
      .map { page ->
        page.results.mapNotNull { result ->
          result.packageValue?.toOfficialPluginSummary()?.let { plugin ->
            OfficialPluginSearchMatch(plugin = plugin, score = result.score)
          }
        }
      }
  }

  override suspend fun detail(packageName: PluginPackageName): PluginCatalogResult<OfficialPluginDetail> =
    api
      .get(packageRequest(packageName))
      .decode(ClawHubPackageDetailWire.serializer())
      .flatMap { detail ->
        val plugin =
          detail.packageValue?.toOfficialPluginSummary()
            ?: return@flatMap PluginCatalogResult.Failure(PluginCatalogFailure.NotOfficial)
        if (plugin.packageName != packageName) {
          return@flatMap PluginCatalogResult.Failure(PluginCatalogFailure.InvalidResponse)
        }
        PluginCatalogResult.Success(
          OfficialPluginDetail(
            plugin = plugin,
            owner =
              detail.owner?.let { owner ->
                owner.handle.clean()?.let { handle ->
                  PluginPublisher(
                    handle = handle,
                    displayName = owner.displayName.clean(),
                    imageUrl = owner.image.clean(),
                  )
                }
              },
            compatibility = detail.packageValue.compatibility?.toPluginCompatibility(),
            artifact = detail.packageValue.artifact?.toPluginArtifact(),
            verification = detail.packageValue.verification?.toPluginVerification(),
          ),
        )
      }

  override suspend fun readiness(packageName: PluginPackageName): PluginCatalogResult<PluginReadiness> =
    api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "packages", packageName.value, "readiness"),
        ),
      ).decode(ClawHubReadinessWire.serializer())
      .flatMap { readiness ->
        val responsePackage = readiness.packageValue
        if (responsePackage?.isOfficial != true) {
          return@flatMap PluginCatalogResult.Failure(PluginCatalogFailure.NotOfficial)
        }
        if (responsePackage.name.clean() != packageName.value) {
          return@flatMap PluginCatalogResult.Failure(PluginCatalogFailure.InvalidResponse)
        }
        PluginCatalogResult.Success(
          PluginReadiness(
            ready = readiness.ready == true,
            checks =
              readiness.checks.orEmpty().mapNotNull { check ->
                check.id.clean()?.let { id ->
                  PluginReadinessCheck(
                    id = id,
                    label = check.label.clean(),
                    status = check.status.clean(),
                    message = check.message.clean(),
                  )
                }
              },
            blockers = readiness.blockers.orEmpty().cleanList(),
          ),
        )
      }

  override suspend fun versions(
    packageName: PluginPackageName,
    cursor: String?,
    limit: Int,
  ): PluginCatalogResult<PluginVersionPage> {
    val query = mutableListOf("limit" to limit.apiLimit().toString())
    cursor?.trim()?.takeIf(String::isNotEmpty)?.let { query += "cursor" to it }
    return api
      .get(
        ClawHubGetRequest(
          pathSegments = listOf("api", "v1", "packages", packageName.value, "versions"),
          queryParameters = query,
        ),
      ).decode(ClawHubVersionsWire.serializer())
      .map { page ->
        PluginVersionPage(
          items =
            page.items.mapNotNull { version ->
              version.version.clean()?.let { value ->
                PluginVersion(
                  version = value,
                  createdAt = version.createdAt,
                  changelog = version.changelog.clean(),
                  distributionTags = version.distTags.orEmpty().cleanList(),
                )
              }
            },
          nextCursor = page.nextCursor.clean(),
        )
      }
  }

  override suspend fun security(
    packageName: PluginPackageName,
    version: String,
  ): PluginCatalogResult<PluginSecurity> {
    val normalizedVersion = version.requiredPathValue("Plugin version")
    return api
      .get(
        ClawHubGetRequest(
          pathSegments =
            listOf(
              "api",
              "v1",
              "packages",
              packageName.value,
              "versions",
              normalizedVersion,
              "security",
            ),
        ),
      ).decode(ClawHubSecurityWire.serializer())
      .map { security ->
        val release = security.release
        val trust = security.trust
        PluginSecurity(
          version = release?.version.clean() ?: normalizedVersion,
          overview = security.overview.clean(),
          auditUrl = security.securityAuditUrl.clean(),
          releaseId = release?.releaseId.clean(),
          artifactKind = release?.artifactKind.clean(),
          artifactSha256 = release?.artifactSha256.clean(),
          npmIntegrity = release?.npmIntegrity.clean(),
          scanStatus = trust?.scanStatus.clean(),
          moderationState = trust?.moderationState.clean(),
          blockedFromDownload = trust?.blockedFromDownload == true,
          reasons = trust?.reasons.orEmpty().cleanList(),
          pending = trust?.pending == true,
          stale = trust?.stale == true,
        )
      }
  }
}

private fun packageRequest(
  packageName: PluginPackageName,
): ClawHubGetRequest =
  ClawHubGetRequest(
    pathSegments = listOf("api", "v1", "packages", packageName.value),
  )

private fun ClawHubPackageWire.toOfficialPluginSummary(): OfficialPluginSummary? {
  if (isOfficial != true || family.clean() == "skill") return null
  val normalizedName = name.clean() ?: return null
  val packageName = runCatching { PluginPackageName(normalizedName) }.getOrNull() ?: return null
  return OfficialPluginSummary(
    packageName = packageName,
    displayName = displayName.clean() ?: normalizedName.substringAfterLast('/'),
    summary = summary.clean(),
    family = family.clean(),
    channel = channel.clean(),
    iconUrl = icon.clean(),
    latestVersion = latestVersion.clean(),
    ownerHandle = ownerHandle.clean(),
    runtimeId = runtimeId.clean(),
    categories = categories.orEmpty().cleanList(),
    topics = topics.orEmpty().cleanList(),
    verificationTier = verificationTier.clean(),
    downloads = stats?.downloads,
    installs = stats?.installs,
    stars = stats?.stars,
    updatedAt = updatedAt,
  )
}

private fun ClawHubCompatibilityWire.toPluginCompatibility(): PluginCompatibility =
  PluginCompatibility(
    builtWithOpenClawVersion = builtWithOpenClawVersion.clean(),
    minimumGatewayVersion = minGatewayVersion.clean(),
    pluginApiRange = pluginApiRange.clean(),
  )

private fun ClawHubArtifactWire.toPluginArtifact(): PluginArtifact =
  PluginArtifact(
    format = format.clean(),
    kind = kind.clean(),
    sha256 = sha256.clean(),
    npmIntegrity = npmIntegrity.clean(),
    sizeBytes = size,
  )

private fun ClawHubVerificationWire.toPluginVerification(): PluginVerification =
  PluginVerification(
    tier = tier.clean(),
    scanStatus = scanStatus.clean(),
    summary = summary.clean(),
    sourceRepository = sourceRepo.clean(),
    sourceCommit = sourceCommit.clean(),
    sourcePath = sourcePath.clean(),
    hasProvenance = hasProvenance,
    trustedOpenClawPlugin = trustedOpenClawPlugin,
  )

private fun Int.apiLimit(): Int = coerceIn(1, 100)

private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun List<String>.cleanList(): List<String> = mapNotNull(String::clean).distinct()

private fun String.requiredPathValue(label: String): String {
  val value = trim()
  require(value.isNotEmpty()) { "$label must not be blank." }
  require(value.length <= 256) { "$label is too long." }
  require(value.none(Char::isISOControl)) { "$label contains a control character." }
  return value
}

private fun <T> ClawHubApiResult.decode(
  deserializer: DeserializationStrategy<T>,
): PluginCatalogResult<T> =
  when (this) {
    is ClawHubApiResult.Failure -> PluginCatalogResult.Failure(error.toPluginFailure())
    is ClawHubApiResult.Success ->
      try {
        PluginCatalogResult.Success(
          ClawHubJson.decodeFromJsonElement(deserializer, document.body),
        )
      } catch (_: SerializationException) {
        PluginCatalogResult.Failure(PluginCatalogFailure.InvalidResponse)
      } catch (_: IllegalArgumentException) {
        PluginCatalogResult.Failure(PluginCatalogFailure.InvalidResponse)
      }
  }

private fun ClawHubApiError.toPluginFailure(): PluginCatalogFailure =
  when (this) {
    is ClawHubApiError.RateLimited -> PluginCatalogFailure.RateLimited(retryAfterSeconds)
    is ClawHubApiError.Http -> PluginCatalogFailure.Http(statusCode)
    ClawHubApiError.Network -> PluginCatalogFailure.Network
    ClawHubApiError.InvalidResponse,
    ClawHubApiError.ResponseTooLarge,
    -> PluginCatalogFailure.InvalidResponse
  }

private inline fun <T, R> PluginCatalogResult<T>.map(transform: (T) -> R): PluginCatalogResult<R> =
  when (this) {
    is PluginCatalogResult.Failure -> this
    is PluginCatalogResult.Success -> PluginCatalogResult.Success(transform(value))
  }

private inline fun <T, R> PluginCatalogResult<T>.flatMap(
  transform: (T) -> PluginCatalogResult<R>,
): PluginCatalogResult<R> =
  when (this) {
    is PluginCatalogResult.Failure -> this
    is PluginCatalogResult.Success -> transform(value)
  }
