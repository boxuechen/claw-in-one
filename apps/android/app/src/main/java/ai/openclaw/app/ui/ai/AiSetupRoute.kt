package ai.openclaw.app.ui.ai

import ai.openclaw.app.R
import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.ai.AiSetupFeature
import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.ai.AiSetupWorkingStage
import ai.openclaw.app.ai.GatewayRestartState
import ai.openclaw.app.ai.GatewayWizardOption
import ai.openclaw.app.ai.GatewayWizardStepType
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTextField
import ai.openclaw.app.ui.design.ClawTheme
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun AiSetupRoute(
  feature: AiSetupFeature?,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
) {
  val state = feature?.state?.collectAsState()?.value ?: AiSetupState.Disconnected
  val autoReconcile =
    (state as? AiSetupState.Wizard)?.takeIf {
      it.step.type == GatewayWizardStepType.Progress || it.waitingForBrowser
    }
  val uriHandler = LocalUriHandler.current
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
  LaunchedEffect(feature, state == AiSetupState.Disconnected) {
    if (state == AiSetupState.Disconnected) feature?.actions?.refresh?.invoke()
  }
  LaunchedEffect(feature, autoReconcile?.step?.id, autoReconcile?.submitting, autoReconcile?.waitingForBrowser, lifecycleState) {
    if (
      lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
      autoReconcile != null &&
      !autoReconcile.submitting
    ) {
      delay(if (autoReconcile.waitingForBrowser) 1_000 else 500)
      feature?.actions?.reconcile?.invoke()
    }
  }
  AiSetupScreen(
    state = state,
    onRefresh = { feature?.actions?.refresh?.invoke() },
    onShowSignIn = { feature?.actions?.showSignIn?.invoke() },
    onShowApiKeys = { feature?.actions?.showApiKeys?.invoke() },
    onChooseAuth = { feature?.actions?.chooseAuth?.invoke(it) },
    onEnterApiKey = { feature?.actions?.enterApiKey?.invoke(it) },
    onSubmitApiKey = { feature?.actions?.submitApiKey?.invoke(it) },
    onChooseModel = { feature?.actions?.chooseModel?.invoke(it) },
    onAnswer = { id, value -> feature?.actions?.answerWizard?.invoke(id, value) },
    onBrowserOpened = { id -> feature?.actions?.browserOpened?.invoke(id) },
    onOpenExternal = { url -> runCatching { uriHandler.openUri(url) }.isSuccess },
    onCopyCode = { code ->
      context
        .getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(nativeString("Sign-in code"), code))
    },
    onReconcile = { feature?.actions?.reconcile?.invoke() },
    onCancel = { feature?.actions?.cancel?.invoke() },
    onBack = onBack,
    modifier = modifier,
  )
}

