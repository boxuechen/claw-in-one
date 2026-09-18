package ai.openclaw.app.ui.extensions

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.mcp.ConnectionFeature
import ai.openclaw.app.mcp.ConnectionState
import ai.openclaw.app.mcp.GatewayMcpConfigState
import ai.openclaw.app.mcp.GatewayMcpMutationState
import ai.openclaw.app.mcp.McpConnectorAction
import ai.openclaw.app.mcp.McpConnectorFollowUp
import ai.openclaw.app.mcp.McpConnectorGroup
import ai.openclaw.app.mcp.McpServerTransport
import ai.openclaw.app.mcp.pinnedConnectorSuggestions
import ai.openclaw.app.plugin.GatewayPluginCatalogEntry
import ai.openclaw.app.plugin.GatewayPluginDeclaredSurface
import ai.openclaw.app.plugin.GatewayPluginHookGrant
import ai.openclaw.app.plugin.GatewayPluginInspectionDetails
import ai.openclaw.app.plugin.GatewayPluginInspectionState
import ai.openclaw.app.plugin.GatewayPluginInstallAction
import ai.openclaw.app.plugin.GatewayPluginModelGrants
import ai.openclaw.app.plugin.GatewayPluginMutationIntent
import ai.openclaw.app.plugin.GatewayPluginMutationStage
import ai.openclaw.app.plugin.GatewayPluginMutationState
import ai.openclaw.app.plugin.GatewayPluginOperatorGrants
import ai.openclaw.app.plugin.GatewayPluginState
import ai.openclaw.app.plugin.GatewayPluginTrust
import ai.openclaw.app.plugin.GatewayPluginTrustDisposition
import ai.openclaw.app.plugin.PluginFeature
import ai.openclaw.app.plugin.PluginState
import ai.openclaw.app.plugin.catalog.OfficialPluginSearchMatch
import ai.openclaw.app.plugin.catalog.PluginCatalogFailure
import ai.openclaw.app.plugin.catalog.PluginCatalogSnapshot
import ai.openclaw.app.plugin.catalog.PluginDirectoryFeature
import ai.openclaw.app.plugin.catalog.PluginLocalState
import ai.openclaw.app.plugin.catalog.overlayPluginLocalState
import ai.openclaw.app.uppercaseFirstGraphemeOrNull
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Reconciles the read-only ClawHub Plugin directory with Gateway lifecycle state. */
@Composable
internal fun PluginHubRoute(
  plugin: PluginFeature?,
  directory: PluginDirectoryFeature,
  connections: ConnectionFeature?,
  destination: ExtensionCenterDestination,
  contentPadding: PaddingValues,
  onOpenPlugin: (String, String) -> Unit,
  onOpenCategory: (PluginDirectorySectionId) -> Unit,
) {
  val pluginState = plugin?.state?.collectAsState()?.value ?: PluginState()
  val summary = pluginState.summary
  val inventoryRefreshing = pluginState.refreshing
  val inventoryErrorText = pluginState.errorText
  val inventoryAvailable = pluginState.capabilities.inventory
  val directoryState by directory.state.collectAsState()
  val inspectAvailable = pluginState.capabilities.inspect
  val inspectionState = pluginState.inspection
  val installAvailable = pluginState.capabilities.install
  val setEnabledAvailable = pluginState.capabilities.setEnabled
  val uninstallAvailable = pluginState.capabilities.uninstall
  val mutationState = pluginState.mutation
  val connectionState = connections?.state?.collectAsState()?.value ?: ConnectionState()
  val mcpState = connectionState.config
  val mcpReadAvailable = connectionState.readAvailable
  val mcpPatchAvailable = connectionState.patchAvailable
  val operatorAdmin = pluginState.adminScope
  val connected = pluginState.connected
  val plugins = overlayPluginLocalState(directoryState.items, summary.plugins).map(PluginCatalogSnapshot::toPresentationItem)
  val managedPlugins = summary.plugins.map(GatewayPluginCatalogEntry::toPresentationItem)
  val state =
    PluginHubUiState(
      connected = connected,
      inventoryAvailable = inventoryAvailable,
      catalogRefreshing = directoryState.refreshing,
      catalogLoadingNextPage = directoryState.loadingNextPage,
      catalogCanLoadNextPage = directoryState.nextCursor != null,
      catalogErrorText = directoryState.error?.toPresentationText(),
      inventoryRefreshing = inventoryRefreshing,
      inventoryErrorText = inventoryErrorText,
      mutationAllowed = summary.mutationAllowed,
      diagnosticsCount = summary.diagnosticsCount,
      plugins = plugins,
      managedPlugins = managedPlugins,
      searchQuery = directoryState.query,
      searching = directoryState.searching,
      searchResults = directoryState.searchResults.map { it.toPresentationItem(summary.plugins) },
      searchErrorText = directoryState.searchError?.toPresentationText(),
      lifecycle =
        pluginLifecyclePresentation(
          state = mutationState,
          canInstall = connected && operatorAdmin && installAvailable && summary.mutationAllowed,
          canSetEnabled = connected && operatorAdmin && setEnabledAvailable && summary.mutationAllowed,
          canUninstall = connected && operatorAdmin && uninstallAvailable && summary.mutationAllowed,
        ),
      mcp =
        mcpHubPresentation(
          state = mcpState,
          readAvailable = mcpReadAvailable,
          canMutate = connectionState.connected && connectionState.adminScope && mcpPatchAvailable,
        ),
    )
  val loadArtwork: suspend (PluginArtworkSource) -> PluginArtworkPayload? = { source ->
    when (source) {
      PluginArtworkSource.None -> null
      is PluginArtworkSource.Gateway ->
        plugin?.loadIcon?.invoke(source.pluginId)?.let {
          PluginArtworkPayload(bytes = it.bytes, contentType = it.contentType)
        }
      is PluginArtworkSource.ClawHub ->
        directory.loadArtwork(source.packageName)?.let {
          PluginArtworkPayload(bytes = it.bytes, contentType = it.contentType)
        }
    }
  }

  LaunchedEffect(Unit) { directory.actions.refresh() }
  LaunchedEffect(connected, inventoryAvailable) {
    if (connected && inventoryAvailable) plugin?.actions?.refresh?.invoke()
  }
  LaunchedEffect(connectionState.connected, mcpReadAvailable) {
    if (connectionState.connected && mcpReadAvailable) connections?.actions?.refresh?.invoke()
  }

  when (destination) {
    is ExtensionCenterDestination.PluginDetail -> {
      val detailPlugin =
        (plugins + managedPlugins).firstOrNull {
          it.pluginId == destination.pluginId || it.packageName == destination.pluginId
        }
      LaunchedEffect(destination.pluginId, detailPlugin?.status, connected, inspectAvailable) {
        if (
          connected &&
          inspectAvailable &&
          detailPlugin?.status != PluginInstallStatus.Available
        ) {
          plugin?.actions?.inspect?.invoke(destination.pluginId)
        }
      }
      PluginDetailScreen(
        state =
          pluginDetailUiState(
            pluginId = destination.pluginId,
            plugin = detailPlugin,
            connected = connected,
            inspectAvailable = inspectAvailable,
            inspectionState = inspectionState,
          ),
        contentPadding = contentPadding,
        onRetryInspection = { plugin?.actions?.inspect?.invoke(destination.pluginId) },
        lifecycle = state.lifecycle,
        management = destination.origin == PluginDetailOrigin.Settings,
        onInstall = { detailPlugin?.toInstallIntent()?.let { plugin?.actions?.requestInstall?.invoke(it) } },
        onSetEnabled = { enabled ->
          detailPlugin?.let { plugin?.actions?.requestSetEnabled?.invoke(it.pluginId, it.displayName, enabled) }
        },
        onUninstall = {
          detailPlugin?.let { plugin?.actions?.requestUninstall?.invoke(it.pluginId, it.displayName) }
        },
        onRefreshLifecycle = { plugin?.actions?.reconcileMutation?.invoke() },
        onDismissLifecycle = { plugin?.actions?.dismissMutation?.invoke() },
        onLoadPluginArtwork = loadArtwork,
      )
    }
    is ExtensionCenterDestination.Directory,
    is ExtensionCenterDestination.PluginCategory,
    is ExtensionCenterDestination.PluginSettings,
    ->
      PluginHubScreen(
        surface =
          when (destination) {
            is ExtensionCenterDestination.Directory -> PluginHubSurface.Directory
            is ExtensionCenterDestination.PluginCategory -> PluginHubSurface.Category(destination.sectionId)
            is ExtensionCenterDestination.PluginSettings -> PluginHubSurface.Management
          },
        state = state,
        contentPadding = contentPadding,
        onRefresh = {
          directory.actions.refresh()
          plugin?.actions?.refresh?.invoke()
          connections?.actions?.refresh?.invoke()
        },
        onSearch = directory.actions.search,
        onOpenPlugin = onOpenPlugin,
        onOpenCategory = onOpenCategory,
        onLoadNextPage = directory.actions.loadNextPage,
        onLoadPluginArtwork = loadArtwork,
        onInstallPlugin = { item ->
          item.toInstallIntent()?.let { intent -> plugin?.actions?.requestInstall?.invoke(intent) }
        },
        onInstallSearchResult = { result ->
          plugin?.actions?.requestInstall?.invoke(
            GatewayPluginMutationIntent.Install(
              action = GatewayPluginInstallAction.ClawHub(result.packageName),
              displayName = result.displayName,
              version = result.latestVersion,
            ),
          )
        },
        onAddMcpServer = { name, target, transport ->
          connections?.actions?.addHttpServer?.invoke(
            name,
            target,
            when (transport) {
              McpTransportChoice.StreamableHttp -> McpServerTransport.StreamableHttp
              McpTransportChoice.Sse -> McpServerTransport.Sse
            },
          )
        },
        onSetMcpEnabled = { name, enabled -> connections?.actions?.setEnabled?.invoke(name, enabled) },
        onRemoveMcpServer = { name -> connections?.actions?.remove?.invoke(name) },
        onActivateMcpConnector = { connectorId ->
          pinnedConnectorSuggestions
            .firstOrNull { it.id == connectorId }
            ?.action
            ?.let { it as? McpConnectorAction.AddMcp }
            ?.template
            ?.let { template -> connections?.actions?.addConnector?.invoke(template) }
        },
        onRefreshMcpMutation = { connections?.actions?.reconcileMutation?.invoke() },
        onDismissMcpMutation = { connections?.actions?.dismissMutation?.invoke() },
        onRefreshLifecycle = { plugin?.actions?.reconcileMutation?.invoke() },
        onDismissLifecycle = { plugin?.actions?.dismissMutation?.invoke() },
      )
    is ExtensionCenterDestination.SkillDetail,
    is ExtensionCenterDestination.BuiltInCapabilities,
    -> Unit
  }

  PluginMutationDialog(
    dialog = state.lifecycle.dialog,
    onDismiss = { plugin?.actions?.dismissMutation?.invoke() },
    onConfirmMutation = { plugin?.actions?.confirmMutation?.invoke() },
    onConfirmPolicy = { plugin?.actions?.confirmInstallPolicy?.invoke() },
    onConfirmCapabilities = { plugin?.actions?.confirmCapabilities?.invoke() },
  )
}

