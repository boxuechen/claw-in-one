package ai.openclaw.app.ui.extensions

import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawDetailRow
import ai.openclaw.app.ui.design.ClawListPanel
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPill
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawSectionHeader
import ai.openclaw.app.ui.design.ClawStatus
import ai.openclaw.app.ui.design.ClawStatusPill
import ai.openclaw.app.ui.design.ClawTextBadge
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

/** Plugin-owned surfaces selected by extension-center navigation. */
internal sealed interface PluginHubSurface {
  data object Directory : PluginHubSurface

  data class Category(
    val sectionId: PluginDirectorySectionId,
  ) : PluginHubSurface

  data object Management : PluginHubSurface
}

@Composable
internal fun PluginHubScreen(
  surface: PluginHubSurface,
  state: PluginHubUiState,
  contentPadding: PaddingValues,
  onRefresh: () -> Unit,
  onSearch: (String) -> Unit,
  onOpenPlugin: (String, String) -> Unit,
  onOpenCategory: (PluginDirectorySectionId) -> Unit = {},
  onLoadNextPage: () -> Unit = {},
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload? = { null },
  onInstallPlugin: (PluginCatalogItem) -> Unit = {},
  onInstallSearchResult: (PluginSearchItem) -> Unit = {},
  onAddMcpServer: (String, String, McpTransportChoice) -> Unit = { _, _, _ -> },
  onSetMcpEnabled: (String, Boolean) -> Unit = { _, _ -> },
  onRemoveMcpServer: (String) -> Unit = {},
  onActivateMcpConnector: (String) -> Unit = {},
  onRefreshMcpMutation: () -> Unit = {},
  onDismissMcpMutation: () -> Unit = {},
  onRefreshLifecycle: () -> Unit = {},
  onDismissLifecycle: () -> Unit = {},
) {
  when (surface) {
    PluginHubSurface.Management ->
      InstalledPluginsScreen(
        state = state,
        contentPadding = contentPadding,
        onRefresh = onRefresh,
        onOpenPlugin = onOpenPlugin,
        onLoadPluginArtwork = onLoadPluginArtwork,
        onAddMcpServer = onAddMcpServer,
        onSetMcpEnabled = onSetMcpEnabled,
        onRemoveMcpServer = onRemoveMcpServer,
        onActivateMcpConnector = onActivateMcpConnector,
        onRefreshMcpMutation = onRefreshMcpMutation,
        onDismissMcpMutation = onDismissMcpMutation,
        onRefreshLifecycle = onRefreshLifecycle,
        onDismissLifecycle = onDismissLifecycle,
      )
    PluginHubSurface.Directory ->
      DiscoverPluginsScreen(
        state = state,
        contentPadding = contentPadding,
        onRefresh = onRefresh,
        onSearch = onSearch,
        onOpenPlugin = onOpenPlugin,
        onOpenCategory = onOpenCategory,
        onLoadPluginArtwork = onLoadPluginArtwork,
        onInstallPlugin = onInstallPlugin,
        onInstallSearchResult = onInstallSearchResult,
        onRefreshLifecycle = onRefreshLifecycle,
        onDismissLifecycle = onDismissLifecycle,
      )
    is PluginHubSurface.Category ->
      PluginCategoryScreen(
        sectionId = surface.sectionId,
        state = state,
        contentPadding = contentPadding,
        onRefresh = onRefresh,
        onOpenPlugin = onOpenPlugin,
        onLoadPluginArtwork = onLoadPluginArtwork,
        onInstallPlugin = onInstallPlugin,
        onLoadNextPage = onLoadNextPage,
        onRefreshLifecycle = onRefreshLifecycle,
        onDismissLifecycle = onDismissLifecycle,
      )
  }
}

