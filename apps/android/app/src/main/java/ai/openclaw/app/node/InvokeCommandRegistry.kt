package ai.openclaw.app.node

import ai.openclaw.app.androiduse.ANDROID_USE_NODE_COMMAND
import ai.openclaw.app.protocol.OpenClawCapability

/**
 * Publishes the supported native consent protocol while Accessibility is connected.
 * This is not a grant: the native lease owner checks saved consent for each action.
 * Disabling consent keeps the transport available for exact revoke acknowledgements.
 */
object InvokeCommandRegistry {
  val all: List<String> = listOf(ANDROID_USE_NODE_COMMAND)

  fun contains(command: String): Boolean = command == ANDROID_USE_NODE_COMMAND

  fun advertisedCapabilities(androidUseAvailable: Boolean): List<String> = if (androidUseAvailable) listOf(OpenClawCapability.MobileUI.rawValue, "clawAndroidUseConsentV1") else emptyList()

  fun advertisedCommands(androidUseAvailable: Boolean): List<String> = if (androidUseAvailable) all else emptyList()
}