internal fun mcpHubPresentation(
  state: GatewayMcpConfigState,
  readAvailable: Boolean,
  canMutate: Boolean,
): McpHubPresentation {
  val names = state.summary.servers.mapTo(mutableSetOf()) { it.name }
  val mutation = state.mutation
  return McpHubPresentation(
    readAvailable = readAvailable,
    canMutate = canMutate,
    refreshing = state.refreshing,
    errorText = state.errorText,
    servers =
      state.summary.servers.map { server ->
        McpServerItem(
          name = server.name,
          target = server.target.ifBlank { nativeString("Invalid configuration") },
          transportLabel =
            when (server.transport) {
              McpServerTransport.StreamableHttp -> nativeString("Streamable HTTP")
              McpServerTransport.Sse -> nativeString("SSE")
              McpServerTransport.Stdio -> nativeString("Local command")
              McpServerTransport.Invalid -> nativeString("Invalid")
            },
          enabled = server.enabled,
          detail =
            buildList {
              server.auth?.let { add("Auth: $it") }
              if (server.toolFilter) add(nativeString("Tool filter"))
              if (server.parallel) add(nativeString("Parallel tools"))
              server.tls?.let { add("TLS: $it") }
            }.joinToString(" · ").ifBlank { null },
        )
      },
    connectors =
      pinnedConnectorSuggestions.map { suggestion ->
        McpConnectorItem(
          id = suggestion.id,
          name = suggestion.name,
          description = suggestion.description,
          group = suggestion.group.toPresentationGroup(),
          addsServer = suggestion.action is McpConnectorAction.AddMcp,
          alreadyAdded = suggestion.id in names,
          requiresAuthentication =
            (suggestion.action as? McpConnectorAction.AddMcp)?.template?.followUp == McpConnectorFollowUp.OAuth,
        )
      },
    busyIdentity = (mutation as? GatewayMcpMutationState.Working)?.intent?.identity,
    busyLabel = (mutation as? GatewayMcpMutationState.Working)?.let { nativeString("Saving…") },
    notice =
      when (mutation) {
        GatewayMcpMutationState.Idle,
        is GatewayMcpMutationState.Working,
        -> null
        is GatewayMcpMutationState.Succeeded ->
          PluginLifecycleNotice(mutation.message, PluginLifecycleNoticeTone.Success)
        is GatewayMcpMutationState.Failed ->
          PluginLifecycleNotice(mutation.message, PluginLifecycleNoticeTone.Error)
        is GatewayMcpMutationState.UnknownOutcome ->
          PluginLifecycleNotice(mutation.message, PluginLifecycleNoticeTone.Warning, refreshAction = true)
      },
  )
}

