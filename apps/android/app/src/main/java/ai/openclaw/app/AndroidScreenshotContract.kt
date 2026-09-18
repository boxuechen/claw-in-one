package ai.openclaw.app

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.ui.settings.SettingsDestination

/** Small production seam consumed by the shell; debug source sets provide the fixture itself. */
internal data class AndroidScreenshotShellState(
  val launchChatCreated: Boolean,
  val suppressAutomaticChat: Boolean,
  val forceBlankChat: Boolean,
  val openDrawer: Boolean,
)

internal data class AndroidScreenshotLaunch(
  val fixture: AndroidScreenshotRuntimeFixture,
  val themeMode: AppearanceThemeMode,
  val homeDestination: HomeDestination,
  val settingsRoute: SettingsDestination?,
  val shellState: AndroidScreenshotShellState,
)

/** Data contract required by NodeRuntime without shipping any debug fixture records. */
internal interface AndroidScreenshotRuntimeFixture {
  val gatewayId: String
  val controlUiBaseUrl: String
  val mainSessionKey: String
  val offline: Boolean
  val models: List<AiModel>

  fun createRequester(): (String, String?) -> String
}
