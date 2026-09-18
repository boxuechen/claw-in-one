package ai.openclaw.app.ui.setup

import ai.openclaw.app.BuildConfig
import ai.openclaw.app.R
import ai.openclaw.app.bootstrap.BootstrapDeliveryError
import ai.openclaw.app.bootstrap.BootstrapHandoffError
import ai.openclaw.app.bootstrap.SupervisorProgress
import ai.openclaw.app.eligibility.DeviceEligibilityAction
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.eligibility.launchDeviceEligibilityAction
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.onboarding.EnvironmentSetupStep
import ai.openclaw.app.onboarding.FirstRunCoordinator
import ai.openclaw.app.onboarding.FirstRunFailure
import ai.openclaw.app.onboarding.FirstRunState
import ai.openclaw.app.onboarding.InstallationProgress
import ai.openclaw.app.onboarding.LinuxProvisioningStep
import ai.openclaw.app.supervisor.SupervisorControlError
import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal enum class SetupPrimaryAction {
  OpenDeviceInfo,
  OpenDeveloperSettings,
  InstallEnvironment,
  Retry,
  None,
}

internal enum class TerminalCommandCopyState {
  Idle,
  Copied,
  Failed,
}

internal data class TerminalCommandUiState(
  val command: String,
  val sourceUrl: String,
  val copyState: TerminalCommandCopyState = TerminalCommandCopyState.Idle,
)

internal data class FirstRunCopy(
  val phase: Int,
  val title: String,
  val body: String,
  val primaryLabel: String? = null,
  val action: SetupPrimaryAction = SetupPrimaryAction.None,
  val statusLabel: String? = null,
  val progressFraction: Float? = null,
  val progressDetail: String? = null,
  val showRefresh: Boolean = false,
)

internal data class SetupUiState(
  val copy: FirstRunCopy,
  val launchFailed: Boolean,
  val showDevelopmentBadge: Boolean = false,
  val terminalCommand: TerminalCommandUiState? = null,
  val progressItems: List<SetupProgressItem> = emptyList(),
)

internal data class SetupActions(
  val runPrimaryAction: () -> Unit,
  val copyTerminalCommand: () -> Unit,
  val openTerminal: () -> Unit,
  val openTerminalSource: () -> Unit,
  val refresh: () -> Unit,
)

/** Binds the text-only first-run surface to the process coordinator. */
@Composable
internal fun SetupRoute(
  coordinator: FirstRunCoordinator,
  modifier: Modifier = Modifier,
) {
  val state by coordinator.state.collectAsState()
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var launchFailed by rememberSaveable { mutableStateOf(false) }
  var terminalCopyState by rememberSaveable { mutableStateOf(TerminalCommandCopyState.Idle) }
  val uriHandler = LocalUriHandler.current

  LaunchedEffect(coordinator) { coordinator.resume() }
  DisposableEffect(lifecycleOwner, coordinator) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          launchFailed = false
          coordinator.resume()
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val copy = firstRunCopy(state)
  val bootstrapCommand = coordinator.readyBootstrapCommand()
  val terminalCommand =
    bootstrapCommand
      ?.takeIf {
        (state as? FirstRunState.LinuxProvisioning)?.step ==
          LinuxProvisioningStep.SupervisorRequired(commandReady = true)
      }?.let { command ->
        TerminalCommandUiState(
          command = command,
          sourceUrl = bootstrapSourceUrl(BuildConfig.GIT_COMMIT),
          copyState = terminalCopyState,
        )
      }
  LaunchedEffect(bootstrapCommand) { terminalCopyState = TerminalCommandCopyState.Idle }
  SetupScreen(
    state =
      SetupUiState(
        copy = copy,
        launchFailed = launchFailed,
        showDevelopmentBadge = BuildConfig.DEBUG,
        terminalCommand = terminalCommand,
        progressItems = setupProgressItems(state),
      ),
    actions =
      SetupActions(
        runPrimaryAction = {
          launchFailed = false
          when (copy.action) {
            SetupPrimaryAction.OpenDeviceInfo ->
              launchFailed = !launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenDeviceInfo)
            SetupPrimaryAction.OpenDeveloperSettings ->
              launchFailed = !launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenDeveloperSettings)
            SetupPrimaryAction.InstallEnvironment -> coordinator.installEnvironment()
            SetupPrimaryAction.Retry -> coordinator.retry()
            SetupPrimaryAction.None -> Unit
          }
        },
        copyTerminalCommand = {
          terminalCopyState =
            if (coordinator.copyBootstrapCommand()) {
              TerminalCommandCopyState.Copied
            } else {
              TerminalCommandCopyState.Failed
            }
        },
        openTerminal = {
          launchFailed = !launchDeviceEligibilityAction(context, DeviceEligibilityAction.OpenTerminal)
        },
        openTerminalSource = {
          launchFailed =
            terminalCommand?.sourceUrl?.let { sourceUrl ->
              runCatching { uriHandler.openUri(sourceUrl) }.isFailure
            } ?: true
        },
        refresh = {
          launchFailed = false
          coordinator.resume()
        },
      ),
    modifier = modifier,
  )
}