private fun McpConnectorGroup.toPresentationGroup(): McpConnectorGroupPresentation =
  when (this) {
    McpConnectorGroup.Work -> McpConnectorGroupPresentation.Work
    McpConnectorGroup.Dev -> McpConnectorGroupPresentation.Development
    McpConnectorGroup.Home -> McpConnectorGroupPresentation.Home
    McpConnectorGroup.Life -> McpConnectorGroupPresentation.Life
  }

internal fun pluginLifecyclePresentation(
  state: GatewayPluginMutationState,
  canInstall: Boolean,
  canSetEnabled: Boolean,
  canUninstall: Boolean,
): PluginLifecyclePresentation {
  val base =
    PluginLifecyclePresentation(
      canInstall = canInstall,
      canSetEnabled = canSetEnabled,
      canUninstall = canUninstall,
    )
  return when (state) {
    GatewayPluginMutationState.Idle -> base
    is GatewayPluginMutationState.Confirmation ->
      base.copy(
        dialog =
          when (val intent = state.intent) {
            is GatewayPluginMutationIntent.Install ->
              PluginMutationDialogPresentation.Confirmation(
                displayName = intent.displayName,
                action = PluginMutationDialogPresentation.ConfirmationAction.Install,
              )
            is GatewayPluginMutationIntent.Uninstall ->
              PluginMutationDialogPresentation.Confirmation(
                displayName = intent.displayName,
                action = PluginMutationDialogPresentation.ConfirmationAction.Remove,
              )
            is GatewayPluginMutationIntent.SetEnabled -> error("Enablement does not require confirmation")
          },
      )
    is GatewayPluginMutationState.Working ->
      base.copy(
        busyIdentity = state.intent.identity,
        busyLabel =
          when (state.stage) {
            GatewayPluginMutationStage.Installing -> nativeString("Installing…")
            GatewayPluginMutationStage.Enabling -> nativeString("Enabling…")
            GatewayPluginMutationStage.Disabling -> nativeString("Disabling…")
            GatewayPluginMutationStage.Removing -> nativeString("Removing…")
            GatewayPluginMutationStage.Inspecting -> nativeString("Loading review…")
            GatewayPluginMutationStage.Reconciling -> nativeString("Refreshing…")
            GatewayPluginMutationStage.WaitingForRestart -> nativeString("Waiting for current work…")
            GatewayPluginMutationStage.Restarting -> nativeString("Applying configuration…")
            GatewayPluginMutationStage.Reconnecting -> nativeString("Reconnecting to OpenClaw…")
          },
      )
    is GatewayPluginMutationState.PolicyReview ->
      base.copy(
        dialog =
          PluginMutationDialogPresentation.PolicyReview(
            displayName = state.intent.displayName,
            reason = state.challenge.reason,
            findings =
              state.challenge.findings.map { finding ->
                PluginDetailFact(
                  label = finding.severity.name,
                  value =
                    listOfNotNull(
                      finding.message,
                      finding.file?.let { file -> finding.line?.let { "$file:$it" } ?: file },
                      finding.evidence,
                    ).joinToString(" · "),
                )
              },
          ),
      )
    is GatewayPluginMutationState.CapabilityReview ->
      base.copy(
        dialog =
          PluginMutationDialogPresentation.CapabilityReview(
            displayName = state.intent.displayName,
            inspection = state.inspection.toPresentation(),
            widenedSections = state.widened?.toPresentationSections().orEmpty(),
            acceptedAt = state.acceptedAt,
            action =
              if (state.intent is GatewayPluginMutationIntent.Install) {
                PluginMutationDialogPresentation.CapabilityReviewAction.Install
              } else {
                PluginMutationDialogPresentation.CapabilityReviewAction.Enable
              },
          ),
      )
    is GatewayPluginMutationState.Succeeded ->
      base.copy(
        notice =
          PluginLifecycleNotice(
            text =
              buildList {
                add(state.intent.successMessage())
                if (state.removed.isNotEmpty()) add("Removed ${state.removed.size} managed entries.")
                addAll(state.warnings)
              }.joinToString(" "),
            tone = PluginLifecycleNoticeTone.Success,
          ),
      )
    is GatewayPluginMutationState.Failed ->
      base.copy(
        notice = PluginLifecycleNotice(state.message, PluginLifecycleNoticeTone.Error),
      )
    is GatewayPluginMutationState.UnknownOutcome ->
      base.copy(
        notice =
          PluginLifecycleNotice(
            text = state.message,
            tone = PluginLifecycleNoticeTone.Warning,
            refreshAction = true,
          ),
      )
  }
}

