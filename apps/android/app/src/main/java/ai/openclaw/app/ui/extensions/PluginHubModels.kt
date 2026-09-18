package ai.openclaw.app.ui.extensions

/** Plugin-only lifecycle projection. Gateway remains the source of truth. */
internal enum class PluginInstallStatus {
  Available,
  Reviewing,
  Installing,
  Installed,
  Ready,
  NeedsSetup,
  Incompatible,
  Failed,
  UnknownOutcome,
}

internal sealed interface PluginArtworkSource {
  data object None : PluginArtworkSource

  data class Gateway(
    val pluginId: String,
  ) : PluginArtworkSource

  data class ClawHub(
    val packageName: String,
  ) : PluginArtworkSource
}

internal data class PluginArtworkPayload(
  val bytes: ByteArray,
  val contentType: String,
)

/** Installable distribution unit. It may expose several Skills after installation. */
internal data class PluginCatalogItem(
  val pluginId: String,
  val displayName: String,
  val summary: String?,
  val origin: String?,
  val categories: List<String>,
  val packageName: String?,
  val version: String?,
  val kinds: List<String>,
  val status: PluginInstallStatus,
  val enabled: Boolean,
  val artworkSource: PluginArtworkSource = PluginArtworkSource.None,
  val installSource: String?,
  val installReference: String?,
  val removable: Boolean,
  val error: String?,
) {
  val isBuiltIn: Boolean
    get() = origin.equals("bundled", ignoreCase = true)

  val isProductInternal: Boolean
    get() = pluginId.startsWith("claw-in-one-")
}

internal data class PluginSearchItem(
  val packageName: String,
  val displayName: String,
  val summary: String?,
  val family: String,
  val channel: String,
  val official: Boolean,
  val latestVersion: String?,
  val downloads: Long?,
  val verificationTier: String?,
  val installedPluginId: String?,
  val artworkSource: PluginArtworkSource,
)

internal data class PluginHubUiState(
  val connected: Boolean,
  val inventoryAvailable: Boolean,
  val catalogRefreshing: Boolean,
  val catalogLoadingNextPage: Boolean = false,
  val catalogCanLoadNextPage: Boolean = false,
  val catalogErrorText: String?,
  val inventoryRefreshing: Boolean,
  val inventoryErrorText: String?,
  val mutationAllowed: Boolean,
  val diagnosticsCount: Int,
  /** Official ClawHub candidates with Gateway lifecycle state overlaid. */
  val plugins: List<PluginCatalogItem>,
  /** Gateway-owned inventory used only by management and built-in surfaces. */
  val managedPlugins: List<PluginCatalogItem>,
  val searchQuery: String,
  val searching: Boolean,
  val searchResults: List<PluginSearchItem>,
  val searchErrorText: String?,
  val lifecycle: PluginLifecyclePresentation = PluginLifecyclePresentation(),
  val mcp: McpHubPresentation = McpHubPresentation(),
)

/** Directory-only projection; it never invents entries outside the authoritative Plugin catalog. */
internal data class PluginDirectoryPresentation(
  val installed: List<PluginCatalogItem>,
  val recommended: PluginDirectorySectionPresentation,
  val categories: List<PluginDirectorySectionPresentation>,
)

internal sealed interface PluginDirectorySectionId {
  data object Recommended : PluginDirectorySectionId

  data class Category(
    val category: String,
  ) : PluginDirectorySectionId
}

internal data class PluginDirectoryOverflowPresentation(
  val hiddenCount: Int,
  val iconItems: List<PluginCatalogItem>,
  val labelNames: List<String>,
  val hasMore: Boolean,
)

/**
 * A directory section owns both the authoritative list and its compact landing-page preview.
 * Navigation consumes [items]; the landing screen consumes [previewItems] and [overflow].
 */
internal data class PluginDirectorySectionPresentation(
  val id: PluginDirectorySectionId,
  val items: List<PluginCatalogItem>,
  val previewItems: List<PluginCatalogItem>,
  val overflow: PluginDirectoryOverflowPresentation?,
)

private const val PluginDirectoryPreviewLimit = 6
private const val PluginDirectoryOverflowIconLimit = 3
private const val PluginDirectoryOverflowNameLimit = 2

internal fun pluginDirectoryPresentation(plugins: List<PluginCatalogItem>): PluginDirectoryPresentation {
  val visiblePlugins = plugins.filterNot(PluginCatalogItem::isBuiltIn)
  val categoryNames = visiblePlugins.flatMap(PluginCatalogItem::categories).distinct()
  return PluginDirectoryPresentation(
    installed = visiblePlugins.filter { it.status != PluginInstallStatus.Available },
    recommended =
      pluginDirectorySection(
        id = PluginDirectorySectionId.Recommended,
        items = visiblePlugins,
      ),
    categories =
      categoryNames.map { category ->
        pluginDirectorySection(
          id = PluginDirectorySectionId.Category(category),
          items = visiblePlugins.filter { category in it.categories },
        )
      },
  )
}