@Composable
private fun InstalledPluginsScreen(
  state: PluginHubUiState,
  contentPadding: PaddingValues,
  onRefresh: () -> Unit,
  onOpenPlugin: (String, String) -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onAddMcpServer: (String, String, McpTransportChoice) -> Unit,
  onSetMcpEnabled: (String, Boolean) -> Unit,
  onRemoveMcpServer: (String) -> Unit,
  onActivateMcpConnector: (String) -> Unit,
  onRefreshMcpMutation: () -> Unit,
  onDismissMcpMutation: () -> Unit,
  onRefreshLifecycle: () -> Unit,
  onDismissLifecycle: () -> Unit,
) {
  val installed =
    remember(state.plugins) {
      state.managedPlugins.filter { it.status != PluginInstallStatus.Available && !it.isBuiltIn }
    }

  LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(28.dp)) {
    state.lifecycle.notice?.let {
      item {
        PluginLifecycleNoticePanel(
          lifecycle = state.lifecycle,
          onRefresh = onRefreshLifecycle,
          onDismiss = onDismissLifecycle,
        )
      }
    }
    item {
      McpConnectionsSection(
        state = state,
        onAdd = onAddMcpServer,
        onSetEnabled = onSetMcpEnabled,
        onRemove = onRemoveMcpServer,
        onActivateConnector = onActivateMcpConnector,
        onRefreshMutation = onRefreshMcpMutation,
        onDismissMutation = onDismissMcpMutation,
      )
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ClawSectionHeader(stringResource(R.string.extension_installed_plugins))
        when {
          !state.connected ->
            ExtensionDirectoryState(
              title = stringResource(R.string.extension_plugins_offline_title),
              message = stringResource(R.string.extension_reconnect_installed_plugins),
              actionLabel = stringResource(R.string.extension_try_again),
              onAction = onRefresh,
            )
          !state.inventoryAvailable ->
            PluginInlineState(stringResource(R.string.extension_update_manage_plugins), warning = true)
          installed.isEmpty() -> PluginInlineState(stringResource(R.string.extension_no_installed_plugins))
          else ->
            ClawListPanel(items = installed) { plugin ->
              PluginRow(
                plugin = plugin,
                onOpen = { onOpenPlugin(plugin.pluginId, plugin.displayName) },
                onLoadArtwork = onLoadPluginArtwork,
              )
            }
        }
      }
    }

    if (state.inventoryErrorText != null || state.diagnosticsCount > 0) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          ClawSectionHeader(stringResource(R.string.extension_needs_attention))
          state.inventoryErrorText?.let { PluginInlineState(text = it, warning = true) }
          if (state.diagnosticsCount > 0) {
            PluginInlineState(
              text = stringResource(R.string.extension_plugin_diagnostics),
              warning = true,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DiscoverPluginsScreen(
  state: PluginHubUiState,
  contentPadding: PaddingValues,
  onRefresh: () -> Unit,
  onSearch: (String) -> Unit,
  onOpenPlugin: (String, String) -> Unit,
  onOpenCategory: (PluginDirectorySectionId) -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onInstallPlugin: (PluginCatalogItem) -> Unit,
  onInstallSearchResult: (PluginSearchItem) -> Unit,
  onRefreshLifecycle: () -> Unit,
  onDismissLifecycle: () -> Unit,
) {
  var query by rememberSaveable { mutableStateOf(state.searchQuery) }
  LaunchedEffect(query) {
    val normalized = query.trim()
    if (normalized.length < 2) {
      if (state.searchQuery.isNotEmpty()) onSearch("")
    } else {
      delay(300)
      onSearch(normalized)
    }
  }
  val directory = remember(state.plugins) { pluginDirectoryPresentation(state.plugins) }
  val normalizedQuery = query.trim()
  val searchActive = normalizedQuery.length >= 2
  val searchPending = searchActive && normalizedQuery != state.searchQuery.trim()

  LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(24.dp)) {
    item {
      val searchLabel = stringResource(R.string.extension_search_plugins)
      ExtensionDirectorySearchField(
        value = query,
        onValueChange = { query = it },
        onClear = {
          query = ""
          onSearch("")
        },
        placeholder = searchLabel,
        enabled = true,
        modifier = Modifier.semantics { contentDescription = searchLabel },
      )
    }

    if (!searchActive && directory.installed.isNotEmpty()) {
      item {
        InstalledPluginShortcuts(
          plugins = directory.installed,
          onOpenPlugin = onOpenPlugin,
          onLoadPluginArtwork = onLoadPluginArtwork,
        )
      }
    }

    state.lifecycle.notice?.let {
      item {
        PluginLifecycleNoticePanel(
          lifecycle = state.lifecycle,
          onRefresh = onRefreshLifecycle,
          onDismiss = onDismissLifecycle,
        )
      }
    }

    when {
      state.catalogRefreshing && state.plugins.isEmpty() -> {
        item {
          ExtensionDirectoryLoading(message = stringResource(R.string.extension_loading_plugins))
        }
      }
      state.catalogErrorText != null && state.plugins.isEmpty() -> {
        item {
          ExtensionDirectoryState(
            title = stringResource(R.string.extension_plugin_search_failed_title),
            message = state.catalogErrorText,
            actionLabel = stringResource(R.string.extension_refresh),
            onAction = onRefresh,
            warning = true,
          )
        }
      }
      searchActive -> {
        when {
          searchPending || state.searching -> {
            item {
              ExtensionDirectoryLoading(message = stringResource(R.string.extension_searching_plugins))
            }
          }
          state.searchErrorText != null -> {
            item {
              ExtensionDirectoryState(
                title = stringResource(R.string.extension_plugin_search_failed_title),
                message = state.searchErrorText,
                actionLabel = stringResource(R.string.extension_try_again),
                onAction = { onSearch(query.trim()) },
                warning = true,
              )
            }
          }
          state.searchResults.isEmpty() -> {
            item {
              ExtensionDirectoryState(
                title = stringResource(R.string.extension_no_search_matches_title),
                message = stringResource(R.string.extension_no_search_matches),
                actionLabel = stringResource(R.string.extension_clear_search),
                onAction = {
                  query = ""
                  onSearch("")
                },
              )
            }
          }
          else -> {
            item {
              PluginSearchGroup(
                items = state.searchResults,
                lifecycle = state.lifecycle,
                onOpenPlugin = onOpenPlugin,
                onLoadPluginArtwork = onLoadPluginArtwork,
                onInstall = onInstallSearchResult,
              )
            }
          }
        }
      }
      else -> {
        if (directory.recommended.items.isNotEmpty()) {
          item {
            PluginCatalogGroup(
              title = nativeString("Recommended"),
              section = directory.recommended,
              lifecycle = state.lifecycle,
              onOpenPlugin = onOpenPlugin,
              onLoadPluginArtwork = onLoadPluginArtwork,
              onInstall = onInstallPlugin,
              onOpenCategory = onOpenCategory,
            )
          }
        }
        directory.categories.forEach { category ->
          item {
            PluginCatalogGroup(
              title = (category.id as PluginDirectorySectionId.Category).category,
              section = category,
              lifecycle = state.lifecycle,
              onOpenPlugin = onOpenPlugin,
              onLoadPluginArtwork = onLoadPluginArtwork,
              onInstall = onInstallPlugin,
              onOpenCategory = onOpenCategory,
            )
          }
        }
        if (directory.recommended.items.isEmpty()) {
          item {
            ExtensionDirectoryState(
              title = stringResource(R.string.extension_no_plugins_title),
              message = stringResource(R.string.extension_no_curated_plugins),
              actionLabel = stringResource(R.string.extension_refresh),
              onAction = onRefresh,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PluginCategoryScreen(
  sectionId: PluginDirectorySectionId,
  state: PluginHubUiState,
  contentPadding: PaddingValues,
  onRefresh: () -> Unit,
  onOpenPlugin: (String, String) -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onInstallPlugin: (PluginCatalogItem) -> Unit,
  onLoadNextPage: () -> Unit,
  onRefreshLifecycle: () -> Unit,
  onDismissLifecycle: () -> Unit,
) {
  val directory = remember(state.plugins) { pluginDirectoryPresentation(state.plugins) }
  val section =
    when (sectionId) {
      PluginDirectorySectionId.Recommended -> directory.recommended
      is PluginDirectorySectionId.Category -> directory.categories.firstOrNull { it.id == sectionId }
    }
  val listState = rememberLazyListState()
  val shouldLoadNextPage by
    remember(section?.items?.size, state.catalogCanLoadNextPage, state.catalogLoadingNextPage) {
      derivedStateOf {
        val lastVisible =
          listState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index ?: -1
        val itemCount = section?.items?.size ?: 0
        shouldLoadNextPluginPage(
          lastVisibleIndex = lastVisible,
          itemCount = itemCount,
          canLoadNextPage = state.catalogCanLoadNextPage,
          loadingNextPage = state.catalogLoadingNextPage,
        )
      }
    }
  LaunchedEffect(shouldLoadNextPage) {
    if (shouldLoadNextPage) onLoadNextPage()
  }

  LazyColumn(
    state = listState,
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    state.lifecycle.notice?.let { lifecycleNotice ->
      item {
        PluginLifecycleNoticePanel(
          lifecycle = state.lifecycle.copy(notice = lifecycleNotice),
          onRefresh = onRefreshLifecycle,
          onDismiss = onDismissLifecycle,
        )
      }
    }
    when {
      section == null && state.catalogRefreshing ->
        item { ExtensionDirectoryLoading(message = stringResource(R.string.extension_loading_plugins)) }
      section == null ->
        item {
          ExtensionDirectoryState(
            title = stringResource(R.string.extension_no_plugins_title),
            message = stringResource(R.string.extension_no_curated_plugins),
            actionLabel = stringResource(R.string.extension_refresh),
            onAction = onRefresh,
          )
        }
      else -> {
        items(
          items = section.items,
          key = { plugin -> plugin.packageName ?: plugin.pluginId },
        ) { plugin ->
          PluginCatalogRow(
            plugin = plugin,
            lifecycle = state.lifecycle,
            onOpenPlugin = onOpenPlugin,
            onLoadPluginArtwork = onLoadPluginArtwork,
            onInstall = onInstallPlugin,
          )
        }
        if (state.catalogLoadingNextPage) {
          item {
            Box(
              modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = ClawTheme.colors.textMuted,
                strokeWidth = 2.dp,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun InstalledPluginShortcuts(
  plugins: List<PluginCatalogItem>,
  onOpenPlugin: (String, String) -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_installed_shortcuts))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(plugins, key = PluginCatalogItem::pluginId) { plugin ->
        val openLabel = stringResource(R.string.extension_open_plugin, plugin.displayName)
        Surface(
          onClick = { onOpenPlugin(plugin.pluginId, plugin.displayName) },
          modifier =
            Modifier
              .size(48.dp)
              .semantics { contentDescription = openLabel },
          shape = RoundedCornerShape(13.dp),
          color = ClawTheme.colors.surface,
        ) {
          Box(contentAlignment = Alignment.Center) {
            PluginArtwork(plugin = plugin, onLoadArtwork = onLoadPluginArtwork, size = 40.dp)
          }
        }
      }
    }
  }
}

@Composable
private fun PluginCatalogGroup(
  title: String,
  section: PluginDirectorySectionPresentation,
  lifecycle: PluginLifecyclePresentation,
  onOpenPlugin: (String, String) -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onInstall: (PluginCatalogItem) -> Unit,
  onOpenCategory: (PluginDirectorySectionId) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
    ClawSectionHeader(title)
    section.previewItems.forEach { plugin ->
      PluginCatalogRow(
        plugin = plugin,
        lifecycle = lifecycle,
        onOpenPlugin = onOpenPlugin,
        onLoadPluginArtwork = onLoadPluginArtwork,
        onInstall = onInstall,
      )
    }
    section.overflow?.let { overflow ->
      PluginDirectoryOverflowRow(
        overflow = overflow,
        onLoadPluginArtwork = onLoadPluginArtwork,
        onClick = { onOpenCategory(section.id) },
      )
    }
  }
}

@Composable
private fun PluginCatalogRow(
  plugin: PluginCatalogItem,
  lifecycle: PluginLifecyclePresentation,
  onOpenPlugin: (String, String) -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onInstall: (PluginCatalogItem) -> Unit,
) {
  PluginDirectoryRow(
    title = plugin.displayName,
    value = plugin.summary ?: pluginStatusLabel(plugin),
    installed = plugin.status != PluginInstallStatus.Available,
    busy = lifecycle.busyIdentity == plugin.installIdentity(),
    actionEnabled = lifecycle.busyIdentity == null && lifecycle.canInstall,
    onOpen = { onOpenPlugin(plugin.pluginId, plugin.displayName) },
    onInstall = { onInstall(plugin) },
    leading = { PluginArtwork(plugin = plugin, onLoadArtwork = onLoadPluginArtwork, size = 40.dp) },
  )
}

@Composable
private fun PluginDirectoryOverflowRow(
  overflow: PluginDirectoryOverflowPresentation,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onClick: () -> Unit,
) {
  val label =
    when {
      overflow.hasMore ->
        stringResource(
          R.string.extension_view_more_plugins,
          overflow.labelNames[0],
          overflow.labelNames[1],
        )
      overflow.labelNames.size == 2 ->
        stringResource(
          R.string.extension_view_two_plugins,
          overflow.labelNames[0],
          overflow.labelNames[1],
        )
      else -> stringResource(R.string.extension_view_one_plugin, overflow.labelNames.single())
    }
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
    color = Color.Transparent,
    contentColor = ClawTheme.colors.textMuted,
  ) {
    Row(
      modifier = Modifier.padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
        overflow.iconItems.forEach { plugin ->
          Box(
            modifier =
              Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, ClawTheme.colors.canvas, RoundedCornerShape(8.dp)),
          ) {
            PluginArtwork(plugin = plugin, onLoadArtwork = onLoadPluginArtwork, size = 28.dp)
          }
        }
      }
      Text(
        text = label,
        style = ClawTheme.type.body,
        color = ClawTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun PluginSearchGroup(
  items: List<PluginSearchItem>,
  lifecycle: PluginLifecyclePresentation,
  onOpenPlugin: (String, String) -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onInstall: (PluginSearchItem) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_from_clawhub))
    items.forEach { item ->
      val installedId = item.installedPluginId
      PluginDirectoryRow(
        title = item.displayName,
        value = item.summary ?: stringResource(R.string.extension_from_clawhub),
        installed = installedId != null,
        busy = lifecycle.busyIdentity == "plugin:${item.packageName}",
        actionEnabled = lifecycle.busyIdentity == null && lifecycle.canInstall,
        onOpen = installedId?.let { id -> { onOpenPlugin(id, item.displayName) } },
        onInstall = { onInstall(item) },
        leading = {
          PluginArtwork(
            displayName = item.displayName,
            artworkSource = item.artworkSource,
            onLoadArtwork = onLoadPluginArtwork,
            size = 40.dp,
          )
        },
      )
    }
  }
}

@Composable
private fun PluginDirectoryRow(
  title: String,
  value: String,
  installed: Boolean,
  busy: Boolean,
  actionEnabled: Boolean,
  onOpen: (() -> Unit)?,
  onInstall: () -> Unit,
  leading: @Composable () -> Unit,
) {
  val rowModifier = if (onOpen == null) Modifier else Modifier.clickable(onClick = onOpen)
  Row(
    modifier = rowModifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    leading()
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = title,
        style = ClawTheme.type.body,
        color = ClawTheme.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    when {
      busy ->
        Box(modifier = Modifier.size(ClawTheme.sizes.minimumTouchTarget), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = ClawTheme.colors.textMuted,
            strokeWidth = 2.dp,
          )
        }
      installed ->
        PluginDirectoryIconAction(
          icon = Icons.Default.MoreHoriz,
          contentDescription = stringResource(R.string.extension_open_plugin, title),
          onClick = onOpen ?: {},
          enabled = onOpen != null,
        )
      else ->
        PluginDirectoryIconAction(
          icon = Icons.Default.Add,
          contentDescription = stringResource(R.string.extension_install_plugin, title),
          onClick = onInstall,
          enabled = actionEnabled,
        )
    }
  }
}

@Composable
private fun PluginDirectoryIconAction(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  enabled: Boolean,
) {
  Surface(
    onClick = onClick,
    enabled = enabled,
    modifier =
      Modifier
        .size(ClawTheme.sizes.minimumTouchTarget)
        .semantics { this.contentDescription = contentDescription },
    shape = CircleShape,
    color = Color.Transparent,
    contentColor = if (enabled) ClawTheme.colors.text else ClawTheme.colors.textSubtle,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
    }
  }
}

@Composable
private fun McpConnectionsSection(
  state: PluginHubUiState,
  onAdd: (String, String, McpTransportChoice) -> Unit,
  onSetEnabled: (String, Boolean) -> Unit,
  onRemove: (String) -> Unit,
  onActivateConnector: (String) -> Unit,
  onRefreshMutation: () -> Unit,
  onDismissMutation: () -> Unit,
) {
  var name by rememberSaveable { mutableStateOf("") }
  var target by rememberSaveable { mutableStateOf("") }
  var formOpen by rememberSaveable { mutableStateOf(false) }
  var selectedServerName by rememberSaveable { mutableStateOf<String?>(null) }
  var pendingRemovalName by rememberSaveable { mutableStateOf<String?>(null) }
  var transportName by rememberSaveable { mutableStateOf(McpTransportChoice.StreamableHttp.name) }
  val transport = McpTransportChoice.entries.firstOrNull { it.name == transportName } ?: McpTransportChoice.StreamableHttp
  val selectedServer = state.mcp.servers.firstOrNull { it.name == selectedServerName }
  val availableConnectors = state.mcp.connectors.filter { it.addsServer && !it.alreadyAdded }

  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    ClawSectionHeader(
      title = stringResource(R.string.extension_connections),
      action = {
        ClawTextButton(
          text = stringResource(R.string.extension_connection_add),
          onClick = { formOpen = true },
          enabled = state.mcp.canMutate && state.mcp.busyIdentity == null,
        )
      },
    )
    state.mcp.notice?.let {
      PluginLifecycleNoticePanel(
        lifecycle = PluginLifecyclePresentation(notice = it),
        onRefresh = onRefreshMutation,
        onDismiss = onDismissMutation,
      )
    }
    state.mcp.errorText?.let { PluginInlineState(it, warning = true) }
    if (state.connected && state.mcp.readAvailable && !state.mcp.canMutate) {
      PluginInlineState(stringResource(R.string.extension_mcp_admin_required), warning = true)
    }
    when {
      !state.connected -> PluginInlineState(stringResource(R.string.extension_mcp_reconnect))
      !state.mcp.readAvailable -> PluginInlineState(stringResource(R.string.extension_mcp_update), warning = true)
      state.mcp.refreshing && state.mcp.servers.isEmpty() -> PluginInlineState(stringResource(R.string.extension_mcp_loading))
      state.mcp.servers.isEmpty() -> PluginInlineState(stringResource(R.string.extension_mcp_empty))
      else ->
        ClawListPanel(items = state.mcp.servers) { server ->
          McpServerRow(
            server = server,
            state = state.mcp,
            onOpen = { selectedServerName = server.name },
          )
        }
    }

    if (availableConnectors.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ClawSectionHeader(stringResource(R.string.extension_connection_available))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          items(items = availableConnectors, key = McpConnectorItem::id) { connector ->
            Surface(
              modifier = Modifier.width(300.dp),
              shape = RoundedCornerShape(ClawTheme.radii.panel),
              color = ClawTheme.colors.surface,
            ) {
              Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                McpConnectorRow(
                  connector = connector,
                  state = state.mcp,
                  onActivate = { onActivateConnector(connector.id) },
                )
              }
            }
          }
        }
      }
    }
  }

  if (formOpen) {
    ExtensionReviewSheet(
      title = stringResource(R.string.extension_mcp_add_title),
      intro = stringResource(R.string.extension_connection_manual_intro),
      confirmLabel = stringResource(R.string.extension_mcp_add_server),
      confirmEnabled = state.mcp.canMutate && state.mcp.busyIdentity == null && name.isNotBlank() && target.isNotBlank(),
      onConfirm = {
        onAdd(name, target, transport)
        formOpen = false
      },
      onDismiss = { formOpen = false },
    ) {
      ClawTextField(
        value = name,
        onValueChange = { name = it },
        placeholder = stringResource(R.string.extension_mcp_name_hint),
        label = stringResource(R.string.extension_mcp_name),
      )
      ClawTextField(
        value = target,
        onValueChange = { target = it },
        placeholder = nativeString("https://example.com/mcp"),
        label = stringResource(R.string.extension_mcp_endpoint),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        McpTransportChoice.entries.forEach { choice ->
          ClawPill(
            text = if (choice == McpTransportChoice.StreamableHttp) nativeString("HTTP") else nativeString("SSE"),
            selected = transport == choice,
            onClick = { transportName = choice.name },
          )
        }
      }
    }
  }

  selectedServer?.let { server ->
    ExtensionReviewSheet(
      title = server.name,
      intro = stringResource(R.string.extension_connection_configured_disclaimer),
      confirmLabel =
        if (server.enabled) {
          stringResource(R.string.extension_connection_disable)
        } else {
          stringResource(R.string.extension_connection_enable)
        },
      confirmEnabled = state.mcp.canMutate && state.mcp.busyIdentity == null,
      onConfirm = {
        onSetEnabled(server.name, !server.enabled)
        selectedServerName = null
      },
      onDismiss = { selectedServerName = null },
    ) {
      PluginFactsPanel(
        facts =
          listOf(
            PluginDetailFact(stringResource(R.string.extension_mcp_endpoint), server.target),
            PluginDetailFact(stringResource(R.string.extension_connection_transport), server.transportLabel),
            PluginDetailFact(
              stringResource(R.string.extension_connection_status),
              if (server.enabled) {
                stringResource(R.string.extension_connection_configured)
              } else {
                stringResource(R.string.extension_connection_disabled)
              },
            ),
          ),
      )
      ClawTextButton(
        text = stringResource(R.string.extension_connection_remove),
        onClick = {
          selectedServerName = null
          pendingRemovalName = server.name
        },
        enabled = state.mcp.canMutate && state.mcp.busyIdentity == null,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  pendingRemovalName?.let { serverName ->
    AlertDialog(
      onDismissRequest = { pendingRemovalName = null },
      title = { Text(stringResource(R.string.extension_connection_remove_title, serverName)) },
      text = { Text(stringResource(R.string.extension_connection_remove_message)) },
      confirmButton = {
        ClawTextButton(
          text = stringResource(R.string.extension_connection_remove),
          onClick = {
            onRemove(serverName)
            pendingRemovalName = null
          },
          enabled = state.mcp.canMutate && state.mcp.busyIdentity == null,
        )
      },
      dismissButton = { ClawTextButton(nativeString("Cancel"), { pendingRemovalName = null }) },
      containerColor = ClawTheme.colors.surface,
    )
  }
}

@Composable
private fun McpServerRow(
  server: McpServerItem,
  state: McpHubPresentation,
  onOpen: () -> Unit,
) {
  ClawDetailRow(
    title = server.name,
    subtitle = listOf(server.target, server.transportLabel).joinToString(" · "),
    modifier = Modifier.clickable(enabled = state.busyIdentity == null, onClick = onOpen),
    leading = { ClawTextBadge(pluginBadge(server.name)) },
    trailing = {
      ClawStatusPill(
        text =
          if (state.busyIdentity == "mcp:${server.name}") {
            state.busyLabel ?: nativeString("Saving…")
          } else if (server.enabled) {
            stringResource(R.string.extension_connection_configured)
          } else {
            stringResource(R.string.extension_connection_disabled)
          },
        status = if (server.enabled) ClawStatus.Neutral else ClawStatus.Warning,
      )
    },
  )
}

@Composable
private fun McpConnectorRow(
  connector: McpConnectorItem,
  state: McpHubPresentation,
  onActivate: () -> Unit,
) {
  ClawDetailRow(
    title = connector.name,
    subtitle =
      if (connector.requiresAuthentication) {
        "${connector.description} · ${stringResource(R.string.extension_connection_sign_in_after_adding)}"
      } else {
        connector.description
      },
    leading = { ClawTextBadge(pluginBadge(connector.name)) },
    trailing = {
      when {
        connector.alreadyAdded -> ClawStatusPill(stringResource(R.string.extension_connection_configured), ClawStatus.Neutral)
        state.busyIdentity == "mcp:${connector.id}" ->
          ClawTextButton(state.busyLabel ?: nativeString("Saving…"), onClick = {}, enabled = false)
        connector.addsServer ->
          ClawTextButton(
            stringResource(R.string.extension_connector_add),
            onActivate,
            enabled = state.canMutate && state.busyIdentity == null,
          )
        else -> Unit
      }
    },
  )
}

@Composable
internal fun PluginDetailScreen(
  state: PluginDetailUiState,
  contentPadding: PaddingValues,
  onRetryInspection: () -> Unit,
  lifecycle: PluginLifecyclePresentation,
  management: Boolean = false,
  onInstall: () -> Unit,
  onSetEnabled: (Boolean) -> Unit = {},
  onUninstall: () -> Unit = {},
  onRefreshLifecycle: () -> Unit,
  onDismissLifecycle: () -> Unit,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload? = { null },
) {
  val plugin = state.plugin
  if (plugin == null) {
    LazyColumn(contentPadding = contentPadding) {
      item {
        ExtensionDirectoryState(
          title = stringResource(R.string.extension_plugin_unavailable_title),
          message =
            if (state.connected) {
              stringResource(R.string.extension_plugin_no_longer_available)
            } else {
              stringResource(R.string.extension_plugin_reconnect_to_load)
            },
          actionLabel = stringResource(R.string.extension_refresh),
          onAction = onRetryInspection,
        )
      }
    }
    return
  }
  val useCases = pluginUseCases(plugin)

  Column(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      modifier = Modifier.weight(1f),
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
      item {
        PluginDetailHero(
          plugin = plugin,
          onLoadPluginArtwork = onLoadPluginArtwork,
        )
      }

      plugin.error?.let { error -> item { PluginInlineState(error, warning = true) } }
      lifecycle.notice?.let {
        item {
          PluginLifecycleNoticePanel(
            lifecycle = lifecycle,
            onRefresh = onRefreshLifecycle,
            onDismiss = onDismissLifecycle,
          )
        }
      }

      if (management && plugin.status != PluginInstallStatus.Available) {
        item {
          PluginManagementSection(
            plugin = plugin,
            lifecycle = lifecycle,
            onSetEnabled = onSetEnabled,
            onUninstall = onUninstall,
          )
        }
      }

      if (useCases.isNotEmpty()) {
        item { PluginUseCases(useCases) }
      }

      when {
        plugin.status == PluginInstallStatus.Available -> Unit
        !state.connected ->
          item {
            PluginInlineState(stringResource(R.string.extension_plugin_reconnect_to_inspect))
          }
        !state.inspectionAvailable ->
          item {
            PluginInlineState(
              stringResource(R.string.extension_plugin_update_to_inspect),
              warning = true,
            )
          }
        state.loading ->
          item {
            ExtensionDirectoryLoading(stringResource(R.string.extension_plugin_inspecting))
          }
        state.inspectionErrorText != null ->
          item {
            ExtensionDirectoryState(
              title = stringResource(R.string.extension_plugin_inspection_failed_title),
              message = state.inspectionErrorText,
              actionLabel = stringResource(R.string.extension_try_again),
              onAction = onRetryInspection,
              warning = true,
            )
          }
        state.inspection != null -> {
          if (state.inspection.includedSections.isNotEmpty()) {
            item { PluginIncludedSections(state.inspection.includedSections) }
          }
          if (state.inspection.includedSections.any { it.kind == PluginIncludedKind.Connections }) {
            item {
              Text(
                text = stringResource(R.string.extension_plugin_connections_separate),
                style = ClawTheme.type.caption,
                color = ClawTheme.colors.textMuted,
              )
            }
          }
        }
      }

      item {
        PluginInformationSection(
          plugin = plugin,
          sourceFacts = state.inspection?.sourceFacts.orEmpty(),
        )
      }

      if (management) {
        state.inspection?.trust?.let { trust ->
          item { PluginSecuritySection(trust) }
        }

        state.inspection?.let { inspection ->
          if (inspection.declaredSections.isNotEmpty() || inspection.grantSections.isNotEmpty()) {
            item { PluginTechnicalSections(inspection) }
          }
        }
      }
    }

    if (plugin.status == PluginInstallStatus.Available) {
      PluginInstallBar(
        plugin = plugin,
        lifecycle = lifecycle,
        onInstall = onInstall,
      )
    }
  }
}

@Composable
private fun PluginManagementSection(
  plugin: PluginCatalogItem,
  lifecycle: PluginLifecyclePresentation,
  onSetEnabled: (Boolean) -> Unit,
  onUninstall: () -> Unit,
) {
  val busy = lifecycle.busyIdentity != null
  val enabledDescription = stringResource(R.string.extension_plugin_enabled_description)
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_plugin_management))
    ClawListPanel(items = listOf(plugin)) {
      ClawDetailRow(
        title = stringResource(R.string.extension_plugin_enabled),
        subtitle = stringResource(R.string.extension_plugin_enabled_description),
        leading = { ClawTextBadge(pluginBadge(plugin.displayName)) },
        trailing = {
          Switch(
            checked = plugin.enabled,
            onCheckedChange = onSetEnabled,
            enabled = lifecycle.canSetEnabled && !busy,
            modifier = Modifier.semantics { contentDescription = enabledDescription },
          )
        },
      )
    }
    if (plugin.removable) {
      ClawTextButton(
        text = stringResource(R.string.extension_plugin_remove_action),
        onClick = onUninstall,
        enabled = lifecycle.canUninstall && !busy,
        modifier = Modifier.fillMaxWidth(),
      )
    } else {
      Text(
        text = stringResource(R.string.extension_plugin_bundled_not_removable),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
      )
    }
  }
}

@Composable
private fun PluginDetailHero(
  plugin: PluginCatalogItem,
  onLoadPluginArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
) {
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    PluginArtwork(plugin = plugin, onLoadArtwork = onLoadPluginArtwork, size = 56.dp)
    Text(text = plugin.displayName, style = ClawTheme.type.display, color = ClawTheme.colors.text)
    plugin.summary?.let { summary ->
      Text(text = summary, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
    ClawStatusPill(text = pluginDetailStatusLabel(plugin), status = pluginStatusColor(plugin))
  }
}

@Composable
private fun pluginUseCases(plugin: PluginCatalogItem): List<String> =
  plugin.kinds
    .mapNotNull { kind ->
      when (kind.lowercase()) {
        "skill", "skills" -> stringResource(R.string.extension_plugin_adds_workflows)
        "tool", "tools" -> stringResource(R.string.extension_plugin_adds_tools)
        "provider", "providers" -> stringResource(R.string.extension_plugin_adds_provider)
        "channel", "channels" -> stringResource(R.string.extension_plugin_adds_channel)
        "memory" -> stringResource(R.string.extension_plugin_adds_memory)
        else -> null
      }
    }.distinct()

@Composable
private fun PluginUseCases(useCases: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_plugin_what_it_adds))
    useCases.forEach { useCase ->
      Text(text = useCase, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
  }
}

@Composable
private fun PluginIncludedSections(sections: List<PluginIncludedSection>) {
  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_plugin_included))
    sections.forEach { section ->
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = section.title, style = ClawTheme.type.label, color = ClawTheme.colors.text)
        section.items.forEach { item ->
          Text(text = item, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
        }
      }
    }
  }
}