private fun GatewayPluginMutationIntent.successMessage(): String =
  when (this) {
    is GatewayPluginMutationIntent.Install -> nativeString("Installed \$slug.", displayName)
    is GatewayPluginMutationIntent.SetEnabled ->
      if (enabled) "$displayName enabled." else "$displayName disabled."
    is GatewayPluginMutationIntent.Uninstall -> "$displayName removed."
  }

private fun PluginCatalogItem.toInstallIntent(): GatewayPluginMutationIntent.Install? {
  val reference = installReference ?: return null
  val action =
    when (installSource) {
      "official" -> GatewayPluginInstallAction.Official(reference)
      "clawhub" -> GatewayPluginInstallAction.ClawHub(reference)
      else -> return null
    }
  return GatewayPluginMutationIntent.Install(action = action, displayName = displayName, version = version)
}

internal fun pluginDetailUiState(
  pluginId: String,
  plugin: PluginCatalogItem?,
  connected: Boolean,
  inspectAvailable: Boolean,
  inspectionState: GatewayPluginInspectionState,
): PluginDetailUiState {
  val matchingState =
    when (inspectionState) {
      is GatewayPluginInspectionState.Loading -> inspectionState.takeIf { it.pluginId == pluginId }
      is GatewayPluginInspectionState.Ready -> inspectionState.takeIf { it.pluginId == pluginId }
      is GatewayPluginInspectionState.Error -> inspectionState.takeIf { it.pluginId == pluginId }
      GatewayPluginInspectionState.Idle -> null
    }
  return PluginDetailUiState(
    plugin = plugin,
    connected = connected,
    inspectionAvailable = inspectAvailable,
    loading = matchingState is GatewayPluginInspectionState.Loading,
    inspection =
      (matchingState as? GatewayPluginInspectionState.Ready)
        ?.details
        ?.toPresentation(),
    inspectionErrorText = (matchingState as? GatewayPluginInspectionState.Error)?.message,
  )
}