/** Presentation-only renderer: no runtime or platform dependencies. */
@Composable
internal fun SetupScreen(
  state: SetupUiState,
  actions: SetupActions,
  modifier: Modifier = Modifier,
) {
  val copy = state.copy
  ClawScaffold(
    modifier = modifier,
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      OnboardingBrandHeader(showDevelopmentBadge = state.showDevelopmentBadge)
      Spacer(modifier = Modifier.height(28.dp))
      FirstRunProgress(phase = copy.phase)
      Spacer(modifier = Modifier.height(48.dp))
      Text(
        text = nativeString(copy.title),
        style = ClawTheme.type.display,
        color = ClawTheme.colors.text,
      )
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = nativeString(copy.body),
        style = ClawTheme.type.body,
        color = ClawTheme.colors.textMuted,
      )
      state.terminalCommand?.let { terminalCommand ->
        Spacer(modifier = Modifier.height(20.dp))
        TerminalCommandPanel(
          state = terminalCommand,
          onOpenSource = actions.openTerminalSource,
        )
      }
      if (state.progressItems.isNotEmpty()) {
        Spacer(modifier = Modifier.height(24.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
          SetupProgressList(
            items = state.progressItems,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          )
        }
      }
      copy.statusLabel?.let { status ->
        Spacer(modifier = Modifier.height(24.dp))
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (copy.progressFraction == null) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = ClawTheme.colors.text,
              strokeWidth = 2.dp,
            )
          }
          Text(
            text = nativeString(status),
            style = ClawTheme.type.body,
            color = ClawTheme.colors.text,
          )
        }
        copy.progressFraction?.let { progress ->
          Spacer(modifier = Modifier.height(14.dp))
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = ClawTheme.colors.text,
            trackColor = ClawTheme.colors.surfacePressed,
          )
          copy.progressDetail?.let { detail ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = nativeString(detail),
              style = ClawTheme.type.caption,
              color = ClawTheme.colors.textMuted,
            )
          }
        }
      }
      if (state.launchFailed) {
        Spacer(modifier = Modifier.height(20.dp))
        Text(
          text = nativeString("Android could not complete that action. Check the current setup and try again."),
          style = ClawTheme.type.body,
          color = ClawTheme.colors.danger,
        )
      }
      if (state.progressItems.isEmpty()) {
        Spacer(modifier = Modifier.weight(1f))
      } else {
        Spacer(modifier = Modifier.height(20.dp))
      }
      copy.primaryLabel?.let { label ->
        ClawPrimaryButton(
          text = nativeString(label),
          onClick = actions.runPrimaryAction,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      state.terminalCommand?.let {
        ClawPrimaryButton(
          text = nativeString("Copy command"),
          onClick = actions.copyTerminalCommand,
          modifier = Modifier.fillMaxWidth().testTag("copy-bootstrap-command"),
        )
        ClawSecondaryButton(
          text = nativeString("Open Terminal"),
          onClick = actions.openTerminal,
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("open-terminal"),
        )
      }
      if (copy.showRefresh) {
        ClawTextButton(
          text = nativeString("Check again"),
          onClick = actions.refresh,
          modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun OnboardingBrandHeader(
  showDevelopmentBadge: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().testTag("onboarding-brand-header"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Image(
      painter = painterResource(R.drawable.clawinone_logo),
      contentDescription = null,
      modifier = Modifier.size(30.dp).testTag("onboarding-brand-logo"),
    )
    Text(
      text = "ClawInOne",
      style = ClawTheme.type.section,
      color = ClawTheme.colors.text,
    )
    if (showDevelopmentBadge) {
      Surface(
        shape = RoundedCornerShape(ClawTheme.radii.pill),
        color = ClawTheme.colors.surface,
        contentColor = ClawTheme.colors.textMuted,
        border = BorderStroke(1.dp, ClawTheme.colors.border),
      ) {
        Text(
          text = nativeString("Dev"),
          style = ClawTheme.type.captionSmall,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
      }
    }
  }
}

@Composable
private fun TerminalCommandPanel(
  state: TerminalCommandUiState,
  onOpenSource: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = nativeString("Terminal command"),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
      )
      ClawTextButton(
        text = nativeString("GitHub source"),
        onClick = onOpenSource,
        modifier = Modifier.testTag("bootstrap-source"),
      )
    }
    Surface(
      modifier = Modifier.fillMaxWidth().testTag("bootstrap-command"),
      shape = RoundedCornerShape(ClawTheme.radii.row),
      color = ClawTheme.colors.codeBg,
      contentColor = ClawTheme.colors.codeText,
      border = BorderStroke(1.dp, ClawTheme.colors.codeBorder),
    ) {
      SelectionContainer {
        Text(
          text = state.command,
          style = ClawTheme.type.mono,
          color = ClawTheme.colors.codeText,
          modifier =
            Modifier
              .padding(horizontal = 14.dp, vertical = 12.dp)
              .semantics { contentDescription = state.command },
        )
      }
    }
    when (state.copyState) {
      TerminalCommandCopyState.Idle -> Unit
      TerminalCommandCopyState.Copied ->
        Text(
          text = nativeString("Command copied"),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.success,
        )
      TerminalCommandCopyState.Failed ->
        Text(
          text = nativeString("Could not copy the command"),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.danger,
        )
    }
  }
}

internal fun bootstrapSourceUrl(commit: String): String {
  val normalized = commit.trim().lowercase()
  val ref = normalized.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: "main"
  return "https://github.com/boxuechen/claw-in-one/tree/$ref/bootstrap"
}

@Composable
private fun FirstRunProgress(phase: Int) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      text = firstRunPhaseLabel(phase),
      style = ClawTheme.type.caption,
      color = ClawTheme.colors.textMuted,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      repeat(FIRST_RUN_PHASE_COUNT) { index ->
        Box(
          modifier =
            Modifier
              .weight(1f)
              .height(3.dp)
              .background(
                color = if (index < phase) ClawTheme.colors.text else ClawTheme.colors.surfacePressed,
                shape = RoundedCornerShape(ClawTheme.radii.pill),
              ),
        )
      }
    }
  }
}

