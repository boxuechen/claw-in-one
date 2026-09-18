package ai.openclaw.app.ui.shell

import ai.openclaw.app.HomeDestination
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.ui.approval.collectInbox
import ai.openclaw.app.ui.chat.ChatShellRoute
import ai.openclaw.app.ui.chat.projectStarters
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.devkit.DevKitRoute
import ai.openclaw.app.ui.environment.rememberRuntimeEnvironmentRouteActions
import ai.openclaw.app.ui.extensions.ExtensionCenterRoute
import ai.openclaw.app.ui.onboardingreview.AndroidOnboardingReviewRoute
import ai.openclaw.app.ui.onboardingreview.androidOnboardingReviewAvailable
import ai.openclaw.app.ui.settings.SettingsDestination
import ai.openclaw.app.ui.settings.SettingsSheetRoute
import ai.openclaw.app.ui.sidebar.SidebarDestination
import ai.openclaw.app.ui.sidebar.SidebarRoute
import ai.openclaw.app.ui.terminal.TerminalRoute
import ai.openclaw.app.ui.terminal.web.TerminalViewRetention
import ai.openclaw.app.ui.vscreen.VScreenRoute
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Chat-first route that owns navigation overlays and delegates feature presentation. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppShellRoute(
  viewModel: MainViewModel,
  modifier: Modifier = Modifier,
) {
  val shellState = remember { ShellState() }
  val focusManager = LocalFocusManager.current
  val keyboard = LocalSoftwareKeyboardController.current
  ReleaseInputFocusOnBackground()
  LaunchedEffect(shellState.settingsVisible, shellState.devKitVisible) {
    if (shellState.settingsVisible || shellState.devKitVisible) {
      focusManager.clearFocus(force = true)
      keyboard?.hide()
    }
  }
  val terminalRetention = remember { TerminalViewRetention() }
  DisposableEffect(terminalRetention) { onDispose { terminalRetention.release() } }
  val sidebarDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val coroutineScope = rememberCoroutineScope()
  val requestedHomeDestination by viewModel.requestedHomeDestination.collectAsState()
  val screenshotShellState by viewModel.screenshotShellState.collectAsState()
  val runtimeInitialized by viewModel.runtimeInitialized.collectAsState()
  val vscreen by viewModel.vscreen.collectAsState(initial = null)
  val chatDirectory by viewModel.chatDirectory.collectAsState(initial = null)
  val projects by viewModel.projects.collectAsState(initial = null)
  val chatCurrentWork by viewModel.chatCurrentWork.collectAsState(initial = null)
  val androidAppResults by viewModel.androidAppResults.collectAsState(initial = null)
  val webProjectResults by viewModel.webProjectResults.collectAsState(initial = null)
  val chatExecution by viewModel.chatExecution.collectAsState(initial = null)
  val chatGateway by viewModel.chatGateway.collectAsState(initial = null)
  val chatOutbox by viewModel.chatOutbox.collectAsState(initial = null)
  val chatSessionOptions by viewModel.chatSessionOptions.collectAsState(initial = null)
  val chatHistory by viewModel.chatHistory.collectAsState(initial = null)
  val chatSessionKey =
    key(chatHistory) {
      chatHistory
        ?.selection
        ?.key
        ?.collectAsState()
        ?.value ?: "main"
    }
  val approvalFeature by viewModel.approvalFeature.collectAsState(initial = null)
  val permissionFeature by viewModel.permissionFeature.collectAsState(initial = null)
  val aiSetupFeature by viewModel.aiSetupFeature.collectAsState(initial = null)
  val pluginFeature by viewModel.pluginFeature.collectAsState(initial = null)
  val skillFeature by viewModel.skillFeature.collectAsState(initial = null)
  val connectionFeature by viewModel.connectionFeature.collectAsState(initial = null)
  val terminalFeature by viewModel.terminalFeature.collectAsState(initial = null)
  val runtimeFeature = viewModel.runtimeFeature
  val devKitFeature = viewModel.devKitFeature
  val devKitCatalog by
    devKitFeature.catalog.collectAsState(
      initial = devKitFeature.initialCatalog(),
    )
  val runtimeActions = rememberRuntimeEnvironmentRouteActions(runtimeFeature)
  val approvals = approvalFeature.collectInbox()
  var focusApprovalId by rememberSaveable { mutableStateOf<String?>(null) }
  var onboardingReviewVisible by rememberSaveable { mutableStateOf(false) }
  val activeGatewayStableId by viewModel.activeGatewayStableId.collectAsState()
  val developerCapabilities =
    key(projects) {
      projects
        ?.capabilities
        ?.collectAsState()
        ?.value
    }
  val availableProjectStarters =
    remember(developerCapabilities, screenshotShellState) {
      if (screenshotShellState == null) {
        projectStarters(developerCapabilities)
      } else {
        emptyList()
      }
    }
  val launchState = viewModel.chatLaunchState
  val launchDraftSelected = screenshotShellState?.launchChatCreated == true || launchState.selected

  LaunchedEffect(requestedHomeDestination) {
    val destination = requestedHomeDestination ?: return@LaunchedEffect
    when (destination) {
      HomeDestination.Connect -> shellState.openSettings(SettingsDestination.LocalEnvironment)
      HomeDestination.Settings ->
        shellState.openSettings(viewModel.requestedSettingsRoute.value ?: SettingsDestination.Home)
      HomeDestination.Chat -> {
        shellState.closeSettings()
      }
    }
    viewModel.clearRequestedSettingsRoute()
    viewModel.clearRequestedHomeDestination()
  }

  LaunchedEffect(runtimeInitialized, projects, activeGatewayStableId, chatSessionKey, launchDraftSelected) {
    if (
      !launchDraftSelected &&
      screenshotShellState?.suppressAutomaticChat != true &&
      runtimeInitialized &&
      activeGatewayStableId != null
    ) {
      projects?.actions?.let { actions -> launchState.selectDraft(actions.newProject) }
    }
  }

  LaunchedEffect(screenshotShellState) {
    if (screenshotShellState?.openDrawer == true) sidebarDrawerState.open()
  }

  BackHandler(enabled = shellState.settingsVisible || shellState.terminalVisible) {
    shellState.back()
  }

  val openSidebar: () -> Unit = {
    projects?.actions?.refreshCatalog?.invoke()
    coroutineScope.launch { sidebarDrawerState.open() }
  }
  val closeSidebar: () -> Unit = {
    coroutineScope.launch { sidebarDrawerState.close() }
  }
  val selectSidebarDestination: (SidebarDestination) -> Unit = { destination ->
    when (destination) {
      SidebarDestination.VScreen -> {
        viewModel.vscreenPresentation.openFullscreen()
        vscreen?.ensure?.invoke()
      }
      SidebarDestination.DevKit -> shellState.openDevKit()
      SidebarDestination.Plugins -> shellState.openExtensionCenter()
      SidebarDestination.Terminal -> shellState.openTerminal()
    }
    closeSidebar()
  }

  if (onboardingReviewVisible) {
    AndroidOnboardingReviewRoute(
      onClose = { onboardingReviewVisible = false },
      modifier = Modifier.fillMaxSize(),
    )
    return
  }

  Box(modifier = modifier.fillMaxSize().background(ClawTheme.colors.canvas)) {
    DrawerHost(
      drawerState = sidebarDrawerState,
      drawerContent = {
        SidebarRoute(
          catalog = chatDirectory?.catalog,
          projects = projects,
          approvals = approvals,
          activeSessionKey = chatSessionKey,
          onOpenSettings = { shellState.openSettings() },
          onNewProject = {
            focusApprovalId = null
            projects?.actions?.newProject?.invoke()
            closeSidebar()
          },
          onNewChatInProject = { projectId ->
            focusApprovalId = null
            projects?.actions?.newChat?.invoke(projectId)
            closeSidebar()
          },
          onSelectSession = { session ->
            focusApprovalId = approvals.pendingFor(ApprovalSession(session.key, session.ownerAgentId)).firstOrNull()?.id
            session.projectId?.let { projects?.actions?.selectProject?.invoke(it) }
            chatDirectory?.navigation?.select?.invoke(session.key, session.ownerAgentId)
            closeSidebar()
          },
          onSelectDestination = selectSidebarDestination,
        )
      },
    ) {
      ChatShellRoute(
        composer = viewModel.chatComposer,
        projectStarters = availableProjectStarters,
        history = chatHistory,
        currentWork = chatCurrentWork,
        execution = chatExecution,
        appResults = androidAppResults,
        webResults = webProjectResults,
        gateway = chatGateway,
        outbox = chatOutbox,
        directory = chatDirectory,
        sessionOptions = chatSessionOptions,
        approvals = approvalFeature,
        permissions = permissionFeature,
        projects = projects,
        skills = skillFeature,
        devKitCatalog = devKitCatalog,
        forceBlank = screenshotShellState?.forceBlankChat == true || !launchDraftSelected,
        showSidebarButton = true,
        onOpenSidebar = openSidebar,
        onOpenAiSettings = { shellState.openSettings(SettingsDestination.AiModels) },
        skillSelectionRequest = shellState.chatSkillSelectionRequest,
        onSkillSelectionConsumed = shellState::consumeChatSkillSelection,
        focusApprovalId = focusApprovalId,
        onApprovalFocusConsumed = { focusApprovalId = null },
      )
    }

    shellState.settingsRoute?.let { settingsRoute ->
      Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = shellState::closeSettings))
      Surface(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp).testTag("settings-sheet"),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = ClawTheme.colors.surface,
        contentColor = ClawTheme.colors.text,
      ) {
        SettingsSheetRoute(
          settings = viewModel.settingsFeatures,
          aiSetup = aiSetupFeature,
          environment = runtimeFeature,
          environmentActions = runtimeActions,
          route = settingsRoute,
          onPreviewOnboarding =
            if (androidOnboardingReviewAvailable) {
              { onboardingReviewVisible = true }
            } else {
              null
            },
          onRouteChange = shellState::openSettingsRoute,
          onBack = { shellState.back() },
          onClose = shellState::closeSettings,
        )
      }
    }

    shellState.extensionCenterRoute?.let { extensionRoute ->
      ExtensionCenterRoute(
        plugin = pluginFeature,
        pluginDirectory = viewModel.pluginDirectoryFeature,
        skill = skillFeature,
        skillDirectory = viewModel.skillDirectoryFeature,
        connections = connectionFeature,
        destination = extensionRoute,
        onDestinationChange = shellState::openExtensionCenterRoute,
        onBack = { shellState.back() },
      )
    }

    shellState.devKitRoute?.let { devKitRoute ->
      DevKitRoute(
        feature = viewModel.devKitFeature,
        environmentActions = runtimeActions,
        destination = devKitRoute,
        onDestinationChange = shellState::openDevKitRoute,
        onOpenVScreen = {
          viewModel.vscreenPresentation.openFullscreen()
          vscreen?.ensure?.invoke()
        },
        onUseSkillInChat = shellState::useSkillInChat,
        onBack = { shellState.back() },
      )
    }

    if (shellState.terminalVisible) {
      TerminalRoute(
        feature = terminalFeature,
        environment = runtimeFeature,
        environmentActions = runtimeActions,
        retention = terminalRetention,
        onBack = { shellState.back() },
      )
    }

    VScreenRoute(
      feature = vscreen,
      coordinator = viewModel.vscreenPresentation,
    )
  }
}
