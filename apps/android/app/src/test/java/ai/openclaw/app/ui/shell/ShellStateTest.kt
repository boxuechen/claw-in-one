package ai.openclaw.app.ui.shell

import ai.openclaw.app.ui.devkit.DevKitDestination
import ai.openclaw.app.ui.extensions.BuiltInCapabilityDirectory
import ai.openclaw.app.ui.extensions.ExtensionCenterDestination
import ai.openclaw.app.ui.extensions.ExtensionCenterDirectory
import ai.openclaw.app.ui.extensions.PluginDetailOrigin
import ai.openclaw.app.ui.extensions.PluginDirectorySectionId
import ai.openclaw.app.ui.extensions.toBuiltInCapabilityDirectory
import ai.openclaw.app.ui.settings.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellStateTest {
  @Test
  fun terminalIsAFullScreenDestinationAndBackReturnsToDrawerLayer() {
    val state = ShellState()
    state.openTerminal()
    assertTrue(state.terminalVisible)
    assertFalse(state.settingsVisible)
    assertTrue(state.back())
    assertFalse(state.terminalVisible)
    assertFalse(state.back())
    state.openTerminal()
    state.openSettings()
    assertFalse(state.terminalVisible)
  }

  @Test
  fun shellStartsOnChatWithoutRestoredNavigation() {
    val state = ShellState()

    assertFalse(state.settingsVisible)
    assertFalse(state.extensionCenterVisible)
    assertFalse(state.devKitVisible)
  }

  @Test
  fun pluginCenterOpensPluginDirectoryByDefault() {
    val state = ShellState()

    state.openExtensionCenter()

    assertEquals(
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Plugins),
      state.extensionCenterRoute,
    )
  }

  @Test
  fun settingsBackUnwindsDetailThenClosesSheet() {
    val state = ShellState()
    state.openSettings()
    state.openSettingsRoute(SettingsDestination.AiModels)

    assertTrue(state.back())
    assertEquals(SettingsDestination.Home, state.settingsRoute)
    assertTrue(state.back())
    assertNull(state.settingsRoute)
    assertFalse(state.back())
  }

  @Test
  fun devKitBackUnwindsCapabilityThenClosesDestination() {
    val state = ShellState()
    state.openDevKit()
    state.openDevKitRoute(DevKitDestination.AndroidUse)

    assertTrue(state.back())
    assertEquals(DevKitDestination.Home, state.devKitRoute)
    assertTrue(state.back())
    assertNull(state.devKitRoute)
    assertFalse(state.back())
  }

  @Test
  fun useSkillInChatClosesDevKitAndConsumesOnlyTheMatchingRequest() {
    val state = ShellState()
    state.openDevKitRoute(DevKitDestination.AndroidKotlin)

    state.useSkillInChat("android-development")

    assertFalse(state.devKitVisible)
    val request = checkNotNull(state.chatSkillSelectionRequest)
    assertEquals("android-development", request.reference)
    state.consumeChatSkillSelection(request.id + 1)
    assertEquals(request, state.chatSkillSelectionRequest)
    state.consumeChatSkillSelection(request.id)
    assertNull(state.chatSkillSelectionRequest)
  }

  @Test
  fun extensionCenterBackReturnsFromDetailThenClosesDestination() {
    val state = ShellState()
    state.openExtensionCenterRoute(
      ExtensionCenterDestination.PluginDetail(pluginId = "example", displayName = "Example"),
    )

    assertTrue(state.extensionCenterVisible)
    assertTrue(state.back())
    assertEquals(
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Plugins),
      state.extensionCenterRoute,
    )
    assertTrue(state.back())
    assertNull(state.extensionCenterRoute)
  }

  @Test
  fun openingTopLevelLayerReplacesThePreviousLayer() {
    val state = ShellState()
    state.openSettings(SettingsDestination.Home)
    state.openExtensionCenter(ExtensionCenterDirectory.Skills)

    assertFalse(state.settingsVisible)
    assertEquals(
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Skills),
      state.extensionCenterRoute,
    )

    state.openSettings(SettingsDestination.About)

    assertFalse(state.extensionCenterVisible)
    assertEquals(SettingsDestination.About, state.settingsRoute)

    state.openDevKit()

    assertFalse(state.settingsVisible)
    assertEquals(DevKitDestination.Home, state.devKitRoute)
  }

  @Test
  fun pluginSettingsAndManagedDetailUnwindToDirectory() {
    val state = ShellState()
    state.openExtensionCenterRoute(
      ExtensionCenterDestination.PluginSettings(ExtensionCenterDirectory.Skills),
    )

    assertTrue(state.back())
    assertEquals(
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Skills),
      state.extensionCenterRoute,
    )

    state.openExtensionCenterRoute(
      ExtensionCenterDestination.PluginDetail(
        pluginId = "example",
        displayName = "Example",
        origin = PluginDetailOrigin.Settings,
        returnDirectory = ExtensionCenterDirectory.Skills,
      ),
    )

    assertTrue(state.back())
    assertEquals(
      ExtensionCenterDestination.PluginSettings(ExtensionCenterDirectory.Skills),
      state.extensionCenterRoute,
    )
  }

  @Test
  fun skillDetailBackReturnsToSkillsDirectory() {
    val state = ShellState()
    state.openExtensionCenterRoute(
      ExtensionCenterDestination.SkillDetail(skillKey = "@openclaw/example"),
    )

    assertTrue(state.back())
    assertEquals(
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Skills),
      state.extensionCenterRoute,
    )
  }

  @Test
  fun pluginCategoryBackReturnsToPluginDirectory() {
    val state = ShellState()
    state.openExtensionCenterRoute(
      ExtensionCenterDestination.PluginCategory(
        PluginDirectorySectionId.Category("Development"),
      ),
    )

    assertTrue(state.back())
    assertEquals(
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Plugins),
      state.extensionCenterRoute,
    )
  }

  @Test
  fun pluginDetailOpenedFromCategoryReturnsToThatCategory() {
    val category = PluginDirectorySectionId.Category("Development")
    val state = ShellState()
    state.openExtensionCenterRoute(
      ExtensionCenterDestination.PluginDetail(
        pluginId = "example",
        displayName = "Example",
        origin = PluginDetailOrigin.Category(category),
      ),
    )

    assertTrue(state.back())
    assertEquals(ExtensionCenterDestination.PluginCategory(category), state.extensionCenterRoute)
  }

  @Test
  fun builtInCapabilitiesBackReturnsToOriginDirectory() {
    val state = ShellState()
    state.openExtensionCenterRoute(
      ExtensionCenterDestination.BuiltInCapabilities(
        directory = BuiltInCapabilityDirectory.Skills,
        returnDirectory = ExtensionCenterDirectory.Skills,
      ),
    )

    assertTrue(state.back())
    assertEquals(
      ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Skills),
      state.extensionCenterRoute,
    )
  }

  @Test
  fun builtInCapabilitiesOpenOnTheMatchingDirectory() {
    assertEquals(
      BuiltInCapabilityDirectory.Plugins,
      ExtensionCenterDirectory.Plugins.toBuiltInCapabilityDirectory(),
    )
    assertEquals(
      BuiltInCapabilityDirectory.Skills,
      ExtensionCenterDirectory.Skills.toBuiltInCapabilityDirectory(),
    )
  }
}
