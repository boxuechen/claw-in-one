package ai.openclaw.app.settings

import ai.openclaw.app.AppearanceThemeMode
import ai.openclaw.app.gateway.GatewayUpdateAvailableSummary
import kotlinx.coroutines.flow.StateFlow

/** Pure composition contract for Settings; source owners retain all lifecycle and persistence. */
internal data class SettingsFeatureSet(
  val appearance: AppearanceSettingsFeature,
  val about: AboutSettingsFeature,
)

internal class AppearanceSettingsFeature(
  val themeMode: StateFlow<AppearanceThemeMode>,
  val actions: AppearanceSettingsActions,
)

internal data class AppearanceSettingsActions(
  val selectTheme: (AppearanceThemeMode) -> Unit,
)

internal class AboutSettingsFeature(
  val updateAvailable: StateFlow<GatewayUpdateAvailableSummary?>,
  val gatewayVersion: StateFlow<String?>,
)