internal fun GatewayPluginInspectionDetails.toPresentation(): PluginInspectionPresentation =
  PluginInspectionPresentation(
    includedSections = declared.toIncludedSections(),
    sourceFacts =
      source
        ?.let {
          listOfNotNull(
            PluginDetailFact(nativeString("Source"), it.kind),
            it.spec?.let { value -> PluginDetailFact(nativeString("Source reference"), value) },
            it.packageName?.let { value -> PluginDetailFact(nativeString("Source package"), value) },
            it.integrity?.let { value -> PluginDetailFact(nativeString("Integrity"), value) },
            it.integrityKind?.let { value -> PluginDetailFact(nativeString("Integrity type"), value) },
          )
        }.orEmpty(),
    declaredSections = declared.toPresentationSections(),
    grantSections = grants.toPresentationSections(),
    trust = trust?.toPresentation(),
  )

private fun GatewayPluginDeclaredSurface.toIncludedSections(): List<PluginIncludedSection> =
  listOfNotNull(
    skills.takeIf(List<String>::isNotEmpty)?.let {
      PluginIncludedSection(PluginIncludedKind.Skills, nativeString("Skills"), it)
    },
    tools.takeIf(List<String>::isNotEmpty)?.let {
      PluginIncludedSection(PluginIncludedKind.Tools, nativeString("Tools"), it)
    },
    (channels + mcpServers).distinct().takeIf(List<String>::isNotEmpty)?.let {
      PluginIncludedSection(PluginIncludedKind.Connections, nativeString("Connections"), it)
    },
    providers.takeIf(List<String>::isNotEmpty)?.let {
      PluginIncludedSection(PluginIncludedKind.Providers, nativeString("Providers"), it)
    },
  )