@Composable
private fun PluginInformationSection(
  plugin: PluginCatalogItem,
  sourceFacts: List<PluginDetailFact>,
) {
  val facts =
    buildList {
      plugin.version?.let { add(PluginDetailFact(stringResource(R.string.extension_plugin_version), it)) }
      add(
        PluginDetailFact(
          stringResource(R.string.extension_plugin_source),
          pluginOriginLabel(plugin.origin),
        ),
      )
      add(
        PluginDetailFact(
          stringResource(R.string.extension_plugin_category),
          plugin.categories.joinToString().ifBlank { pluginCategoryLabel("other") },
        ),
      )
      plugin.packageName?.let { add(PluginDetailFact(stringResource(R.string.extension_plugin_package), it)) }
      add(PluginDetailFact(stringResource(R.string.extension_plugin_identifier), plugin.pluginId))
      addAll(sourceFacts)
    }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_plugin_information))
    facts.forEachIndexed { index, fact ->
      PluginInformationRow(fact)
      if (index != facts.lastIndex) HorizontalDivider(color = ClawTheme.colors.border.copy(alpha = 0.72f))
    }
  }
}

@Composable
private fun pluginOriginLabel(origin: String?): String =
  when (origin?.lowercase()) {
    "official" -> nativeString("OpenClaw")
    "clawhub" -> nativeString("ClawHub")
    null -> nativeString("Unknown")
    else -> origin
  }