@Composable
internal fun AiSetupScreen(
  state: AiSetupState,
  onRefresh: () -> Unit,
  onShowSignIn: () -> Unit,
  onShowApiKeys: () -> Unit,
  onChooseAuth: (String) -> Unit,
  onEnterApiKey: (String) -> Unit,
  onSubmitApiKey: (String) -> Unit,
  onChooseModel: (String) -> Unit,
  onAnswer: (String, JsonElement?) -> Unit,
  onReconcile: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  onBrowserOpened: (String) -> Unit = {},
  onOpenExternal: (String) -> Boolean = { false },
  onCopyCode: (String) -> Unit = {},
  onBack: (() -> Unit)? = null,
) {
  val contentWindowInsets =
    if (onBack == null) {
      WindowInsets.safeDrawing
    } else {
      WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    }
  ClawScaffold(
    modifier = modifier,
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
    contentWindowInsets = contentWindowInsets,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      if (onBack == null) {
        AiOnboardingHeader()
      } else {
        AiSettingsHeader(onBack = onBack)
      }
      Spacer(Modifier.height(28.dp))
      Text(text = nativeString("AI"), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      Spacer(Modifier.height(42.dp))
      when (state) {
        is AiSetupState.Choices ->
          AiChoices(
            state,
            onChooseAuth,
            onShowSignIn,
            onShowApiKeys,
          )
        is AiSetupState.SignInChoices ->
          ChoiceList(
            title = nativeString("Sign in"),
            rows = state.options.map { Triple(it.id, it.label, it.hint) },
            onChoose = onChooseAuth,
            onBack = onCancel,
          )
        is AiSetupState.ApiKeyChoices ->
          ChoiceList(
            title = nativeString("Use an API key"),
            rows = state.providers.map { Triple(it.id, it.label, it.hint) },
            onChoose = onEnterApiKey,
            onBack = onCancel,
          )
        is AiSetupState.ApiKeyEntry -> ApiKeyEntry(state, onSubmitApiKey, onCancel)
        is AiSetupState.ModelChoices -> ModelChoices(state, onChooseModel, onCancel)
        is AiSetupState.Wizard -> WizardStep(state, onAnswer, onBrowserOpened, onOpenExternal, onCopyCode, onCancel)
        is AiSetupState.WizardUnknown ->
          StatusContent(
            title = nativeString("Checking ${state.label}"),
            body = nativeString("ClawInOne is restoring the current sign-in step."),
            actionLabel = nativeString("Check again"),
            onAction = onReconcile,
            secondaryActionLabel = nativeString("Cancel"),
            onSecondaryAction = onCancel,
          )
        is AiSetupState.Restarting -> RestartContent(state)
        is AiSetupState.Working ->
          StatusContent(
            title =
              nativeString(
                when (state.stage) {
                  AiSetupWorkingStage.Detecting -> "Choose how to use AI"
                  AiSetupWorkingStage.LoadingModels -> "Choose a model"
                  AiSetupWorkingStage.ApplyingModel -> "Changing the default model"
                  AiSetupWorkingStage.Verifying -> "Verifying your AI"
                },
              ),
            body =
              nativeString(
                when (state.stage) {
                  AiSetupWorkingStage.Detecting -> "Loading available sign-in methods…"
                  AiSetupWorkingStage.LoadingModels -> "Loading models from OpenClaw…"
                  AiSetupWorkingStage.ApplyingModel -> "Applying the selected model…"
                  AiSetupWorkingStage.Verifying -> "OpenClaw is testing the selected model."
                },
              ),
          )
        is AiSetupState.Ready -> StatusContent(nativeString("AI is ready"), state.modelRef)
        is AiSetupState.Failed ->
          StatusContent(
            title = nativeString("AI setup needs attention"),
            body = state.message,
            actionLabel = nativeString("Try again"),
            onAction = onRefresh,
          )
        is AiSetupState.Incompatible ->
          StatusContent(
            title = nativeString("Update OpenClaw"),
            body = nativeString("This setup requires the pinned OpenClaw 9.4 Gateway protocol."),
          )
        AiSetupState.Disconnected ->
          StatusContent(
            title = nativeString("Connecting to OpenClaw"),
            body = nativeString("AI setup continues when the local Gateway is connected."),
            actionLabel = nativeString("Check again"),
            onAction = onRefresh,
          )
      }
    }
  }
}

@Composable
private fun AiSettingsHeader(onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    ClawFloatingIconButton(
      icon = Icons.AutoMirrored.Filled.ArrowBack,
      contentDescription = nativeString("Back"),
      onClick = onBack,
    )
    Text(
      nativeString("AI and models"),
      style = ClawTheme.type.title,
      color = ClawTheme.colors.text,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun AiOnboardingHeader() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Image(painterResource(R.drawable.clawinone_logo), null, Modifier.size(30.dp))
    Text("ClawInOne", style = ClawTheme.type.section, color = ClawTheme.colors.text)
  }
}