internal fun firstRunCopy(state: FirstRunState): FirstRunCopy =
  when (state) {
    FirstRunState.Completed,
    is FirstRunState.Finalizing,
    ->
      workingCopy(
        phase = 4,
        title = nativeString("ClawInOne is ready"),
        body = nativeString("Your private OpenClaw environment is ready."),
        statusLabel = nativeString("Finishing setup…"),
      )
    FirstRunState.AwaitingDeviceEligibility ->
      workingCopy(
        phase = 1,
        title = nativeString("Checking this device"),
        body = nativeString("ClawInOne is confirming that Android's Linux environment is available."),
        statusLabel = nativeString("Checking…"),
      )
    is FirstRunState.DevicePreparation -> devicePreparationCopy(state.requirement)
    is FirstRunState.LinuxProvisioning ->
      when (val step = state.step) {
        is LinuxProvisioningStep.SupervisorRequired ->
          if (step.commandReady) {
            FirstRunCopy(
              phase = 2,
              title = nativeString("Connect the local service"),
              body = nativeString("Run this one-time command in Terminal."),
              showRefresh = true,
            )
          } else {
            workingCopy(
              phase = 2,
              title = nativeString("Preparing secure setup"),
              body = nativeString("ClawInOne is creating a private one-time connection to the Linux environment."),
              statusLabel = nativeString("Preparing…"),
            )
          }
        is LinuxProvisioningStep.Connecting ->
          workingCopy(
            phase = 2,
            title = nativeString("Connecting the local service"),
            body = nativeString("Setup continues in Linux and resumes automatically if you leave the App."),
            statusLabel = step.progress?.let(::supervisorProgressLabel) ?: nativeString("Waiting for Linux…"),
            showRefresh = true,
          )
      }
    is FirstRunState.EnvironmentSetup -> environmentSetupCopy(state.step)
    is FirstRunState.AiSetup ->
      workingCopy(
        phase = 4,
        title = nativeString("Choose how to use AI"),
        body = nativeString("Loading available sign-in methods…"),
        statusLabel = nativeString("Connecting to AI setup…"),
      )
    is FirstRunState.Failed -> failureCopy(state.failure)
  }