@Composable
private fun PluginInformationRow(fact: PluginDetailFact) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = fact.label,
      style = ClawTheme.type.body,
      color = ClawTheme.colors.textMuted,
      modifier = Modifier.weight(0.42f),
    )
    Text(
      text = fact.value,
      style = ClawTheme.type.body,
      color = ClawTheme.colors.text,
      modifier = Modifier.weight(0.58f),
    )
  }
}

@Composable
private fun PluginSecuritySection(trust: PluginTrustPresentation) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_plugin_security))
    ClawStatusPill(
      text = trust.label,
      status = if (trust.warning) ClawStatus.Warning else ClawStatus.Success,
    )
    if (trust.facts.isNotEmpty()) PluginFactsPanel(facts = trust.facts)
  }
}

@Composable
private fun PluginTechnicalSections(inspection: PluginInspectionPresentation) {
  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_plugin_technical_details))
    if (inspection.declaredSections.isNotEmpty()) {
      PluginSectionCollection(
        title = nativeString("Declared capabilities"),
        sections = inspection.declaredSections,
        emptyText = "",
      )
    }
    if (inspection.grantSections.isNotEmpty()) {
      PluginSectionCollection(
        title = nativeString("Permissions"),
        sections = inspection.grantSections,
        emptyText = "",
      )
    }
  }
}

