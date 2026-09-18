package ai.openclaw.app.ui.onboardingreview

import ai.openclaw.app.ai.AiManualProvider
import ai.openclaw.app.ai.AiSetupAuthKind
import ai.openclaw.app.ai.AiSetupAuthOption
import ai.openclaw.app.ai.AiSetupDetection
import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.eligibility.DeviceBlockReason
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilitySnapshot
import ai.openclaw.app.eligibility.DeviceEligibilityUnknownReason
import ai.openclaw.app.eligibility.DevicePreparationRequirement
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.onboarding.EnvironmentSetupStep
import ai.openclaw.app.onboarding.FirstRunState
import ai.openclaw.app.onboarding.LinuxProvisioningStep
import ai.openclaw.app.supervisor.ComponentKey
import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.requiredSetupComponents
import ai.openclaw.app.ui.ai.AiSetupScreen
import ai.openclaw.app.ui.design.ClawIconButton
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.eligibility.CompatibilityScreen
import ai.openclaw.app.ui.eligibility.toCompatibilityUiState
import ai.openclaw.app.ui.setup.SetupActions
import ai.openclaw.app.ui.setup.SetupScreen
import ai.openclaw.app.ui.setup.SetupUiState
import ai.openclaw.app.ui.setup.TerminalCommandCopyState
import ai.openclaw.app.ui.setup.TerminalCommandUiState
import ai.openclaw.app.ui.setup.bootstrapSourceUrl
import ai.openclaw.app.ui.setup.firstRunCopy
import ai.openclaw.app.ui.setup.setupProgressItems
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val extraAndroidOnboardingReview = "openclaw.onboardingReview"
internal const val extraAndroidOnboardingReviewScene = "openclaw.onboardingReviewScene"

internal val androidOnboardingReviewAvailable = true

internal fun parseAndroidOnboardingReviewScene(intent: Intent?): String? {
  if (intent?.getBooleanExtra(extraAndroidOnboardingReview, false) != true) return null
  return intent.getStringExtra(extraAndroidOnboardingReviewScene)?.trim()?.takeIf(String::isNotEmpty)
    ?: OnboardingReviewScene.Checking.rawValue
}

internal enum class OnboardingReviewScene(
  val rawValue: String,
) {
  Checking("checking"),
  Unsupported("unsupported"),
  Unknown("unknown"),
  DeveloperOptions("developer-options"),
  LinuxEnvironment("linux-environment"),
  LocalService("local-service"),
  Environment("environment"),
  Installation("installation"),
  Gateway("gateway"),
  Ai("ai"),
  Ready("ready"),
  ;

  companion object {
    fun fromRawValue(raw: String?): OnboardingReviewScene = entries.firstOrNull { it.rawValue == raw } ?: Checking
  }
}

@Composable
internal fun AndroidOnboardingReviewRoute(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  initialScene: String? = null,
) {
  val scenes = OnboardingReviewScene.entries
  var sceneIndex by rememberSaveable(initialScene) {
    mutableIntStateOf(scenes.indexOf(OnboardingReviewScene.fromRawValue(initialScene)).coerceAtLeast(0))
  }
  val scene = scenes[sceneIndex]

  BackHandler {
    if (sceneIndex == 0) onClose() else sceneIndex -= 1
  }

  Box(modifier = modifier.fillMaxSize().testTag("onboarding-review")) {
    OnboardingReviewSceneContent(scene = scene, modifier = Modifier.fillMaxSize())
    OnboardingReviewControls(
      current = sceneIndex,
      count = scenes.size,
      onPrevious = { sceneIndex = (sceneIndex - 1).coerceAtLeast(0) },
      onNext = {
        if (sceneIndex == scenes.lastIndex) onClose() else sceneIndex += 1
      },
      onClose = onClose,
      modifier =
        Modifier
          .align(Alignment.TopEnd)
          .statusBarsPadding()
          .padding(top = 10.dp, end = 2.dp),
    )
  }
}

@Composable
private fun OnboardingReviewSceneContent(
  scene: OnboardingReviewScene,
  modifier: Modifier,
) {
  when (scene) {
    OnboardingReviewScene.Checking ->
      CompatibilityScreen(
        state = DeviceEligibilitySnapshot().toCompatibilityUiState(),
        onAction = {},
        modifier = modifier,
      )
    OnboardingReviewScene.Unsupported ->
      CompatibilityScreen(
        state =
          DeviceEligibilitySnapshot(
            eligibility = DeviceEligibility.Blocked(DeviceBlockReason.AvfUnavailable),
          ).toCompatibilityUiState(),
        onAction = {},
        modifier = modifier,
      )
    OnboardingReviewScene.Unknown ->
      CompatibilityScreen(
        state =
          DeviceEligibilitySnapshot(
            eligibility = DeviceEligibility.Unknown(DeviceEligibilityUnknownReason.EvidenceUnavailable),
          ).toCompatibilityUiState(),
        onAction = {},
        modifier = modifier,
      )
    OnboardingReviewScene.Ai ->
      AiSetupScreen(
        state = aiReviewState(),
        onRefresh = {},
        onShowSignIn = {},
        onShowApiKeys = {},
        onChooseAuth = {},
        onEnterApiKey = {},
        onSubmitApiKey = {},
        onChooseModel = {},
        onAnswer = { _, _ -> },
        onReconcile = {},
        onCancel = {},
        modifier = modifier,
      )
    else -> SetupReviewScene(scene = scene, modifier = modifier)
  }
}

