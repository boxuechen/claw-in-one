package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.entry.AppEntryState
import ai.openclaw.app.onboarding.FirstRunState
import ai.openclaw.app.ui.ai.AiSetupRoute
import ai.openclaw.app.ui.eligibility.CompatibilityRoute
import ai.openclaw.app.ui.runtime.RuntimeGateRoute
import ai.openclaw.app.ui.setup.SetupRoute
import ai.openclaw.app.ui.shell.AppShellRoute
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/** Routes persisted app state to onboarding or the authenticated app shell. */
@Composable
fun RootRoute(viewModel: MainViewModel) {
  val entryState by viewModel.appEntryFeature.state.collectAsState()
  val firstRunState by viewModel.firstRunCoordinator.state.collectAsState()
  val aiSetupFeature by viewModel.aiSetupFeature.collectAsState(initial = null)
  val screenshotShellState by viewModel.screenshotShellState.collectAsState()

  when (entryState) {
    is AppEntryState.Compatibility -> {
      CompatibilityRoute(
        feature = viewModel.appEntryFeature,
        modifier = Modifier.fillMaxSize(),
      )
      return
    }
    AppEntryState.FirstRun -> {
      when (firstRunState) {
        is FirstRunState.AiSetup ->
          AiSetupRoute(feature = aiSetupFeature, modifier = Modifier.fillMaxSize())
        else ->
          SetupRoute(
            coordinator = viewModel.firstRunCoordinator,
            modifier = Modifier.fillMaxSize(),
          )
      }
      return
    }
    AppEntryState.Product -> Unit
  }

  Box(Modifier.fillMaxSize()) {
    AppShellRoute(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    if (screenshotShellState == null) {
      RuntimeGateRoute(
        feature = viewModel.runtimeFeature,
        prepareSupervisorRepairCommand = viewModel::prepareSupervisorRepairCommand,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