private fun devicePreparationCopy(requirement: DevicePreparationRequirement): FirstRunCopy =
  when (requirement) {
    DevicePreparationRequirement.EnableDeveloperOptions ->
      FirstRunCopy(
        phase = 1,
        title = nativeString("Turn on developer options"),
        body = nativeString("Open About phone, find Build number, and tap it seven times. Return here when Android confirms developer mode."),
        primaryLabel = nativeString("Open About phone"),
        action = SetupPrimaryAction.OpenDeviceInfo,
        showRefresh = true,
      )
    DevicePreparationRequirement.EnableLinuxEnvironment ->
      FirstRunCopy(
        phase = 1,
        title = nativeString("Turn on the Linux environment"),
        body = nativeString("Enable Linux development environment. USB debugging is not needed; Wireless debugging comes later."),
        primaryLabel = nativeString("Open developer options"),
        action = SetupPrimaryAction.OpenDeveloperSettings,
        showRefresh = true,
      )
  }

private fun environmentSetupCopy(step: EnvironmentSetupStep): FirstRunCopy =
  when (step) {
    EnvironmentSetupStep.InstallReady ->
      FirstRunCopy(
        phase = 3,
        title = nativeString("Prepare OpenClaw"),
        body = nativeString("Install Chromium, ADB, VScreen support, Node, and OpenClaw in Linux."),
        primaryLabel = nativeString("Install environment"),
        action = SetupPrimaryAction.InstallEnvironment,
      )
    is EnvironmentSetupStep.Installing ->
      workingCopy(
        phase = 3,
        title = nativeString("Preparing OpenClaw"),
        body = nativeString("Installation continues safely if you leave ClawInOne."),
        statusLabel = step.status?.stage?.let(::setupStageLabel) ?: nativeString("Starting setup…"),
      ).copy(
        progressFraction =
          step.status?.installationProgress()?.let { progress ->
            (progress.completedBytes.toDouble() / progress.totalBytes.toDouble()).toFloat()
          },
        progressDetail = step.status?.installationProgress()?.let(::downloadProgressLabel),
      )
    is EnvironmentSetupStep.ConnectingGateway ->
      workingCopy(
        phase = 3,
        title = nativeString("Connecting to OpenClaw"),
        body = nativeString("ClawInOne is establishing a private connection to the local Gateway."),
        statusLabel = if (step.starting) nativeString("Starting OpenClaw…") else nativeString("Waiting for OpenClaw…"),
        showRefresh = true,
      )
  }

private fun firstRunPhaseLabel(phase: Int): String =
  when (phase.coerceIn(1, FIRST_RUN_PHASE_COUNT)) {
    1 -> nativeString("Device")
    2 -> nativeString("Linux")
    3 -> nativeString("OpenClaw")
    else -> nativeString("AI")
  }