@Composable
private fun SetupReviewScene(
  scene: OnboardingReviewScene,
  modifier: Modifier,
) {
  var terminalCopyState by rememberSaveable(scene.rawValue) {
    mutableStateOf(TerminalCommandCopyState.Idle)
  }
  val firstRunState = scene.firstRunState()
  SetupScreen(
    state =
      SetupUiState(
        copy = firstRunCopy(firstRunState),
        launchFailed = false,
        showDevelopmentBadge = true,
        terminalCommand =
          if (scene == OnboardingReviewScene.LocalService) {
            TerminalCommandUiState(
              command =
                "CLAW_IN_ONE_CONFIG_FILE='/mnt/shared/Download/ClawInOne/handoff-review/bootstrap.env' " +
                  "bash '/mnt/shared/Download/ClawInOne/bootstrap-review/bootstrap.sh'",
              sourceUrl = bootstrapSourceUrl("main"),
              copyState = terminalCopyState,
            )
          } else {
            null
          },
        progressItems = setupProgressItems(firstRunState),
      ),
    actions =
      SetupActions(
        runPrimaryAction = {},
        copyTerminalCommand = { terminalCopyState = TerminalCommandCopyState.Copied },
        openTerminal = {},
        openTerminalSource = {},
        refresh = {},
      ),
    modifier = modifier,
  )
}

private fun OnboardingReviewScene.firstRunState(): FirstRunState =
  when (this) {
    OnboardingReviewScene.DeveloperOptions ->
      FirstRunState.DevicePreparation(DevicePreparationRequirement.EnableDeveloperOptions)
    OnboardingReviewScene.LinuxEnvironment ->
      FirstRunState.DevicePreparation(DevicePreparationRequirement.EnableLinuxEnvironment)
    OnboardingReviewScene.LocalService ->
      FirstRunState.LinuxProvisioning(LinuxProvisioningStep.SupervisorRequired(commandReady = true))
    OnboardingReviewScene.Environment ->
      FirstRunState.EnvironmentSetup(EnvironmentSetupStep.InstallReady)
    OnboardingReviewScene.Installation -> installationReviewState()
    OnboardingReviewScene.Gateway ->
      FirstRunState.EnvironmentSetup(EnvironmentSetupStep.ConnectingGateway(starting = true))
    OnboardingReviewScene.Ready -> FirstRunState.Completed
    OnboardingReviewScene.Checking,
    OnboardingReviewScene.Unsupported,
    OnboardingReviewScene.Unknown,
    OnboardingReviewScene.Ai,
    -> error("Scene is not rendered by SetupScreen: $this")
  }

private fun installationReviewState(): FirstRunState {
  val components = requiredSetupComponents()
  return FirstRunState.EnvironmentSetup(
    EnvironmentSetupStep.Installing(
      status =
        SupervisorStatus(
          supervisorBootId = "review-supervisor",
          eventSequence = 7,
          commandSequence = 3,
          timestampEpochSeconds = 1,
          stage = SupervisorStatusStage.DownloadingComponent,
          exitCode = 0,
          planId = "review-plan",
          selectedCapabilities = emptyList(),
          resolvedComponents = components,
          readyCapabilities = emptyList(),
          currentComponent = components.first { it.key == ComponentKey.ChromiumRuntime },
          completedBytes = 140_000_000,
          totalBytes = 480_000_000,
        ),
      resolvedComponents = components,
    ),
  )
}

private fun aiReviewState(): AiSetupState =
  AiSetupState.Choices(
    AiSetupDetection(
      candidates = emptyList(),
      unavailableCandidates = emptyList(),
      manualProviders =
        listOf(
          AiManualProvider(
            id = "deepseek",
            brandId = "deepseek",
            groupLabel = "API key",
            label = "DeepSeek",
            hint = "Use a personal API key",
            iconUrl = null,
            websiteUrl = null,
          ),
        ),
      authOptions =
        listOf(
          AiSetupAuthOption(
            id = "openai-subscription",
            brandId = "openai",
            label = "ChatGPT",
            hint = "Use your ChatGPT subscription",
            groupLabel = "Subscription",
            iconUrl = null,
            websiteUrl = null,
            kind = AiSetupAuthKind.DeviceCode,
            featured = true,
          ),
        ),
      workspace = "/home/claw/.openclaw",
      configuredModel = null,
      setupComplete = false,
    ),
  )

@Composable
private fun OnboardingReviewControls(
  current: Int,
  count: Int,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.testTag("onboarding-review-controls"),
    shape = RoundedCornerShape(28.dp),
    color = ClawTheme.colors.surfaceRaised,
    contentColor = ClawTheme.colors.text,
    border = BorderStroke(1.dp, ClawTheme.colors.borderStrong),
    shadowElevation = 10.dp,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      ClawIconButton(
        icon = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = nativeString("Previous preview"),
        onClick = onPrevious,
        enabled = current > 0,
      )
      Text(
        text = "${current + 1} / $count",
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        modifier = Modifier.testTag("onboarding-review-page"),
      )
      ClawIconButton(
        icon = if (current == count - 1) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = nativeString(if (current == count - 1) "Finish preview" else "Next preview"),
        onClick = onNext,
      )
      ClawIconButton(
        icon = Icons.Default.Close,
        contentDescription = nativeString("Close preview"),
        onClick = onClose,
      )
    }
  }
}