@Composable
private fun AiChoices(
  state: AiSetupState.Choices,
  onChooseAuth: (String) -> Unit,
  onShowSignIn: () -> Unit,
  onShowApiKeys: () -> Unit,
) {
  val detection = state.detection
  val featured = detection.authOptions.firstOrNull { it.featured }
  Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(nativeString("Choose how to use AI"), style = ClawTheme.type.display, color = ClawTheme.colors.text)
    Spacer(Modifier.height(6.dp))
    featured?.let { option ->
      ClawPrimaryButton(
        text = nativeString(if (option.brandId == "openai") "Continue with ChatGPT" else option.label),
        onClick = { onChooseAuth(option.id) },
        modifier = Modifier.fillMaxWidth().testTag("ai-featured-auth"),
      )
      option.hint?.let { Text(it, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted) }
    }
    if (featured == null && detection.authOptions.isNotEmpty()) {
      ClawPrimaryButton(
        text = nativeString("Sign in"),
        onClick = onShowSignIn,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (detection.manualProviders.isNotEmpty()) {
      ClawSecondaryButton(
        text = nativeString("Use an API key"),
        onClick = onShowApiKeys,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    val moreAuth = detection.authOptions.filterNot { it == featured }
    if (featured != null && moreAuth.isNotEmpty()) {
      ClawTextButton(
        text = nativeString("Other sign-in methods"),
        onClick = onShowSignIn,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    Spacer(Modifier.weight(1f))
  }
}

@Composable
private fun ChoiceList(
  title: String,
  rows: List<Triple<String, String, String?>>,
  onChoose: (String) -> Unit,
  onBack: () -> Unit,
) {
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, style = ClawTheme.type.display, color = ClawTheme.colors.text)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      items(rows, key = { it.first }) { (id, label, hint) -> AiChoiceRow(label, hint) { onChoose(id) } }
    }
    ClawTextButton(nativeString("Back"), onBack, Modifier.fillMaxWidth())
  }
}

@Composable
private fun ModelChoices(
  state: AiSetupState.ModelChoices,
  onChoose: (String) -> Unit,
  onBack: () -> Unit,
) {
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(nativeString("Choose a model"), style = ClawTheme.type.display, color = ClawTheme.colors.text)
    Text(state.label, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      items(state.models, key = AiModel::providerQualifiedRef) { model ->
        val modelRef = model.providerQualifiedRef()
        val hint =
          buildList {
            add(model.provider)
            if (model.thinkingLevels.isNotEmpty()) add(nativeString("Thinking available"))
            if (modelRef == state.currentModelRef) add(nativeString("Current"))
          }.joinToString(" · ")
        AiChoiceRow(model.name, hint) { onChoose(modelRef) }
      }
    }
    ClawTextButton(nativeString("Back"), onBack, Modifier.fillMaxWidth())
  }
}

private fun AiModel.providerQualifiedRef(): String {
  val prefix = "${provider.trim()}/"
  return if (id.startsWith(prefix)) id else "$prefix$id"
}

@Composable
private fun AiChoiceRow(
  label: String,
  hint: String?,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(label, style = ClawTheme.type.body, color = ClawTheme.colors.text)
    hint?.takeIf { it.isNotBlank() }?.let { Text(it, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted) }
  }
}

@Composable
private fun ApiKeyEntry(
  state: AiSetupState.ApiKeyEntry,
  onSubmit: (String) -> Unit,
  onCancel: () -> Unit,
) {
  var value by remember(state.provider.id) { mutableStateOf("") }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(state.provider.label, style = ClawTheme.type.display, color = ClawTheme.colors.text)
    Text(
      nativeString("The key is sent once to OpenClaw and is not saved by Android."),
      style = ClawTheme.type.body,
      color = ClawTheme.colors.textMuted,
    )
    ClawTextField(value, { value = it }, nativeString("API key"), sensitive = true, label = nativeString("API key"))
    Spacer(Modifier.weight(1f))
    ClawPrimaryButton(
      nativeString("Continue"),
      onClick = {
        val secret = value
        value = ""
        onSubmit(secret)
      },
      enabled = value.isNotBlank(),
      modifier = Modifier.fillMaxWidth(),
    )
    ClawTextButton(nativeString("Back"), onCancel, Modifier.fillMaxWidth())
  }
}

