package ai.openclaw.app.ui.settings

import ai.openclaw.app.ai.AiSetupFeature
import ai.openclaw.app.runtime.RuntimeFeature
import ai.openclaw.app.settings.SettingsFeatureSet
import ai.openclaw.app.ui.environment.RuntimeEnvironmentRoute
import ai.openclaw.app.ui.environment.RuntimeEnvironmentRouteActions
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Owns the settings sheet hierarchy and delegates runtime adaptation to route-level children. */
@Composable
internal fun SettingsSheetRoute(
  settings: SettingsFeatureSet,
  aiSetup: AiSetupFeature?,
  environment: RuntimeFeature,
  environmentActions: RuntimeEnvironmentRouteActions,
  route: SettingsDestination,
  onPreviewOnboarding: (() -> Unit)?,
  onRouteChange: (SettingsDestination) -> Unit,
  onBack: () -> Unit,
  onClose: () -> Unit,
) {
  var aiEditorVisible by remember(route) { mutableStateOf(false) }
  // Details unwind before the shell closes its same-window Settings panel.
  BackHandler(
    enabled = route != SettingsDestination.Home && !aiEditorVisible,
    onBack = onBack,
  )

  // ShellState owns the sheet hierarchy. Detail Back returns here first;
  // closing the sheet restores the drawer when settings was opened from it.
  if (route == SettingsDestination.Home) {
    SettingsOverviewRoute(
      settings = settings,
      aiSetup = aiSetup,
      environment = environment,
      onRouteChange = onRouteChange,
      onClose = onClose,
    )
  } else {
    SettingsDetailRoute(
      settings = settings,
      aiSetup = aiSetup,
      environment = environment,
      environmentActions = environmentActions,
      route = route,
      onPreviewOnboarding = onPreviewOnboarding,
      onRouteChange = onRouteChange,
      onBack = onBack,
      onAiEditorVisibilityChanged = { aiEditorVisible = it },
    )
  }
}

/**
 * Dispatches a selected settings route to its detail screen without changing navigation ownership.
 */
@Composable
internal fun SettingsDetailRoute(
  settings: SettingsFeatureSet,
  aiSetup: AiSetupFeature?,
  environment: RuntimeFeature,
  environmentActions: RuntimeEnvironmentRouteActions,
  route: SettingsDestination,
  onPreviewOnboarding: (() -> Unit)?,
  onRouteChange: (SettingsDestination) -> Unit,
  onBack: () -> Unit,
  onAiEditorVisibilityChanged: (Boolean) -> Unit,
) {
  when (route) {
    SettingsDestination.Home -> Unit
    SettingsDestination.AiModels ->
      AiModelsRoute(
        setup = aiSetup,
        onBack = onBack,
        onEditorVisibilityChanged = onAiEditorVisibilityChanged,
      )
    SettingsDestination.Appearance -> AppearanceSettingsRoute(feature = settings.appearance, onBack = onBack)
    SettingsDestination.LocalEnvironment ->
      RuntimeEnvironmentRoute(
        feature = environment,
        actions = environmentActions,
        sheet = false,
        onBack = onBack,
        onClose = onBack,
        showClose = false,
      )
    SettingsDestination.About ->
      AboutSettingsRoute(
        feature = settings.about,
        onOpenLicenses = { onRouteChange(SettingsDestination.Licenses) },
        onPreviewOnboarding = onPreviewOnboarding,
        onBack = onBack,
      )
    SettingsDestination.Licenses -> LicensesSettingsScreen(onBack = onBack)
  }
}
