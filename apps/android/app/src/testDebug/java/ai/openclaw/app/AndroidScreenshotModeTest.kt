package ai.openclaw.app

import ai.openclaw.app.ui.settings.SettingsDestination
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidScreenshotModeTest {
  @Test
  fun ignoresNormalLaunches() {
    assertNull(parseAndroidScreenshotLaunchIntent(Intent(Intent.ACTION_MAIN)))
  }

  @Test
  fun parsesRequestedScene() {
    val parsed =
      parseAndroidScreenshotLaunchIntent(
        Intent(Intent.ACTION_MAIN)
          .putExtra(extraAndroidScreenshotMode, true)
          .putExtra(extraAndroidScreenshotScene, "chat"),
      )

    assertEquals(HomeDestination.Chat, parsed?.homeDestination)
    assertEquals(true, parsed?.shellState?.launchChatCreated)
  }

  @Test
  fun defaultsUnknownScenesToHome() {
    val parsed =
      parseAndroidScreenshotLaunchIntent(
        Intent(Intent.ACTION_MAIN)
          .putExtra(extraAndroidScreenshotMode, true)
          .putExtra(extraAndroidScreenshotScene, "unknown"),
      )

    assertEquals(HomeDestination.Connect, parsed?.homeDestination)
  }

  @Test
  fun defaultsFixtureThemeToDark() {
    val parsed =
      parseAndroidScreenshotLaunchIntent(
        Intent(Intent.ACTION_MAIN)
          .putExtra(extraAndroidScreenshotMode, true),
      )

    assertEquals(AppearanceThemeMode.Dark, parsed?.themeMode)
  }

  @Test
  fun parsesFixtureTheme() {
    val parsed =
      parseAndroidScreenshotLaunchIntent(
        Intent(Intent.ACTION_MAIN)
          .putExtra(extraAndroidScreenshotMode, true)
          .putExtra(extraAndroidScreenshotTheme, "light"),
      )

    assertEquals(AppearanceThemeMode.Light, parsed?.themeMode)
  }

  @Test
  fun mapsScenesToProductionShellDestinations() {
    assertEquals(HomeDestination.Connect, androidScreenshotLaunch(AndroidScreenshotScene.Home).homeDestination)
    assertEquals(HomeDestination.Chat, androidScreenshotLaunch(AndroidScreenshotScene.BlankChat).homeDestination)
    assertEquals(HomeDestination.Chat, androidScreenshotLaunch(AndroidScreenshotScene.Chat).homeDestination)
    assertEquals(HomeDestination.Chat, androidScreenshotLaunch(AndroidScreenshotScene.Drawer).homeDestination)
    assertEquals(HomeDestination.Settings, androidScreenshotLaunch(AndroidScreenshotScene.Settings).homeDestination)
  }

  @Test
  fun localEnvironmentSceneTargetsLocalEnvironment() {
    val parsed =
      parseAndroidScreenshotLaunchIntent(
        Intent(Intent.ACTION_MAIN)
          .putExtra(extraAndroidScreenshotMode, true)
          .putExtra(extraAndroidScreenshotScene, "local-environment"),
      )

    assertEquals(HomeDestination.Settings, parsed?.homeDestination)
    assertEquals(SettingsDestination.LocalEnvironment, parsed?.settingsRoute)
    assertNull(androidScreenshotLaunch(AndroidScreenshotScene.Settings).settingsRoute)
  }
}