@Composable
private fun PluginInstallBar(
  plugin: PluginCatalogItem,
  lifecycle: PluginLifecyclePresentation,
  onInstall: () -> Unit,
) {
  Surface(color = ClawTheme.colors.canvas) {
    ClawPrimaryButton(
      text =
        if (lifecycle.busyIdentity == plugin.installIdentity()) {
          lifecycle.busyLabel ?: nativeString("Installing…")
        } else {
          stringResource(R.string.extension_plugin_install_action)
        },
      onClick = onInstall,
      enabled = lifecycle.canInstall && lifecycle.busyIdentity == null,
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
    )
  }
}

@Composable
internal fun PluginMutationDialog(
  dialog: PluginMutationDialogPresentation?,
  onDismiss: () -> Unit,
  onConfirmMutation: () -> Unit,
  onConfirmPolicy: () -> Unit,
  onConfirmCapabilities: () -> Unit,
) {
  when (dialog) {
    null -> Unit
    is PluginMutationDialogPresentation.Confirmation ->
      AlertDialog(
        onDismissRequest = onDismiss,
        title = {
          Text(
            stringResource(
              when (dialog.action) {
                PluginMutationDialogPresentation.ConfirmationAction.Install ->
                  R.string.extension_plugin_install_confirm_title
                PluginMutationDialogPresentation.ConfirmationAction.Remove ->
                  R.string.extension_plugin_remove_confirm_title
              },
              dialog.displayName,
            ),
          )
        },
        text = {
          Text(
            stringResource(
              when (dialog.action) {
                PluginMutationDialogPresentation.ConfirmationAction.Install ->
                  R.string.extension_plugin_install_restart_disclosure
                PluginMutationDialogPresentation.ConfirmationAction.Remove ->
                  R.string.extension_plugin_remove_restart_disclosure
              },
            ),
          )
        },
        confirmButton = {
          ClawTextButton(
            stringResource(
              when (dialog.action) {
                PluginMutationDialogPresentation.ConfirmationAction.Install ->
                  R.string.extension_plugin_install_confirm_action
                PluginMutationDialogPresentation.ConfirmationAction.Remove ->
                  R.string.extension_plugin_remove_confirm_action
              },
            ),
            onConfirmMutation,
          )
        },
        dismissButton = { ClawTextButton(nativeString("Cancel"), onDismiss) },
        containerColor = ClawTheme.colors.surface,
      )
    is PluginMutationDialogPresentation.PolicyReview ->
      ExtensionReviewSheet(
        title = stringResource(R.string.extension_plugin_review_title, dialog.displayName),
        intro = dialog.reason,
        confirmLabel = stringResource(R.string.extension_plugin_install_anyway),
        onConfirm = onConfirmPolicy,
        onDismiss = onDismiss,
      ) {
        if (dialog.findings.isNotEmpty()) PluginFactsPanel(facts = dialog.findings)
      }
    is PluginMutationDialogPresentation.CapabilityReview ->
      ExtensionReviewSheet(
        title = stringResource(R.string.extension_plugin_review_title, dialog.displayName),
        intro = stringResource(R.string.extension_plugin_review_intro),
        confirmLabel =
          stringResource(
            when (dialog.action) {
              PluginMutationDialogPresentation.CapabilityReviewAction.Install ->
                R.string.extension_plugin_allow_install
              PluginMutationDialogPresentation.CapabilityReviewAction.Enable ->
                R.string.extension_plugin_allow_enable
            },
          ),
        onConfirm = onConfirmCapabilities,
        onDismiss = onDismiss,
      ) {
        if (dialog.widenedSections.isNotEmpty()) {
          PluginSectionCollection(
            title = stringResource(R.string.extension_plugin_new_capabilities),
            sections = dialog.widenedSections,
            emptyText = "",
          )
        }
        dialog.acceptedAt?.let {
          PluginInlineState(stringResource(R.string.extension_plugin_previously_accepted, it), warning = true)
        }
        if (dialog.inspection.includedSections.isNotEmpty()) {
          PluginIncludedSections(dialog.inspection.includedSections)
        }
        if (dialog.inspection.sourceFacts.isNotEmpty()) {
          PluginFactsPanel(stringResource(R.string.extension_plugin_source_integrity), dialog.inspection.sourceFacts)
        }
        PluginSectionCollection(
          title = stringResource(R.string.extension_plugin_declared_capabilities),
          sections = dialog.inspection.declaredSections,
          emptyText = stringResource(R.string.extension_plugin_no_declared_capabilities),
        )
        PluginSectionCollection(
          title = stringResource(R.string.extension_plugin_permissions),
          sections = dialog.inspection.grantSections,
          emptyText = stringResource(R.string.extension_plugin_no_operator_grants),
        )
        dialog.inspection.trust?.let { trust -> PluginSecuritySection(trust) }
      }
  }
}