private fun workingCopy(
  phase: Int,
  title: String,
  body: String,
  statusLabel: String,
  showRefresh: Boolean = false,
) = FirstRunCopy(
  phase = phase,
  title = title,
  body = body,
  statusLabel = statusLabel,
  showRefresh = showRefresh,
)

private fun failureCopy(failure: FirstRunFailure): FirstRunCopy =
  FirstRunCopy(
    phase = failurePhase(failure),
    title = failureTitle(failure),
    body = failureBody(failure),
    primaryLabel = nativeString("Try again"),
    action = SetupPrimaryAction.Retry,
  )

private fun failurePhase(failure: FirstRunFailure): Int =
  when (failure) {
    is FirstRunFailure.BootstrapDelivery,
    is FirstRunFailure.BootstrapHandoff,
    is FirstRunFailure.Supervisor,
    -> 2
    is FirstRunFailure.Setup,
    is FirstRunFailure.SetupPlanRejected,
    is FirstRunFailure.GatewayPairing,
    FirstRunFailure.GatewayPortForwarding,
    -> 3
  }

private fun failureTitle(failure: FirstRunFailure): String =
  when (failure) {
    is FirstRunFailure.BootstrapDelivery -> nativeString("Bootstrap could not be prepared")
    is FirstRunFailure.BootstrapHandoff -> nativeString("The local service could not connect")
    is FirstRunFailure.Supervisor -> nativeString("The local service is unavailable")
    is FirstRunFailure.Setup ->
      nativeString("\$component could not be installed", setupComponentTitle(failure.component))
    is FirstRunFailure.SetupPlanRejected -> nativeString("The setup plan could not be accepted")
    is FirstRunFailure.GatewayPairing -> nativeString("OpenClaw could not connect")
    FirstRunFailure.GatewayPortForwarding -> nativeString("Allow OpenClaw's local connection")
  }

private fun failureBody(failure: FirstRunFailure): String =
  when (failure) {
    is FirstRunFailure.BootstrapDelivery -> bootstrapDeliveryErrorText(failure.error)
    is FirstRunFailure.BootstrapHandoff -> bootstrapHandoffErrorText(failure.error)
    is FirstRunFailure.Supervisor -> supervisorErrorText(failure.error)
    is FirstRunFailure.Setup ->
      nativeString(
        "Setup stopped while preparing \$component (exit \$code). Completed tools will not be installed again.",
        setupComponentShortName(failure.component),
        failure.exitCode,
      )
    is FirstRunFailure.SetupPlanRejected ->
      nativeString(
        "The local service rejected this setup plan (exit \$code). Refresh its saved plan and try again.",
        failure.exitCode,
      )
    is FirstRunFailure.GatewayPairing ->
      failure.exitCode?.let { nativeString("The local Gateway stopped with exit code \$code. Try again.", it) }
        ?: nativeString("The local Gateway returned an invalid connection response. Try again.")
    FirstRunFailure.GatewayPortForwarding ->
      nativeString("Approve Terminal's OpenClaw port-forwarding request, then return here and try again. The port is exposed only on this device.")
  }

private fun bootstrapDeliveryErrorText(error: BootstrapDeliveryError): String =
  when (error) {
    BootstrapDeliveryError.StorageUnavailable ->
      nativeString("Android Downloads is unavailable. Check device storage and try again.")
    BootstrapDeliveryError.WriteFailed ->
      nativeString("ClawInOne could not publish the one-time Bootstrap. Check device storage and try again.")
  }

private fun bootstrapHandoffErrorText(error: BootstrapHandoffError): String =
  when (error) {
    BootstrapHandoffError.MissingSession,
    BootstrapHandoffError.EventsMissing,
    -> nativeString("The one-time setup session is missing. Prepare a new Bootstrap and run only the new command.")
    is BootstrapHandoffError.StageFailed ->
      nativeString(
        "Linux setup stopped during \$stage (exit \$code).",
        supervisorProgressLabel(error.stage),
        error.exitCode,
      )
    else -> nativeString("ClawInOne could not verify the one-time setup. Prepare a new Bootstrap and try again.")
  }