private fun pluginDirectorySection(
  id: PluginDirectorySectionId,
  items: List<PluginCatalogItem>,
): PluginDirectorySectionPresentation {
  val hiddenItems = items.drop(PluginDirectoryPreviewLimit)
  return PluginDirectorySectionPresentation(
    id = id,
    items = items,
    previewItems = items.take(PluginDirectoryPreviewLimit),
    overflow =
      hiddenItems.takeIf(List<PluginCatalogItem>::isNotEmpty)?.let { overflowItems ->
        PluginDirectoryOverflowPresentation(
          hiddenCount = overflowItems.size,
          iconItems = overflowItems.take(PluginDirectoryOverflowIconLimit),
          labelNames = overflowItems.take(PluginDirectoryOverflowNameLimit).map(PluginCatalogItem::displayName),
          hasMore = overflowItems.size > PluginDirectoryOverflowNameLimit,
        )
      },
  )
}

internal fun shouldLoadNextPluginPage(
  lastVisibleIndex: Int,
  itemCount: Int,
  canLoadNextPage: Boolean,
  loadingNextPage: Boolean,
): Boolean =
  canLoadNextPage &&
    !loadingNextPage &&
    itemCount > 0 &&
    lastVisibleIndex >= itemCount - 3

internal enum class McpTransportChoice {
  StreamableHttp,
  Sse,
}

internal data class McpServerItem(
  val name: String,
  val target: String,
  val transportLabel: String,
  val enabled: Boolean,
  val detail: String?,
)

internal data class McpConnectorItem(
  val id: String,
  val name: String,
  val description: String,
  val group: McpConnectorGroupPresentation,
  val addsServer: Boolean,
  val alreadyAdded: Boolean,
  val requiresAuthentication: Boolean,
)

internal enum class McpConnectorGroupPresentation {
  Work,
  Development,
  Home,
  Life,
}

internal data class McpHubPresentation(
  val readAvailable: Boolean = false,
  val canMutate: Boolean = false,
  val refreshing: Boolean = false,
  val errorText: String? = null,
  val servers: List<McpServerItem> = emptyList(),
  val connectors: List<McpConnectorItem> = emptyList(),
  val busyIdentity: String? = null,
  val busyLabel: String? = null,
  val notice: PluginLifecycleNotice? = null,
)

internal enum class PluginLifecycleNoticeTone {
  Success,
  Warning,
  Error,
}

internal data class PluginLifecycleNotice(
  val text: String,
  val tone: PluginLifecycleNoticeTone,
  val refreshAction: Boolean = false,
)

internal sealed interface PluginMutationDialogPresentation {
  enum class ConfirmationAction {
    Install,
    Remove,
  }

  data class Confirmation(
    val displayName: String,
    val action: ConfirmationAction,
  ) : PluginMutationDialogPresentation

  data class PolicyReview(
    val displayName: String,
    val reason: String,
    val findings: List<PluginDetailFact>,
  ) : PluginMutationDialogPresentation

  enum class CapabilityReviewAction {
    Install,
    Enable,
  }

  data class CapabilityReview(
    val displayName: String,
    val inspection: PluginInspectionPresentation,
    val widenedSections: List<PluginDetailSection>,
    val acceptedAt: String?,
    val action: CapabilityReviewAction,
  ) : PluginMutationDialogPresentation
}

internal data class PluginLifecyclePresentation(
  val canInstall: Boolean = false,
  val canSetEnabled: Boolean = false,
  val canUninstall: Boolean = false,
  val busyIdentity: String? = null,
  val busyLabel: String? = null,
  val notice: PluginLifecycleNotice? = null,
  val dialog: PluginMutationDialogPresentation? = null,
)

internal data class PluginDetailFact(
  val label: String,
  val value: String,
)

internal data class PluginDetailSection(
  val title: String,
  val facts: List<PluginDetailFact>,
)

internal data class PluginTrustPresentation(
  val label: String,
  val warning: Boolean,
  val facts: List<PluginDetailFact>,
)

internal enum class PluginIncludedKind {
  Skills,
  Tools,
  Connections,
  Providers,
}

internal data class PluginIncludedSection(
  val kind: PluginIncludedKind,
  val title: String,
  val items: List<String>,
)

/** Immutable, token-free projection of one Gateway inspection response. */
internal data class PluginInspectionPresentation(
  val includedSections: List<PluginIncludedSection>,
  val sourceFacts: List<PluginDetailFact>,
  val declaredSections: List<PluginDetailSection>,
  val grantSections: List<PluginDetailSection>,
  val trust: PluginTrustPresentation?,
)

internal data class PluginDetailUiState(
  val plugin: PluginCatalogItem?,
  val connected: Boolean,
  val inspectionAvailable: Boolean,
  val loading: Boolean,
  val inspection: PluginInspectionPresentation?,
  val inspectionErrorText: String?,
)
