package ai.openclaw.app.ui.extensions

import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtensionHubScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun installedPluginRowOpensTheGatewayIdentity() {
    RuntimeEnvironment.getApplication()
    var openedId: String? = null
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginHubScreen(
          surface = PluginHubSurface.Management,
          state = pluginState(),
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearch = {},
          onOpenPlugin = { pluginId, _ -> openedId = pluginId },
        )
      }
    }

    composeRule.onNodeWithText("Workboard").assertIsDisplayed().performClick()
    assertEquals("workboard", openedId)
  }

  @Test
  fun pluginSettingsSeparatesInstalledPluginsAndConnections() {
    RuntimeEnvironment.getApplication()
    var activatedConnector: String? = null
    val state =
      pluginState().copy(
        mcp =
          McpHubPresentation(
            readAvailable = true,
            canMutate = true,
            servers =
              listOf(
                McpServerItem(
                  name = "context7",
                  target = "https://mcp.context7.com/mcp",
                  transportLabel = "Streamable HTTP",
                  enabled = true,
                  detail = null,
                ),
              ),
            connectors =
              listOf(
                McpConnectorItem(
                  id = "notion",
                  name = "Notion",
                  description = "Search pages",
                  group = McpConnectorGroupPresentation.Work,
                  addsServer = true,
                  alreadyAdded = false,
                  requiresAuthentication = true,
                ),
              ),
          ),
      )
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginHubScreen(
          surface = PluginHubSurface.Management,
          state = state,
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearch = {},
          onOpenPlugin = { _, _ -> },
          onActivateMcpConnector = { activatedConnector = it },
        )
      }
    }

    composeRule.onNodeWithText("Installed Plugins").assertIsDisplayed()
    composeRule.onNodeWithText("Connections").assertIsDisplayed()
    composeRule.onNodeWithText("Configured").assertIsDisplayed()
    composeRule.onNodeWithText("Available Connections").assertIsDisplayed()
    composeRule.onAllNodesWithText("Add")[1].performClick()
    assertEquals("notion", activatedConnector)
  }

  @Test
  fun pluginDirectoryShowsOnlyAuthoritativePluginEntries() {
    RuntimeEnvironment.getApplication()
    var installedId: String? = null
    val state =
      pluginState().copy(
        plugins =
          listOf(
            pluginItem(
              pluginId = "workboard",
              displayName = "Workboard",
              status = PluginInstallStatus.Ready,
            ),
            pluginItem(
              pluginId = "calendar",
              displayName = "Calendar",
              status = PluginInstallStatus.Available,
            ),
          ),
        lifecycle = PluginLifecyclePresentation(canInstall = true),
        mcp =
          McpHubPresentation(
            connectors =
              listOf(
                McpConnectorItem(
                  id = "notion",
                  name = "Notion",
                  description = "Search pages",
                  group = McpConnectorGroupPresentation.Work,
                  addsServer = true,
                  alreadyAdded = false,
                  requiresAuthentication = true,
                ),
              ),
          ),
      )
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginHubScreen(
          surface = PluginHubSurface.Directory,
          state = state,
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearch = {},
          onOpenPlugin = { _, _ -> },
          onInstallPlugin = { installedId = it.pluginId },
        )
      }
    }

    composeRule.onNodeWithText("Installed").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open Workboard").assertIsDisplayed()
    composeRule.onNodeWithText("Recommended").assertIsDisplayed()
    composeRule.onAllNodesWithText("Calendar").assertCountEquals(2)
    composeRule.onAllNodesWithText("Notion").assertCountEquals(0)
    composeRule.onAllNodesWithContentDescription("Install Calendar")[0].performClick()
    assertEquals("calendar", installedId)
  }

  @Test
  fun pluginDirectoryOverflowOpensItsSection() {
    RuntimeEnvironment.getApplication()
    var openedSection: PluginDirectorySectionId? = null
    val plugins =
      (1..7).map { index ->
        pluginItem(
          pluginId = "plugin-$index",
          displayName = "Plugin $index",
          status = PluginInstallStatus.Available,
        )
      }
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginHubScreen(
          surface = PluginHubSurface.Directory,
          state = pluginState().copy(plugins = plugins),
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearch = {},
          onOpenPlugin = { _, _ -> },
          onOpenCategory = { openedSection = it },
        )
      }
    }

    composeRule
      .onAllNodesWithText("View Plugin 7")[0]
      .performScrollTo()
      .performClick()
    assertEquals(PluginDirectorySectionId.Recommended, openedSection)
  }

  @Test
  fun pluginCategoryShowsItsSelectedSection() {
    RuntimeEnvironment.getApplication()
    val plugins =
      (1..8).map { index ->
        pluginItem(
          pluginId = "plugin-$index",
          displayName = "Plugin $index",
          status = PluginInstallStatus.Available,
        )
      }
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginHubScreen(
          surface = PluginHubSurface.Category(PluginDirectorySectionId.Category("tool")),
          state =
            pluginState().copy(
              plugins = plugins,
              catalogCanLoadNextPage = true,
            ),
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearch = {},
          onOpenPlugin = { _, _ -> },
          onLoadNextPage = {},
        )
      }
    }

    composeRule.onNodeWithText("Plugin 1").assertIsDisplayed()
  }

  @Test
  fun catalogFailureOffersOneRetryActionEvenWhenGatewayIsOffline() {
    RuntimeEnvironment.getApplication()
    var refreshes = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginHubScreen(
          surface = PluginHubSurface.Directory,
          state =
            pluginState().copy(
              connected = false,
              plugins = emptyList(),
              catalogErrorText = "Can't reach ClawHub.",
            ),
          contentPadding = PaddingValues(),
          onRefresh = { refreshes += 1 },
          onSearch = {},
          onOpenPlugin = { _, _ -> },
        )
      }
    }

    composeRule.onNodeWithText("Can't reach ClawHub.").assertIsDisplayed()
    composeRule
      .onNodeWithText("Refresh")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()
    assertEquals(1, refreshes)
  }

  @Test
  fun emptyPluginSearchOffersOneClearAction() {
    RuntimeEnvironment.getApplication()
    var latestQuery: String? = null
    composeRule.setContent {
      ProvideClawDesignSystem {
        PluginHubScreen(
          surface = PluginHubSurface.Directory,
          state =
            pluginState().copy(
              searchQuery = "missing",
              searchResults = emptyList(),
            ),
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearch = { latestQuery = it },
          onOpenPlugin = { _, _ -> },
        )
      }
    }

    composeRule.onNodeWithText("No results").assertIsDisplayed()
    composeRule.onNodeWithText("Clear search").assertIsDisplayed().performClick()
    assertEquals("", latestQuery)
  }

  @Test
  fun directoryProjectionKeepsInstalledAndCatalogGroupsDistinct() {
    val installed =
      pluginItem(
        pluginId = "workboard",
        displayName = "Workboard",
        status = PluginInstallStatus.Ready,
      )
    val automation =
      pluginItem(
        pluginId = "calendar",
        displayName = "Calendar",
        status = PluginInstallStatus.Available,
      )
    val official =
      pluginItem(
        pluginId = "pdf",
        displayName = "PDF",
        status = PluginInstallStatus.Available,
      )

    val projection = pluginDirectoryPresentation(listOf(installed, automation, official))

    assertEquals(listOf("workboard"), projection.installed.map(PluginCatalogItem::pluginId))
    assertEquals(listOf("workboard", "calendar", "pdf"), projection.recommended.items.map(PluginCatalogItem::pluginId))
    assertEquals(
      listOf("workboard", "calendar", "pdf"),
      projection.categories
        .single()
        .items
        .map(PluginCatalogItem::pluginId),
    )
  }

  @Test
  fun builtInCapabilitiesExposeOnlyEnablementControls() {
    RuntimeEnvironment.getApplication()
    var pluginChange: Pair<String, Boolean>? = null
    var skillChange: Pair<String, Boolean>? = null
    var selectedDirectory by mutableStateOf(BuiltInCapabilityDirectory.Plugins)
    val browser =
      pluginItem(
        pluginId = "browser",
        displayName = "Browser",
        status = PluginInstallStatus.Ready,
        origin = "bundled",
      )
    val browserSkill =
      InstalledSkillItem(
        skillKey = "browser-automation",
        displayName = "Browser automation",
        summary = "Control the OpenClaw browser.",
        sourceLabel = "Built-in",
        badge = "BA",
        status = InstalledSkillStatus.Disabled,
        missingCount = 0,
        installCount = 0,
        isBuiltIn = true,
      )

    composeRule.setContent {
      ProvideClawDesignSystem {
        BuiltInCapabilitiesScreen(
          selectedDirectory = selectedDirectory,
          state =
            BuiltInCapabilitiesUiState(
              connected = true,
              pluginInventoryAvailable = true,
              refreshing = false,
              pluginErrorText = null,
              skillErrorText = null,
              plugins = listOf(browser),
              skills = listOf(browserSkill),
              canSetPluginEnabled = true,
              canSetSkillEnabled = true,
              pluginLifecycle = PluginLifecyclePresentation(canSetEnabled = true),
              mutatingSkillKeys = emptySet(),
            ),
          contentPadding = PaddingValues(),
          onSelectDirectory = { selectedDirectory = it },
          onRefresh = {},
          onSetPluginEnabled = { plugin, enabled -> pluginChange = plugin.pluginId to enabled },
          onSetSkillEnabled = { key, enabled -> skillChange = key to enabled },
          onRefreshPluginMutation = {},
          onDismissPluginMutation = {},
        )
      }
    }

    composeRule.onNodeWithContentDescription("Enable or disable Browser").performClick()
    assertEquals("browser" to false, pluginChange)
    composeRule.onNodeWithText("Skills").performClick()
    assertEquals(BuiltInCapabilityDirectory.Skills, selectedDirectory)
    composeRule.onNodeWithContentDescription("Enable or disable Browser automation").performClick()
    assertEquals("browser-automation" to true, skillChange)
    composeRule.onNodeWithText("Install Plugin").assertDoesNotExist()
    composeRule.onNodeWithText("Remove Plugin").assertDoesNotExist()
  }

  private fun pluginState(): PluginHubUiState =
    PluginHubUiState(
      connected = true,
      inventoryAvailable = true,
      catalogRefreshing = false,
      catalogErrorText = null,
      inventoryRefreshing = false,
      inventoryErrorText = null,
      mutationAllowed = true,
      diagnosticsCount = 0,
      plugins =
        listOf(
          pluginItem(
            pluginId = "workboard",
            displayName = "Workboard",
            status = PluginInstallStatus.Ready,
          ),
        ),
      managedPlugins =
        listOf(
          pluginItem(
            pluginId = "workboard",
            displayName = "Workboard",
            status = PluginInstallStatus.Ready,
          ),
        ),
      searchQuery = "",
      searching = false,
      searchResults = emptyList(),
      searchErrorText = null,
    )

  private fun pluginItem(
    pluginId: String,
    displayName: String,
    status: PluginInstallStatus,
    origin: String = "official",
  ): PluginCatalogItem =
    PluginCatalogItem(
      pluginId = pluginId,
      displayName = displayName,
      summary = "Use $displayName.",
      origin = origin,
      categories = listOf("tool"),
      packageName = "@openclaw/$pluginId",
      version = "1.0.0",
      kinds = listOf("tool"),
      status = status,
      enabled = status == PluginInstallStatus.Ready,
      installSource = if (status == PluginInstallStatus.Available) "official" else null,
      installReference = if (status == PluginInstallStatus.Available) pluginId else null,
      removable = true,
      error = null,
    )
}