private fun supervisorErrorText(error: SupervisorControlError): String =
  when (error) {
    SupervisorControlError.TimedOut ->
      nativeString("The Linux environment did not respond in time. Make sure it is running and try again.")
    SupervisorControlError.StatusMissing ->
      nativeString("The local-service status channel is missing. Make sure Linux is running and try again.")
    else -> nativeString("ClawInOne could not verify the local service. Make sure Linux is running and try again.")
  }

private fun supervisorProgressLabel(stage: SupervisorProgress): String =
  when (stage) {
    SupervisorProgress.BootstrapExecuted -> nativeString("Bootstrap started")
    SupervisorProgress.InstallingSupervisor -> nativeString("Installing the local service")
    SupervisorProgress.StartingSupervisor -> nativeString("Starting the local service")
  }

internal fun setupStageLabel(stage: SupervisorStatusStage): String =
  when (stage) {
    SupervisorStatusStage.SupervisorReady,
    SupervisorStatusStage.CapabilitiesRequired,
    -> nativeString("Preparing OpenClaw")
    SupervisorStatusStage.CapabilityPlanAccepted -> nativeString("Preparing your setup")
    SupervisorStatusStage.DownloadingNode -> nativeString("Downloading the runtime")
    SupervisorStatusStage.VerifyingNode -> nativeString("Verifying the runtime")
    SupervisorStatusStage.InstallingNode -> nativeString("Installing the runtime")
    SupervisorStatusStage.DownloadingOpenClaw -> nativeString("Downloading OpenClaw")
    SupervisorStatusStage.VerifyingOpenClaw -> nativeString("Verifying OpenClaw")
    SupervisorStatusStage.InstallingOpenClaw -> nativeString("Installing OpenClaw")
    SupervisorStatusStage.VerifyingOpenClawInstallation -> nativeString("Verifying the installation")
    SupervisorStatusStage.OpenClawReady -> nativeString("OpenClaw is ready")
    SupervisorStatusStage.DownloadingComponent -> nativeString("Downloading the selected component")
    SupervisorStatusStage.VerifyingComponent -> nativeString("Checking the selected component")
    SupervisorStatusStage.InstallingComponent -> nativeString("Installing the selected component")
    SupervisorStatusStage.ComponentReady -> nativeString("Component is ready")
    SupervisorStatusStage.CapabilitiesReady -> nativeString("ClawInOne is ready")
    SupervisorStatusStage.CapabilityFailed -> nativeString("Setup stopped")
    SupervisorStatusStage.CapabilityPlanRejected -> nativeString("Setup plan was rejected")
    SupervisorStatusStage.GatewayConfiguring -> nativeString("Configuring the local Gateway")
    SupervisorStatusStage.GatewayStarting -> nativeString("Starting the local Gateway")
    SupervisorStatusStage.GatewayHealthy -> nativeString("Checking the local Gateway")
    SupervisorStatusStage.GatewayReady -> nativeString("The local Gateway is ready")
    SupervisorStatusStage.GatewayPairingReady -> nativeString("The local Gateway pairing is ready")
    SupervisorStatusStage.GatewayPairingFailed -> nativeString("The local Gateway pairing stopped")
    SupervisorStatusStage.GatewayFailed -> nativeString("The local Gateway stopped")
    SupervisorStatusStage.GatewayNotStarted -> nativeString("Waiting to start the local Gateway")
  }

private fun SupervisorStatus.installationProgress(): InstallationProgress? {
  val completed = completedBytes ?: return null
  val total = totalBytes ?: return null
  return InstallationProgress(completedBytes = completed, totalBytes = total)
}

private fun downloadProgressLabel(progress: InstallationProgress): String {
  val completedMiB = progress.completedBytes.toDouble() / (1024 * 1024)
  val totalMiB = progress.totalBytes.toDouble() / (1024 * 1024)
  val percent =
    (progress.completedBytes.toDouble() * 100 / progress.totalBytes.toDouble()).toInt().coerceIn(0, 100)
  return nativeString(
    "\$completed of \$total MB · \$percent%",
    "%.1f".format(completedMiB),
    "%.1f".format(totalMiB),
    percent,
  )
}

private const val FIRST_RUN_PHASE_COUNT = 4