internal fun GatewayPluginDeclaredSurface.toPresentationSections(): List<PluginDetailSection> =
  listOf(
    nativeString("Channels") to channels,
    nativeString("Providers") to providers,
    nativeString("Tools") to tools,
    nativeString("Contracts") to contracts,
    nativeString("Hooks") to hooks,
    nativeString("MCP servers") to mcpServers,
    nativeString("CLI commands") to cliCommands,
    nativeString("CLI backends") to cliBackends,
    nativeString("Skills") to skills,
    nativeString("Sensitive settings") to dangerousConfigFlags,
  ).mapNotNull { (label, values) ->
    values.takeIf(List<String>::isNotEmpty)?.let {
      PluginDetailSection(
        title = nativeString(label),
        facts = listOf(PluginDetailFact(nativeString("Declared"), it.joinToString(", "))),
      )
    }
  }

private fun GatewayPluginOperatorGrants.toPresentationSections(): List<PluginDetailSection> =
  buildList {
    add(
      PluginDetailSection(
        title = nativeString("Hooks"),
        facts =
          listOf(
            allowPromptInjection.toFact(nativeString("Prompt injection")),
            allowConversationAccess.toFact(nativeString("Conversation access")),
          ),
      ),
    )
    llm?.let { add(it.toPresentationSection(nativeString("Language model"))) }
    subagent?.let { add(it.toPresentationSection(nativeString("Subagent"))) }
  }

private fun GatewayPluginHookGrant.toFact(label: String): PluginDetailFact =
  PluginDetailFact(
    label = label,
    value =
      buildList {
        add(if (effective) nativeString("Allowed") else nativeString("Not allowed"))
        configured?.let { add(if (it) nativeString("Explicitly allowed") else nativeString("Explicitly denied")) }
      }.joinToString(" · "),
  )

private fun GatewayPluginModelGrants.toPresentationSection(title: String): PluginDetailSection =
  PluginDetailSection(
    title = title,
    facts =
      listOfNotNull(
        allowModelOverride?.let { PluginDetailFact(nativeString("Model override"), it.allowedLabel()) },
        allowedModels.takeIf(List<String>::isNotEmpty)?.let {
          PluginDetailFact(nativeString("Allowed models"), it.joinToString(", "))
        },
        allowedCompletionModels.takeIf(List<String>::isNotEmpty)?.let {
          PluginDetailFact(nativeString("Completion models"), it.joinToString(", "))
        },
        allowAuthProfileOverride?.let { PluginDetailFact(nativeString("Auth profile override"), it.allowedLabel()) },
        allowAgentIdOverride?.let { PluginDetailFact(nativeString("Agent override"), it.allowedLabel()) },
      ),
  )

private fun Boolean.allowedLabel(): String = if (this) nativeString("Allowed") else nativeString("Not allowed")

private fun GatewayPluginTrust.toPresentation(): PluginTrustPresentation =
  PluginTrustPresentation(
    label =
      when (disposition) {
        GatewayPluginTrustDisposition.Clean -> nativeString("Clean")
        GatewayPluginTrustDisposition.ReviewRecommended -> nativeString("Review recommended")
        GatewayPluginTrustDisposition.ReviewRequired -> nativeString("Review required")
        GatewayPluginTrustDisposition.Blocked -> nativeString("Blocked")
      },
    warning = disposition != GatewayPluginTrustDisposition.Clean || pending || stale,
    facts =
      buildList {
        reasons.takeIf(List<String>::isNotEmpty)?.let {
          add(PluginDetailFact(nativeString("Reasons"), it.joinToString(" · ")))
        }
        checkedAt?.let { add(PluginDetailFact(nativeString("Checked"), it)) }
        acknowledgedAt?.let { add(PluginDetailFact(nativeString("Acknowledged"), it)) }
        if (pending) add(PluginDetailFact(nativeString("Status"), nativeString("Review pending")))
        if (stale) add(PluginDetailFact(nativeString("Status"), nativeString("Review is stale")))
      },
  )