@Composable
private fun WizardStep(
  state: AiSetupState.Wizard,
  onAnswer: (String, JsonElement?) -> Unit,
  onBrowserOpened: (String) -> Unit,
  onOpenExternal: (String) -> Boolean,
  onCopyCode: (String) -> Unit,
  onCancel: () -> Unit,
) {
  val step = state.step
  var text by remember(step.id) { mutableStateOf("") }
  val selected = remember(step.id) { mutableStateListOf<JsonElement>() }
  var browserError by remember(step.id) { mutableStateOf(false) }
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(step.title ?: state.label, style = ClawTheme.type.display, color = ClawTheme.colors.text)
    step.message?.let { Text(it, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted) }
    step.deviceCode?.let { code ->
      SelectionContainer { Text(code.code, style = ClawTheme.type.mono, color = ClawTheme.colors.text) }
      code.expiresInMinutes?.let {
        Text(nativeString("Expires in $it minutes"), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }
      ClawSecondaryButton(
        nativeString("Copy code"),
        { onCopyCode(code.code) },
        Modifier.fillMaxWidth(),
        enabled = !state.submitting,
      )
    }
    step.externalUrl?.let { url ->
      ClawPrimaryButton(
        nativeString("Open browser"),
        {
          val opened = onOpenExternal(url)
          browserError = !opened
          if (opened) onBrowserOpened(step.id)
          if (opened && step.type in setOf(GatewayWizardStepType.Note, GatewayWizardStepType.Action)) {
            onAnswer(step.id, null)
          }
        },
        Modifier.fillMaxWidth(),
        enabled = !state.submitting,
      )
    }
    if (browserError) {
      Text(nativeString("Could not open the browser. Try again."), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
    }
    when (step.type) {
      GatewayWizardStepType.Select ->
        step.options.forEach { option -> WizardOptionRow(option, selected = false, multiSelect = false) { onAnswer(step.id, option.value) } }
      GatewayWizardStepType.MultiSelect ->
        step.options.forEach { option ->
          WizardOptionRow(option, selected = option.value in selected, multiSelect = true) {
            if (option.value in selected) selected.remove(option.value) else selected.add(option.value)
          }
        }
      GatewayWizardStepType.Text ->
        ClawTextField(
          value = text,
          onValueChange = { text = it },
          placeholder = step.placeholder ?: nativeString(if (step.sensitive) "Secret value" else "Answer"),
          sensitive = step.sensitive,
        )
      GatewayWizardStepType.Progress -> CircularProgressIndicator(color = ClawTheme.colors.text)
      GatewayWizardStepType.Action -> Unit
      GatewayWizardStepType.Note,
      GatewayWizardStepType.Confirm,
      -> Unit
    }
    Spacer(Modifier.weight(1f))
    when (step.type) {
      GatewayWizardStepType.Text ->
        ClawPrimaryButton(nativeString("Continue"), {
          val answer = text
          text = ""
          onAnswer(step.id, JsonPrimitive(answer))
        }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && !state.submitting)
      GatewayWizardStepType.MultiSelect ->
        ClawPrimaryButton(nativeString("Continue"), { onAnswer(step.id, JsonArray(selected.toList())) }, Modifier.fillMaxWidth(), enabled = !state.submitting)
      GatewayWizardStepType.Select,
      GatewayWizardStepType.Progress,
      -> Unit
      GatewayWizardStepType.Confirm ->
        ClawPrimaryButton(nativeString("Continue"), { onAnswer(step.id, JsonPrimitive(true)) }, Modifier.fillMaxWidth(), enabled = !state.submitting)
      GatewayWizardStepType.Note,
      GatewayWizardStepType.Action,
      ->
        if (step.externalUrl == null) {
          ClawPrimaryButton(nativeString("Continue"), { onAnswer(step.id, null) }, Modifier.fillMaxWidth(), enabled = !state.submitting)
        }
    }
    ClawTextButton(
      nativeString("Cancel"),
      onCancel,
      Modifier.fillMaxWidth(),
      enabled = !state.submitting || step.type == GatewayWizardStepType.Progress,
    )
  }
}

@Composable
private fun WizardOptionRow(
  option: GatewayWizardOption,
  selected: Boolean,
  multiSelect: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (multiSelect) {
      Checkbox(selected, null)
    } else {
      RadioButton(selected, null)
    }
    Column(Modifier.padding(start = 8.dp)) {
      Text(option.label, style = ClawTheme.type.body, color = ClawTheme.colors.text)
      option.hint?.let { Text(it, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted) }
    }
  }
}

@Composable
private fun RestartContent(state: AiSetupState.Restarting) {
  val copy =
    when (state.restart) {
      is GatewayRestartState.WaitingForSafeRestart -> nativeString("Waiting for current work to finish")
      is GatewayRestartState.Reconnecting -> nativeString("Reconnecting to OpenClaw")
      is GatewayRestartState.UnknownOutcome -> nativeString("Checking the new OpenClaw generation")
      else -> nativeString("Applying configuration")
    }
  StatusContent(nativeString("Setting up ${state.label}"), copy)
}

@Composable
private fun StatusContent(
  title: String,
  body: String,
  actionLabel: String? = null,
  onAction: () -> Unit = {},
  secondaryActionLabel: String? = null,
  onSecondaryAction: () -> Unit = {},
) {
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, style = ClawTheme.type.display, color = ClawTheme.colors.text)
    Text(body, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    Spacer(Modifier.weight(1f))
    actionLabel?.let { ClawPrimaryButton(it, onAction, Modifier.fillMaxWidth()) }
    secondaryActionLabel?.let { ClawTextButton(it, onSecondaryAction, Modifier.fillMaxWidth()) }
  }
}
