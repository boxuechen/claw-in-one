package ai.openclaw.app.ui.shell

import ai.openclaw.app.ui.chat.ChatSkillSelectionRequest
import ai.openclaw.app.ui.devkit.DevKitDestination
import ai.openclaw.app.ui.extensions.ExtensionCenterDestination
import ai.openclaw.app.ui.extensions.ExtensionCenterDirectory
import ai.openclaw.app.ui.extensions.parent
import ai.openclaw.app.ui.settings.SettingsDestination
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Transient presentation state for the chat-first Android shell.
 *
 * Gateway, session, and agent state remain owned by MainViewModel/NodeRuntime. This
 * class only owns which product surface or temporary settings layer is visible.
 */
internal class ShellState {
  private sealed interface Layer {
    data object Terminal : Layer

    data class Settings(
      val destination: SettingsDestination,
      val backStack: List<SettingsDestination>,
    ) : Layer

    data class ExtensionCenter(
      val destination: ExtensionCenterDestination,
    ) : Layer

    data class DevKit(
      val destination: DevKitDestination,
      val backStack: List<DevKitDestination>,
    ) : Layer
  }

  private var layer by mutableStateOf<Layer?>(null)
  private var nextChatSkillSelectionId = 0L

  var chatSkillSelectionRequest by mutableStateOf<ChatSkillSelectionRequest?>(null)
    private set

  val terminalVisible: Boolean get() = layer == Layer.Terminal

  fun openTerminal() {
    layer = Layer.Terminal
  }

  val settingsRoute: SettingsDestination?
    get() = (layer as? Layer.Settings)?.destination

  val extensionCenterRoute: ExtensionCenterDestination?
    get() = (layer as? Layer.ExtensionCenter)?.destination

  val devKitRoute: DevKitDestination?
    get() = (layer as? Layer.DevKit)?.destination

  val settingsVisible: Boolean
    get() = layer is Layer.Settings

  val extensionCenterVisible: Boolean
    get() = layer is Layer.ExtensionCenter

  val devKitVisible: Boolean
    get() = layer is Layer.DevKit

  fun openDevKit() {
    layer = Layer.DevKit(destination = DevKitDestination.Home, backStack = emptyList())
  }

  fun openDevKitRoute(destination: DevKitDestination) {
    val current = layer as? Layer.DevKit
    layer =
      if (current == null) {
        Layer.DevKit(destination = destination, backStack = emptyList())
      } else if (current.destination == destination) {
        current
      } else {
        Layer.DevKit(destination = destination, backStack = current.backStack + current.destination)
      }
  }

  fun useSkillInChat(reference: String) {
    val normalized = reference.trim()
    require(normalized.isNotEmpty())
    nextChatSkillSelectionId += 1
    chatSkillSelectionRequest = ChatSkillSelectionRequest(nextChatSkillSelectionId, normalized)
    layer = null
  }

  fun consumeChatSkillSelection(id: Long) {
    if (chatSkillSelectionRequest?.id == id) chatSkillSelectionRequest = null
  }

  fun openExtensionCenter(directory: ExtensionCenterDirectory = ExtensionCenterDirectory.Plugins) {
    layer = Layer.ExtensionCenter(ExtensionCenterDestination.Directory(directory))
  }

  fun openExtensionCenterRoute(destination: ExtensionCenterDestination) {
    layer = Layer.ExtensionCenter(destination)
  }

  fun closeExtensionCenter() {
    if (layer is Layer.ExtensionCenter) layer = null
  }

  fun showChat() {
    layer = null
  }

  fun openSettings(route: SettingsDestination = SettingsDestination.Home) {
    layer = Layer.Settings(destination = route, backStack = emptyList())
  }

  fun openSettingsRoute(route: SettingsDestination) {
    val current = layer as? Layer.Settings
    layer =
      if (current == null) {
        Layer.Settings(destination = route, backStack = emptyList())
      } else if (current.destination == route) {
        current
      } else {
        Layer.Settings(destination = route, backStack = current.backStack + current.destination)
      }
  }

  /** Closes the sheet. The Material drawer stays open when it launched settings. */
  fun closeSettings() {
    if (layer is Layer.Settings) layer = null
  }

  /** Returns true when this state consumed the system Back action. */
  fun back(): Boolean {
    when (val current = layer) {
      Layer.Terminal -> {
        layer = null
        return true
      }
      is Layer.Settings -> {
        val parent = current.backStack.lastOrNull()
        layer =
          parent?.let {
            Layer.Settings(destination = it, backStack = current.backStack.dropLast(1))
          }
        return true
      }

      is Layer.ExtensionCenter -> {
        layer = current.destination.parent()?.let(Layer::ExtensionCenter)
        return true
      }

      is Layer.DevKit -> {
        val parent = current.backStack.lastOrNull()
        layer =
          parent?.let {
            Layer.DevKit(destination = it, backStack = current.backStack.dropLast(1))
          }
        return true
      }

      null -> return false
    }
  }
}