@Composable
private fun PluginSectionCollection(
  title: String,
  sections: List<PluginDetailSection>,
  emptyText: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    ClawSectionHeader(title)
    if (sections.isEmpty()) {
      PluginInlineState(emptyText)
    } else {
      sections.forEach { section -> PluginFactsPanel(title = section.title, facts = section.facts) }
    }
  }
}

@Composable
private fun PluginFactsPanel(
  title: String? = null,
  facts: List<PluginDetailFact>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    title?.let { ClawSectionHeader(it) }
    ClawPanel(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
      facts.forEachIndexed { index, fact ->
        if (index > 0) HorizontalDivider(color = ClawTheme.colors.border)
        PluginDetailValue(fact.label, fact.value)
      }
    }
  }
}

@Composable
private fun PluginRow(
  plugin: PluginCatalogItem,
  onOpen: () -> Unit,
  lifecycle: PluginLifecyclePresentation? = null,
  onLoadArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  onInstall: (() -> Unit)? = null,
  onSetEnabled: (() -> Unit)? = null,
) {
  ClawDetailRow(
    title = plugin.displayName,
    subtitle = listOfNotNull(plugin.summary, plugin.origin).joinToString(" · ").ifBlank { plugin.pluginId },
    modifier = Modifier.clickable(onClick = onOpen),
    leading = { PluginArtwork(plugin, onLoadArtwork) },
    trailing = {
      when {
        plugin.status == PluginInstallStatus.Available && onInstall != null && lifecycle != null ->
          ClawTextButton(
            text =
              if (lifecycle.busyIdentity == plugin.installIdentity()) {
                lifecycle.busyLabel ?: nativeString("Installing…")
              } else {
                nativeString("Install")
              },
            onClick = onInstall,
            enabled = lifecycle.canInstall && lifecycle.busyIdentity == null,
          )
        onSetEnabled != null && lifecycle != null ->
          ClawTextButton(
            text =
              if (lifecycle.busyIdentity == plugin.pluginIdentity()) {
                lifecycle.busyLabel ?: nativeString("Updating…")
              } else if (plugin.enabled) {
                nativeString("Disable")
              } else {
                nativeString("Enable")
              },
            onClick = onSetEnabled,
            enabled = lifecycle.canSetEnabled && lifecycle.busyIdentity == null,
          )
        else -> ClawStatusPill(pluginStatusLabel(plugin), pluginStatusColor(plugin))
      }
    },
  )
}

