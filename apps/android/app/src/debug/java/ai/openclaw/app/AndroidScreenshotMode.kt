package ai.openclaw.app

import ai.openclaw.app.ui.settings.SettingsDestination
import android.content.Intent

internal const val extraAndroidScreenshotMode = "openclaw.screenshotMode"
internal const val extraAndroidScreenshotScene = "openclaw.screenshotScene"
internal const val extraAndroidScreenshotTheme = "openclaw.screenshotTheme"

internal enum class AndroidScreenshotScene(
  val rawValue: String,
) {
  Home("home"),
  BlankChat("blank-chat"),
  Chat("chat"),
  Drawer("drawer"),
  Settings("settings"),
  LocalEnvironment("local-environment"),
  ;

  companion object {
    fun fromRawValue(raw: String?): AndroidScreenshotScene = entries.firstOrNull { it.rawValue == raw?.trim()?.lowercase() } ?: Home
  }
}

internal fun androidScreenshotLaunch(
  scene: AndroidScreenshotScene,
  themeMode: AppearanceThemeMode = AppearanceThemeMode.Dark,
): AndroidScreenshotLaunch {
  AndroidScreenshotFixture.configure(scene)
  return AndroidScreenshotLaunch(
    fixture = AndroidScreenshotFixture,
    themeMode = themeMode,
    homeDestination =
      when (scene) {
        AndroidScreenshotScene.Home -> HomeDestination.Connect
        AndroidScreenshotScene.BlankChat,
        AndroidScreenshotScene.Chat,
        AndroidScreenshotScene.Drawer,
        -> HomeDestination.Chat
        AndroidScreenshotScene.Settings,
        AndroidScreenshotScene.LocalEnvironment,
        -> HomeDestination.Settings
      },
    settingsRoute =
      when (scene) {
        AndroidScreenshotScene.LocalEnvironment -> SettingsDestination.LocalEnvironment
        else -> null
      },
    shellState =
      AndroidScreenshotShellState(
        launchChatCreated = scene != AndroidScreenshotScene.BlankChat,
        suppressAutomaticChat = true,
        forceBlankChat = scene == AndroidScreenshotScene.BlankChat,
        openDrawer = scene == AndroidScreenshotScene.Drawer,
      ),
  )
}

internal fun parseAndroidScreenshotLaunchIntent(intent: Intent?): AndroidScreenshotLaunch? {
  if (intent?.getBooleanExtra(extraAndroidScreenshotMode, false) != true) return null
  val scene = AndroidScreenshotScene.fromRawValue(intent.getStringExtra(extraAndroidScreenshotScene))
  val themeMode = AppearanceThemeMode.fromRawValue(intent.getStringExtra(extraAndroidScreenshotTheme))
  return androidScreenshotLaunch(scene = scene, themeMode = themeMode)
}