internal fun GatewayPluginCatalogEntry.toPresentationItem(): PluginCatalogItem =
  PluginCatalogItem(
    pluginId = id,
    displayName = name,
    summary = description,
    origin = origin,
    categories = listOfNotNull(category),
    packageName = packageName,
    version = version,
    kinds = kinds,
    status =
      when (state) {
        GatewayPluginState.Enabled -> PluginInstallStatus.Ready
        GatewayPluginState.Disabled -> PluginInstallStatus.Installed
        GatewayPluginState.NotInstalled -> PluginInstallStatus.Available
        GatewayPluginState.Error -> PluginInstallStatus.Failed
      },
    enabled = enabled,
    artworkSource =
      if (hasIcon) {
        PluginArtworkSource.Gateway(id)
      } else {
        PluginArtworkSource.None
      },
    installSource =
      when (install) {
        is GatewayPluginInstallAction.ClawHub -> "clawhub"
        is GatewayPluginInstallAction.Official -> "official"
        null -> null
      },
    installReference =
      when (install) {
        is GatewayPluginInstallAction.ClawHub -> install.packageName
        is GatewayPluginInstallAction.Official -> install.pluginId
        null -> null
      },
    removable = removable,
    error = error,
  )

private fun PluginCatalogSnapshot.toPresentationItem(): PluginCatalogItem {
  val installed = local as? PluginLocalState.Installed
  return PluginCatalogItem(
    pluginId = installed?.gatewayPluginId ?: catalog.packageName.value,
    displayName = catalog.displayName,
    summary = catalog.summary,
    origin = "clawhub",
    categories = catalog.categories,
    packageName = catalog.packageName.value,
    version = installed?.version ?: catalog.latestVersion,
    kinds = listOfNotNull(catalog.family),
    status = installed?.state.toPluginInstallStatus(),
    enabled = installed?.enabled == true,
    artworkSource =
      if (catalog.iconUrl != null) {
        PluginArtworkSource.ClawHub(catalog.packageName.value)
      } else {
        PluginArtworkSource.None
      },
    installSource = "clawhub",
    installReference = catalog.packageName.value,
    removable = installed != null,
    error = installed?.error,
  )
}

private fun GatewayPluginState?.toPluginInstallStatus(): PluginInstallStatus =
  when (this) {
    GatewayPluginState.Enabled -> PluginInstallStatus.Ready
    GatewayPluginState.Disabled -> PluginInstallStatus.Installed
    GatewayPluginState.NotInstalled,
    null,
    -> PluginInstallStatus.Available
    GatewayPluginState.Error -> PluginInstallStatus.Failed
  }

private fun OfficialPluginSearchMatch.toPresentationItem(
  gatewayPlugins: List<GatewayPluginCatalogEntry>,
): PluginSearchItem {
  val snapshot = overlayPluginLocalState(listOf(plugin), gatewayPlugins).single()
  val installed = snapshot.local as? PluginLocalState.Installed
  return PluginSearchItem(
    packageName = plugin.packageName.value,
    displayName = plugin.displayName,
    summary = plugin.summary,
    family = plugin.family.orEmpty(),
    channel = plugin.channel.orEmpty(),
    official = true,
    latestVersion = plugin.latestVersion,
    downloads = plugin.downloads,
    verificationTier = plugin.verificationTier,
    installedPluginId = installed?.gatewayPluginId,
    artworkSource =
      if (plugin.iconUrl != null) {
        PluginArtworkSource.ClawHub(plugin.packageName.value)
      } else {
        PluginArtworkSource.None
      },
  )
}

private fun PluginCatalogFailure.toPresentationText(): String =
  when (this) {
    is PluginCatalogFailure.RateLimited ->
      retryAfterSeconds?.let { nativeString("ClawHub is busy. Try again in \$count seconds.", it) }
        ?: nativeString("ClawHub is busy. Try again shortly.")
    is PluginCatalogFailure.Http -> nativeString("ClawHub is unavailable (HTTP \$count).", statusCode)
    PluginCatalogFailure.Network -> nativeString("Can't reach ClawHub. Check your connection and try again.")
    PluginCatalogFailure.InvalidResponse -> nativeString("ClawHub returned an invalid response.")
    PluginCatalogFailure.NotOfficial -> nativeString("This item is not in the official catalog.")
  }

internal fun pluginBadge(name: String): String =
  name
    .split(' ', '-', '_')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::uppercaseFirstGraphemeOrNull)
    .joinToString("")
    .ifBlank { "P" }