@Composable
private fun PluginArtwork(
  plugin: PluginCatalogItem,
  onLoadArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  size: Dp = 30.dp,
) {
  PluginArtwork(
    displayName = plugin.displayName,
    artworkSource = plugin.artworkSource,
    onLoadArtwork = onLoadArtwork,
    size = size,
  )
}

@Composable
private fun PluginArtwork(
  displayName: String,
  artworkSource: PluginArtworkSource,
  onLoadArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload?,
  size: Dp,
) {
  if (artworkSource == PluginArtworkSource.None) {
    PluginFallbackArtwork(displayName, size)
    return
  }
  var payload by remember(artworkSource) { mutableStateOf<PluginArtworkPayload?>(null) }
  LaunchedEffect(artworkSource) { payload = onLoadArtwork(artworkSource) }
  val resolved = payload
  if (resolved == null) {
    PluginFallbackArtwork(displayName, size)
    return
  }
  val context = LocalPlatformContext.current
  val request =
    remember(resolved.bytes, context) {
      ImageRequest
        .Builder(context)
        .data(resolved.bytes)
        .size(128)
        .build()
    }
  val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.Fit)
  val painterState by painter.state.collectAsState()
  if (painterState !is AsyncImagePainter.State.Success) {
    PluginFallbackArtwork(displayName, size)
  } else {
    Image(
      painter = painter,
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)),
    )
  }
}

