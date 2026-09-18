package ai.openclaw.app.ui.settings

import ai.openclaw.app.BuildConfig
import ai.openclaw.app.ai.AiSetupFeature
import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.currentAppLanguage
import ai.openclaw.app.i18n.joinedNativeText
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.verbatimText
import ai.openclaw.app.runtime.RuntimeFeature
import ai.openclaw.app.settings.SettingsFeatureSet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

internal data class SettingsOverviewUiState(
  val sections: List<SettingsSection>,
  val versionLabel: String,
  val statusLabel: String,
  val statusNeedsAttention: Boolean,
)

/** Adapts gateway/runtime state to the presentation-only settings overview contract. */
@Composable
internal fun SettingsOverviewRoute(
  settings: SettingsFeatureSet,
  aiSetup: AiSetupFeature?,
  environment: RuntimeFeature,
  onRouteChange: (SettingsDestination) -> Unit,
  onClose: () -> Unit,
) {
  val environmentState by environment.state.collectAsState()
  val isConnected = environmentState.gatewayConnected
  val appearanceThemeMode by settings.appearance.themeMode.collectAsState()
  val aiSetupState = aiSetup?.state?.collectAsState()?.value ?: AiSetupState.Disconnected
  val appLanguage = currentAppLanguage()
  val rows =
    listOf(
      SettingsRow(
        nativeText("AI and models"),
        when (aiSetupState) {
          is AiSetupState.Ready -> verbatimText(aiSetupState.modelRef)
          is AiSetupState.Working -> nativeText("Checking configuration")
          else -> nativeText("Needs attention")
        },
        Icons.Outlined.Inventory2,
        needsAttention = aiSetupState !is AiSetupState.Ready,
        route = SettingsDestination.AiModels,
      ),
      SettingsRow(
        nativeText("Appearance"),
        joinedNativeText(
          separator = " · ",
          parts =
            listOf(
              verbatimText(appearanceThemeSummary(appearanceThemeMode)),
              verbatimText(appLanguage.displayName),
            ),
        ),
        Icons.Default.Palette,
        route = SettingsDestination.Appearance,
      ),
      SettingsRow(
        nativeText("Local environment"),
        nativeText(
          when {
            !isConnected -> "Needs attention"
            else -> "Ready"
          },
        ),
        Icons.Default.Settings,
        needsAttention = !isConnected,
        route = SettingsDestination.LocalEnvironment,
      ),
      SettingsRow(nativeText("About"), nativeText("Version and update"), Icons.Default.Storage, route = SettingsDestination.About),
    )

  SettingsHomeScreen(
    state =
      SettingsOverviewUiState(
        sections =
          settingsHomeSections(
            rows = rows,
            labels =
              SettingsHomeSectionLabels(
                preferences = nativeString("General"),
                product = "ClawInOne",
              ),
          ),
        versionLabel = nativeString("ClawInOne \${BuildConfig.VERSION_NAME} (\${BuildConfig.VERSION_CODE})", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        statusLabel = if (isConnected) nativeString("All systems operational") else nativeString("Gateway not connected"),
        statusNeedsAttention = !isConnected,
      ),
    onRouteChange = onRouteChange,
    onClose = onClose,
  )
}
