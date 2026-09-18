package ai.openclaw.app.plugin.catalog

import ai.openclaw.app.plugin.GatewayPluginCatalogEntry
import ai.openclaw.app.plugin.GatewayPluginState

@JvmInline
internal value class PluginPackageName(
  val value: String,
) {
  init {
    require(value.isNotBlank()) { "Plugin package name must not be blank." }
    require(value == value.trim()) { "Plugin package name must be normalized." }
    require(value.length <= MAX_PLUGIN_PACKAGE_NAME_LENGTH) { "Plugin package name is too long." }
    require(value.none(Char::isISOControl)) { "Plugin package name contains a control character." }
  }
}

internal data class OfficialPluginSummary(
  val packageName: PluginPackageName,
  val displayName: String,
  val summary: String?,
  val family: String?,
  val channel: String?,
  val iconUrl: String?,
  val latestVersion: String?,
  val ownerHandle: String?,
  val runtimeId: String?,
  val categories: List<String>,
  val topics: List<String>,
  val verificationTier: String?,
  val downloads: Long?,
  val installs: Long?,
  val stars: Long?,
  val updatedAt: Long?,
)

internal data class OfficialPluginPage(
  val items: List<OfficialPluginSummary>,
  val nextCursor: String?,
)

internal data class OfficialPluginSearchMatch(
  val plugin: OfficialPluginSummary,
  val score: Double?,
)

internal data class OfficialPluginDetail(
  val plugin: OfficialPluginSummary,
  val owner: PluginPublisher?,
  val compatibility: PluginCompatibility?,
  val artifact: PluginArtifact?,
  val verification: PluginVerification?,
)

internal data class PluginPublisher(
  val handle: String,
  val displayName: String?,
  val imageUrl: String?,
)

internal data class PluginCompatibility(
  val builtWithOpenClawVersion: String?,
  val minimumGatewayVersion: String?,
  val pluginApiRange: String?,
)

internal data class PluginArtifact(
  val format: String?,
  val kind: String?,
  val sha256: String?,
  val npmIntegrity: String?,
  val sizeBytes: Long?,
)

internal data class PluginVerification(
  val tier: String?,
  val scanStatus: String?,
  val summary: String?,
  val sourceRepository: String?,
  val sourceCommit: String?,
  val sourcePath: String?,
  val hasProvenance: Boolean?,
  val trustedOpenClawPlugin: Boolean?,
)

internal data class PluginReadiness(
  val ready: Boolean,
  val checks: List<PluginReadinessCheck>,
  val blockers: List<String>,
)

internal data class PluginReadinessCheck(
  val id: String,
  val label: String?,
  val status: String?,
  val message: String?,
)

internal data class PluginVersionPage(
  val items: List<PluginVersion>,
  val nextCursor: String?,
)

internal data class PluginVersion(
  val version: String,
  val createdAt: Long?,
  val changelog: String?,
  val distributionTags: List<String>,
)

internal data class PluginSecurity(
  val version: String,
  val overview: String?,
  val auditUrl: String?,
  val releaseId: String?,
  val artifactKind: String?,
  val artifactSha256: String?,
  val npmIntegrity: String?,
  val scanStatus: String?,
  val moderationState: String?,
  val blockedFromDownload: Boolean,
  val reasons: List<String>,
  val pending: Boolean,
  val stale: Boolean,
)

internal sealed interface PluginCatalogResult<out T> {
  data class Success<T>(
    val value: T,
  ) : PluginCatalogResult<T>

  data class Failure(
    val reason: PluginCatalogFailure,
  ) : PluginCatalogResult<Nothing>
}

internal sealed interface PluginCatalogFailure {
  data class RateLimited(
    val retryAfterSeconds: Long?,
  ) : PluginCatalogFailure

  data class Http(
    val statusCode: Int,
  ) : PluginCatalogFailure

  data object Network : PluginCatalogFailure

  data object InvalidResponse : PluginCatalogFailure

  data object NotOfficial : PluginCatalogFailure
}

internal interface PluginCatalogRepository {
  suspend fun recommended(
    cursor: String? = null,
    limit: Int = DEFAULT_PLUGIN_PAGE_SIZE,
  ): PluginCatalogResult<OfficialPluginPage>

  suspend fun search(
    query: String,
    limit: Int = DEFAULT_PLUGIN_SEARCH_SIZE,
  ): PluginCatalogResult<List<OfficialPluginSearchMatch>>

  suspend fun detail(packageName: PluginPackageName): PluginCatalogResult<OfficialPluginDetail>

  suspend fun readiness(packageName: PluginPackageName): PluginCatalogResult<PluginReadiness>

  suspend fun versions(
    packageName: PluginPackageName,
    cursor: String? = null,
    limit: Int = DEFAULT_PLUGIN_VERSION_PAGE_SIZE,
  ): PluginCatalogResult<PluginVersionPage>

  suspend fun security(
    packageName: PluginPackageName,
    version: String,
  ): PluginCatalogResult<PluginSecurity>
}

internal sealed interface PluginLocalState {
  data object NotInstalled : PluginLocalState

  data class Installed(
    val gatewayPluginId: String,
    val version: String?,
    val enabled: Boolean,
    val state: GatewayPluginState,
    val error: String?,
  ) : PluginLocalState
}

internal data class PluginCatalogSnapshot(
  val catalog: OfficialPluginSummary,
  val local: PluginLocalState,
)

internal fun overlayPluginLocalState(
  catalog: List<OfficialPluginSummary>,
  gatewayPlugins: List<GatewayPluginCatalogEntry>,
): List<PluginCatalogSnapshot> {
  val byPackageName =
    gatewayPlugins
      .mapNotNull { gateway ->
        gateway.packageName
          ?.trim()
          ?.takeIf(String::isNotEmpty)
          ?.let { it to gateway }
      }.toMap()
  val byRuntimeId = gatewayPlugins.associateBy { it.id }
  return catalog.map { plugin ->
    val gateway =
      byPackageName[plugin.packageName.value]
        ?: plugin.runtimeId?.let(byRuntimeId::get)?.takeIf { it.packageName.isNullOrBlank() }
    PluginCatalogSnapshot(
      catalog = plugin,
      local = gateway?.toPluginLocalState() ?: PluginLocalState.NotInstalled,
    )
  }
}

private fun GatewayPluginCatalogEntry.toPluginLocalState(): PluginLocalState =
  if (!installed || state == GatewayPluginState.NotInstalled) {
    PluginLocalState.NotInstalled
  } else {
    PluginLocalState.Installed(
      gatewayPluginId = id,
      version = version,
      enabled = enabled,
      state = state,
      error = error,
    )
  }

internal const val DEFAULT_PLUGIN_PAGE_SIZE = 50
internal const val DEFAULT_PLUGIN_SEARCH_SIZE = 30
internal const val DEFAULT_PLUGIN_VERSION_PAGE_SIZE = 30
private const val MAX_PLUGIN_PACKAGE_NAME_LENGTH = 256