@Composable
private fun PluginFallbackArtwork(
  displayName: String,
  size: Dp,
) {
  Surface(
    modifier = Modifier.size(size),
    shape = RoundedCornerShape(10.dp),
    color = ClawTheme.colors.surfacePressed,
    contentColor = ClawTheme.colors.text,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
        text = pluginBadge(displayName),
        style = if (size <= 28.dp) ClawTheme.type.captionSmall else ClawTheme.type.label,
        maxLines = 1,
      )
    }
  }
}

@Composable
internal fun PluginLifecycleNoticePanel(
  lifecycle: PluginLifecyclePresentation,
  onRefresh: () -> Unit,
  onDismiss: () -> Unit,
) {
  val notice = lifecycle.notice ?: return
  ClawPanel {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = notice.text,
        style = ClawTheme.type.body,
        color =
          when (notice.tone) {
            PluginLifecycleNoticeTone.Success -> ClawTheme.colors.success
            PluginLifecycleNoticeTone.Warning -> ClawTheme.colors.warning
            PluginLifecycleNoticeTone.Error -> ClawTheme.colors.danger
          },
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (notice.refreshAction) ClawTextButton(nativeString("Refresh"), onRefresh)
        ClawTextButton(nativeString("Dismiss"), onDismiss)
      }
    }
  }
}

private fun PluginCatalogItem.installIdentity(): String? = installReference?.let { "plugin:$it" }

internal fun PluginCatalogItem.pluginIdentity(): String = "plugin:$pluginId"

@Composable
private fun PluginInlineState(
  text: String,
  warning: Boolean = false,
) {
  ClawPanel {
    Text(
      text = text,
      style = ClawTheme.type.body,
      color = if (warning) ClawTheme.colors.warning else ClawTheme.colors.textMuted,
    )
  }
}

@Composable
private fun PluginDetailValue(
  label: String,
  value: String,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(text = label, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
    Text(text = value, style = ClawTheme.type.body, color = ClawTheme.colors.text)
  }
}

private fun matchesPlugin(
  plugin: PluginCatalogItem,
  query: String,
): Boolean {
  val needle = query.trim().lowercase()
  if (needle.isEmpty()) return true
  return listOfNotNull(
    plugin.displayName,
    plugin.pluginId,
    plugin.packageName,
    plugin.summary,
    plugin.origin,
  ).plus(plugin.categories).plus(plugin.kinds).any { it.lowercase().contains(needle) }
}

private fun pluginStatusLabel(plugin: PluginCatalogItem): String =
  when (plugin.status) {
    PluginInstallStatus.Available -> nativeString("Available")
    PluginInstallStatus.Installed -> nativeString("Disabled")
    PluginInstallStatus.Ready -> nativeString("Enabled")
    PluginInstallStatus.Failed -> nativeString("Issue")
    PluginInstallStatus.Reviewing -> nativeString("Reviewing")
    PluginInstallStatus.Installing -> nativeString("Installing")
    PluginInstallStatus.NeedsSetup -> nativeString("Needs setup")
    PluginInstallStatus.Incompatible -> nativeString("Incompatible")
    PluginInstallStatus.UnknownOutcome -> nativeString("Refresh needed")
  }

@Composable
private fun pluginDetailStatusLabel(plugin: PluginCatalogItem): String =
  when (plugin.status) {
    PluginInstallStatus.Available -> nativeString("Available")
    PluginInstallStatus.Installed -> stringResource(R.string.extension_plugin_installed_disabled)
    PluginInstallStatus.Ready -> stringResource(R.string.extension_plugin_ready_enabled)
    PluginInstallStatus.Failed -> stringResource(R.string.extension_plugin_installed_attention)
    PluginInstallStatus.Reviewing -> nativeString("Reviewing")
    PluginInstallStatus.Installing -> nativeString("Installing")
    PluginInstallStatus.NeedsSetup -> nativeString("Needs setup")
    PluginInstallStatus.Incompatible -> nativeString("Incompatible")
    PluginInstallStatus.UnknownOutcome -> nativeString("Refresh needed")
  }

private fun pluginStatusColor(plugin: PluginCatalogItem): ClawStatus =
  when (plugin.status) {
    PluginInstallStatus.Ready -> ClawStatus.Success
    PluginInstallStatus.Failed,
    PluginInstallStatus.UnknownOutcome,
    -> ClawStatus.Warning
    else -> ClawStatus.Neutral
  }

private fun pluginCategoryLabel(category: String): String =
  when (category) {
    "channel" -> nativeString("Channels")
    "provider" -> nativeString("Providers")
    "memory" -> nativeString("Memory")
    "context-engine" -> nativeString("Context")
    "tool" -> nativeString("Tools")
    else -> nativeString("Other")
  }

private val categoryOrder = listOf("channel", "provider", "memory", "context-engine", "tool", "other")
private val categoryComparator = compareBy<String> { categoryOrder.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }.thenBy { it }
