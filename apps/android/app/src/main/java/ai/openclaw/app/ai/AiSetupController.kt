package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayMethod
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.i18n.nativeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal enum class AiSetupWorkingStage { Detecting, LoadingModels, ApplyingModel, Verifying }

internal sealed interface AiSetupState {
  data object Disconnected : AiSetupState

  data class Incompatible(
    val missingMethods: Set<String>,
  ) : AiSetupState

  data class Working(
    val stage: AiSetupWorkingStage,
  ) : AiSetupState

  data class Choices(
    val detection: AiSetupDetection,
  ) : AiSetupState

  data class SignInChoices(
    val options: List<AiSetupAuthOption>,
  ) : AiSetupState

  data class ApiKeyChoices(
    val providers: List<AiManualProvider>,
  ) : AiSetupState

  data class ApiKeyEntry(
    val provider: AiManualProvider,
  ) : AiSetupState

  data class ModelChoices(
    val label: String,
    val models: List<AiModel>,
    val currentModelRef: String?,
  ) : AiSetupState

  data class Wizard(
    val label: String,
    val step: GatewayWizardStep,
    val submitting: Boolean,
    val waitingForBrowser: Boolean = false,
  ) : AiSetupState

  data class WizardUnknown(
    val label: String,
  ) : AiSetupState

  data class Restarting(
    val label: String,
    val restart: GatewayRestartState,
  ) : AiSetupState

  data class Ready(
    val modelRef: String,
    val latencyMs: Double,
  ) : AiSetupState

  data class Failed(
    val message: String,
  ) : AiSetupState
}

internal data class AiSetupActions(
  val refresh: () -> Unit,
  val changeAccess: () -> Unit,
  val changeModel: () -> Unit,
  val showSignIn: () -> Unit,
  val showApiKeys: () -> Unit,
  val chooseAuth: (String) -> Unit,
  val enterApiKey: (String) -> Unit,
  val submitApiKey: (String) -> Unit,
  val chooseModel: (String) -> Unit,
  val answerWizard: (String, JsonElement?) -> Unit,
  val browserOpened: (String) -> Unit,
  val reconcile: () -> Unit,
  val cancel: () -> Unit,
  val dismiss: () -> Unit,
)

internal class AiSetupFeature(
  val state: kotlinx.coroutines.flow.StateFlow<AiSetupState>,
  val actions: AiSetupActions,
)

/** Owns AI access and the verified default-Agent model route. Gateway owns credentials. */
internal class AiSetupController(
  private val scope: CoroutineScope,
  private val transport: AiGatewayTransport,
  private val wizard: GatewayWizardController,
  private val restart: GatewayRestartCoordinator,
  private val json: Json,
  private val sessionStore: AiSetupSessionStore = AiSetupSessionStore.None,
  private val actionsEnabled: Boolean = true,
  private val newSessionId: () -> String = { UUID.randomUUID().toString() },
  private val onReady: () -> Unit = {},
) {
  private data class PendingWizard(
    val label: String,
    val purpose: AiSetupWizardPurpose,
    val sessionId: String,
    val gatewayStableId: String,
    val providerId: String? = null,
    val targetModelRef: String? = null,
  )

  private sealed interface ModelChoicePurpose {
    data class Default(
      val baseline: String?,
    ) : ModelChoicePurpose

    data class Authenticated(
      val baseline: String?,
    ) : ModelChoicePurpose

    data class ApiKey(
      val provider: AiManualProvider,
    ) : ModelChoicePurpose
  }

  private sealed interface PostRestart {
    data class LoadAuthenticatedModels(
      val label: String,
      val providerId: String?,
      val baseline: String,
    ) : PostRestart

    data object Verify : PostRestart
  }

  private val mutableState = MutableStateFlow<AiSetupState>(AiSetupState.Disconnected)
  val state = mutableState.asStateFlow()
  val feature =
    AiSetupFeature(
      state,
      AiSetupActions(
        refresh = ::refresh,
        changeAccess = ::changeAccess,
        changeModel = ::changeModel,
        showSignIn = ::showSignIn,
        showApiKeys = ::showApiKeys,
        chooseAuth = ::chooseAuth,
        enterApiKey = ::enterApiKey,
        submitApiKey = ::submitApiKey,
        chooseModel = ::chooseModel,
        answerWizard = wizard::answer,
        browserOpened = wizard::browserOpened,
        reconcile = ::reconcile,
        cancel = ::cancel,
        dismiss = ::dismiss,
      ),
    )
  private val operationSequence = AtomicLong()
  private var detection: AiSetupDetection? = null
  private var pendingWizard: PendingWizard? = null
  private var pendingRestartLabel: String? = null
  private var postRestart: PostRestart? = null
  private var modelChoicePurpose: ModelChoicePurpose? = null
  private var pendingApiKey: String? = null
  private var lastWizardTerminal: GatewayWizardState? = null
  private var lastRestartTerminal: GatewayRestartState? = null
  private var refreshAfterWizardCancellation = false

  init {
    if (actionsEnabled) {
      sessionStore.load()?.let { checkpoint ->
        pendingWizard =
          PendingWizard(
            label = checkpoint.label,
            purpose = checkpoint.purpose,
            sessionId = checkpoint.sessionId,
            gatewayStableId = checkpoint.gatewayStableId,
            providerId = checkpoint.providerId,
            targetModelRef = checkpoint.targetModelRef,
          )
        wizard.restore(OWNER_ID, checkpoint.sessionId)
      }
    }
    scope.launch { wizard.state.collect(::onWizardState) }
    scope.launch { restart.state.collect(::onRestartState) }
  }

  fun onConnectionChanged() {
    val connection = transport.capture()
    when {
      connection == null -> {
        if (restart.state.value.isPlannedInterruption()) return
        operationSequence.incrementAndGet()
        clearRequestLocalSecret()
        mutableState.value = AiSetupState.Disconnected
      }
      connection.missingRequiredAiMethods().isNotEmpty() -> {
        operationSequence.incrementAndGet()
        clearRequestLocalSecret()
        mutableState.value = AiSetupState.Incompatible(connection.missingRequiredAiMethods())
      }
      !connection.adminScope -> {
        operationSequence.incrementAndGet()
        clearRequestLocalSecret()
        mutableState.value = AiSetupState.Failed(nativeString("Administrator access is required to configure AI."))
      }
      pendingWizard?.gatewayStableId != null && pendingWizard?.gatewayStableId != connection.stableId -> {
        pendingWizard?.let { sessionStore.clear(it.sessionId) }
        pendingWizard = null
        wizard.clear(OWNER_ID)
        refresh()
      }
      pendingRestartLabel != null -> restart.reconcile()
      pendingWizard != null -> wizard.reconcile()
      mutableState.value is AiSetupState.Disconnected || mutableState.value is AiSetupState.Incompatible -> refresh()
    }
  }

  fun refresh() = detect(alwaysShowChoices = false)

  fun changeAccess() = detect(alwaysShowChoices = true)

  fun changeModel() {
    val current = detection?.configuredModel ?: (mutableState.value as? AiSetupState.Ready)?.modelRef
    if (current == null) {
      refresh()
      return
    }
    loadModelChoices(
      label = nativeString("Default model"),
      providerId = null,
      currentModelRef = current,
      purpose = ModelChoicePurpose.Default(current),
      includeModelsAwaitingKey = false,
    )
  }

  private fun detect(alwaysShowChoices: Boolean) {
    if (!actionsEnabled) return
    clearRequestLocalSecret()
    val connection = usableConnection() ?: return
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = AiSetupState.Working(AiSetupWorkingStage.Detecting)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val result = requestDetection(connection)
        val androidDetection = AndroidAuthSurfacePolicy.setupDetection(result)
        detection = androidDetection
        publish(connection, sequence) {
          if (!alwaysShowChoices && result.setupComplete && result.configuredModel != null) {
            verify()
            mutableState.value
          } else {
            AiSetupState.Choices(androidDetection)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        publish(connection, sequence) { AiSetupState.Failed(nativeString("Could not load AI setup choices.")) }
      }
    }
  }

  private fun showSignIn() {
    val allOptions = detection?.authOptions.orEmpty()
    val primary = allOptions.firstOrNull(AiSetupAuthOption::featured)
    val options =
      if (primary != null) {
        allOptions.filterNot { it.id == primary.id }
      } else {
        allOptions
      }
    if (options.isNotEmpty()) mutableState.value = AiSetupState.SignInChoices(options)
  }

  private fun showApiKeys() {
    val providers = detection?.manualProviders.orEmpty()
    if (providers.isNotEmpty()) mutableState.value = AiSetupState.ApiKeyChoices(providers)
  }

  private fun chooseAuth(id: String) {
    if (!actionsEnabled || pendingWizard != null) return
    val option = detection?.authOptions?.firstOrNull { it.id == id } ?: return
    val connection = transport.capture() ?: return
    val sessionId = newSessionId()
    startWizard(
      pending = PendingWizard(option.label, AiSetupWizardPurpose.Auth, sessionId, connection.stableId, option.brandId),
      launch =
        GatewayWizardLaunch(
          ownerId = OWNER_ID,
          sessionId = sessionId,
          method = GatewayMethod.OpenclawSetupAuthStart,
          params =
            aiSetupAuthStartParams(
              sessionId = sessionId,
              authChoice = option.id,
              agentId = connection.defaultAgentId,
              workspace = detection?.workspace,
              nativeSessionCatalogsEnabled = false,
            ),
        ),
    )
  }

  private fun enterApiKey(id: String) {
    clearRequestLocalSecret()
    val provider = detection?.manualProviders?.firstOrNull { it.id == id } ?: return
    mutableState.value = AiSetupState.ApiKeyEntry(provider)
  }

  private fun submitApiKey(apiKey: String) {
    val entry = mutableState.value as? AiSetupState.ApiKeyEntry ?: return
    pendingApiKey = apiKey.takeIf { it.isNotBlank() } ?: return
    loadModelChoices(
      label = entry.provider.label,
      providerId = entry.provider.brandId ?: entry.provider.id,
      currentModelRef = null,
      purpose = ModelChoicePurpose.ApiKey(entry.provider),
      includeModelsAwaitingKey = true,
    )
  }

  private fun chooseModel(modelRef: String) {
    val choices = mutableState.value as? AiSetupState.ModelChoices ?: return
    val selected = choices.models.firstOrNull { it.providerQualifiedRef() == modelRef } ?: return
    when (val purpose = modelChoicePurpose ?: return) {
      is ModelChoicePurpose.ApiKey -> {
        val secret = pendingApiKey ?: return
        pendingApiKey = null
        modelChoicePurpose = null
        startActivation(
          purpose.provider.label,
          AiSetupActivation(purpose.provider.id, secret, selected.providerQualifiedRef()),
        )
      }
      is ModelChoicePurpose.Default -> applyDefaultModel(selected.providerQualifiedRef(), purpose.baseline)
      is ModelChoicePurpose.Authenticated -> applyDefaultModel(selected.providerQualifiedRef(), purpose.baseline)
    }
  }

  private fun loadModelChoices(
    label: String,
    providerId: String?,
    currentModelRef: String?,
    purpose: ModelChoicePurpose,
    includeModelsAwaitingKey: Boolean,
  ) {
    val connection = usableConnection()
    if (connection == null) {
      clearRequestLocalSecret()
      return
    }
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = AiSetupState.Working(AiSetupWorkingStage.LoadingModels)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val snapshot =
          parseModelCatalog(
            transport.request(
              connection,
              GatewayMethod.ModelsList.rawValue,
              modelsListParams(
                agentId = connection.defaultAgentId,
                refresh = true,
                provider = providerId,
                view = "all",
              ),
              SETUP_REQUEST_TIMEOUT_MS,
            ),
            json,
          ) ?: error("Malformed model catalog")
        val models =
          snapshot.models
            .filter { providerId == null || it.provider == providerId }
            .filter { if (includeModelsAwaitingKey) it.apiKeySupported != false else it.available != false }
            .distinctBy(AiModel::providerQualifiedRef)
            .sortedWith(compareByDescending<AiModel> { it.providerQualifiedRef() == currentModelRef }.thenBy { it.name.lowercase() })
        publish(connection, sequence) {
          if (models.isEmpty()) {
            clearRequestLocalSecret()
            AiSetupState.Failed(nativeString("No usable models are available for this AI access method."))
          } else {
            modelChoicePurpose = purpose
            AiSetupState.ModelChoices(label, models, currentModelRef)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        publish(connection, sequence) {
          clearRequestLocalSecret()
          AiSetupState.Failed(nativeString("Could not load AI models."))
        }
      }
    }
  }

  private fun applyDefaultModel(
    target: String,
    baseline: String?,
  ) {
    modelChoicePurpose = null
    if (target == baseline) {
      verify()
      return
    }
    val connection = usableConnection() ?: return
    val agentId = connection.defaultAgentId
    if (agentId.isNullOrBlank()) {
      mutableState.value = AiSetupState.Failed(nativeString("OpenClaw did not provide a default Agent."))
      return
    }
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = AiSetupState.Working(AiSetupWorkingStage.ApplyingModel)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        transport.request(connection, GatewayMethod.AgentsUpdate.rawValue, agentsUpdateModelParams(agentId, target), SETUP_REQUEST_TIMEOUT_MS)
        verifyAppliedModel(connection, sequence, target, baseline, agentId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: GatewayRequestOutcomeUnknown) {
        reconcileUnknownModelUpdate(connection, sequence, target, baseline, agentId)
      } catch (_: Throwable) {
        publish(connection, sequence) { AiSetupState.Failed(nativeString("Could not change the default model.")) }
      }
    }
  }

  private suspend fun verifyAppliedModel(
    connection: AiGatewayConnection,
    sequence: Long,
    target: String,
    baseline: String?,
    agentId: String,
  ) {
    publish(connection, sequence) { AiSetupState.Working(AiSetupWorkingStage.Verifying) }
    when (val result = requestVerification(connection)) {
      is AiSetupVerification.Ready -> {
        if (result.modelRef != target) {
          val restored = rollbackModel(connection, agentId, baseline)
          publish(connection, sequence) {
            AiSetupState.Failed(
              rollbackAwareMessage(
                nativeString("OpenClaw verified a different default model."),
                restored,
              ),
            )
          }
        } else {
          publish(connection, sequence) { readyState(result) }
        }
      }
      is AiSetupVerification.Failed -> {
        val restored = rollbackModel(connection, agentId, baseline)
        publish(connection, sequence) { AiSetupState.Failed(rollbackAwareMessage(result.error, restored)) }
      }
    }
  }

  private suspend fun reconcileUnknownModelUpdate(
    connection: AiGatewayConnection,
    sequence: Long,
    target: String,
    baseline: String?,
    agentId: String,
  ) {
    val detected = runCatching { requestDetection(connection) }.getOrNull()
    when (detected?.configuredModel) {
      target -> verifyAppliedModel(connection, sequence, target, baseline, agentId)
      baseline -> publish(connection, sequence) { AiSetupState.Failed(nativeString("The default model change did not apply.")) }
      else -> publish(connection, sequence) { AiSetupState.Failed(nativeString("The default model outcome is unknown. Check OpenClaw before trying again.")) }
    }
  }

  private suspend fun rollbackModel(
    connection: AiGatewayConnection,
    agentId: String,
    baseline: String?,
  ): Boolean =
    try {
      transport.request(connection, GatewayMethod.AgentsUpdate.rawValue, agentsUpdateModelParams(agentId, baseline), SETUP_REQUEST_TIMEOUT_MS)
      true
    } catch (_: GatewayRequestOutcomeUnknown) {
      runCatching { requestDetection(connection).configuredModel == baseline }.getOrDefault(false)
    } catch (_: Throwable) {
      false
    }

  private fun rollbackAwareMessage(
    failure: String,
    restored: Boolean,
  ): String =
    if (restored) {
      failure
    } else {
      "$failure ${nativeString("The previous default model could not be restored automatically.")}"
    }

  private fun startActivation(
    label: String,
    activation: AiSetupActivation,
  ) {
    clearRequestLocalSecret()
    val connection = transport.capture() ?: return
    val sessionId = newSessionId()
    startWizard(
      pending =
        PendingWizard(
          label = label,
          purpose = AiSetupWizardPurpose.Activation,
          sessionId = sessionId,
          gatewayStableId = connection.stableId,
          targetModelRef = activation.modelRef,
        ),
      launch =
        GatewayWizardLaunch(
          ownerId = OWNER_ID,
          sessionId = sessionId,
          method = GatewayMethod.OpenclawSetupActivateStart,
          params =
            aiSetupActivateStartParams(
              sessionId = sessionId,
              activation = activation,
              agentId = connection.defaultAgentId,
              workspace = detection?.workspace,
              nativeSessionCatalogsEnabled = false,
            ),
        ),
    )
  }

  private fun startWizard(
    pending: PendingWizard,
    launch: GatewayWizardLaunch,
  ) {
    refreshAfterWizardCancellation = false
    val checkpoint =
      AiSetupSessionCheckpoint(
        gatewayStableId = pending.gatewayStableId,
        sessionId = pending.sessionId,
        label = pending.label,
        purpose = pending.purpose,
        providerId = pending.providerId,
        targetModelRef = pending.targetModelRef,
      )
    if (!sessionStore.save(checkpoint)) {
      mutableState.value = AiSetupState.Failed(nativeString("Could not preserve this setup session. Try again."))
      return
    }
    pendingWizard = pending
    lastWizardTerminal = null
    wizard.start(launch)
  }

  private fun verify() {
    val connection = usableConnection() ?: return
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = AiSetupState.Working(AiSetupWorkingStage.Verifying)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val result = requestVerification(connection)
        publish(connection, sequence) {
          when (result) {
            is AiSetupVerification.Ready -> readyState(result)
            is AiSetupVerification.Failed -> AiSetupState.Failed(result.error)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        publish(connection, sequence) { AiSetupState.Failed(nativeString("AI verification did not complete.")) }
      }
    }
  }

  private suspend fun requestDetection(connection: AiGatewayConnection): AiSetupDetection =
    parseAiSetupDetection(
      transport.request(connection, GatewayMethod.OpenclawSetupDetect.rawValue, aiSetupDetectParams(connection.defaultAgentId), SETUP_REQUEST_TIMEOUT_MS),
      json,
    ) ?: error("Malformed AI setup detection")

  private suspend fun requestVerification(connection: AiGatewayConnection): AiSetupVerification =
    parseAiSetupVerification(
      transport.request(connection, GatewayMethod.OpenclawSetupVerify.rawValue, aiSetupVerifyParams(connection.defaultAgentId), SETUP_REQUEST_TIMEOUT_MS),
      json,
    ) ?: error("Malformed setup verification")

  private fun readyState(result: AiSetupVerification.Ready): AiSetupState.Ready {
    detection = detection?.copy(configuredModel = result.modelRef, setupComplete = true)
    onReady()
    return AiSetupState.Ready(result.modelRef, result.latencyMs)
  }

  private fun onWizardState(wizardState: GatewayWizardState) {
    val pending = pendingWizard ?: return
    if (wizardState.ownerIdOrNull() != OWNER_ID) return
    when (wizardState) {
      is GatewayWizardState.Starting -> mutableState.value = AiSetupState.Working(AiSetupWorkingStage.Detecting)
      is GatewayWizardState.Active ->
        mutableState.value = AiSetupState.Wizard(pending.label, wizardState.step, wizardState.submitting, wizardState.waitingForBrowser)
      is GatewayWizardState.WaitingForConnection,
      is GatewayWizardState.Recovering,
      is GatewayWizardState.Cancelling,
      -> mutableState.value = AiSetupState.WizardUnknown(pending.label)
      is GatewayWizardState.Finished -> {
        if (lastWizardTerminal == wizardState) return
        lastWizardTerminal = wizardState
        sessionStore.clear(pending.sessionId)
        pendingWizard = null
        refreshAfterWizardCancellation = false
        wizard.clear(OWNER_ID)
        when (pending.purpose) {
          AiSetupWizardPurpose.Auth -> finishAuthentication(pending, wizardState.result)
          AiSetupWizardPurpose.Activation -> finishActivation(pending, wizardState.result)
        }
      }
      is GatewayWizardState.Cancelled -> {
        sessionStore.clear(pending.sessionId)
        pendingWizard = null
        wizard.clear(OWNER_ID)
        if (refreshAfterWizardCancellation) {
          refreshAfterWizardCancellation = false
          refresh()
        } else {
          returnToAccessChoices()
        }
      }
      is GatewayWizardState.Failed -> {
        sessionStore.clear(pending.sessionId)
        pendingWizard = null
        refreshAfterWizardCancellation = false
        wizard.clear(OWNER_ID)
        mutableState.value = AiSetupState.Failed(wizardState.message)
      }
      GatewayWizardState.Idle -> Unit
    }
  }

  private fun finishAuthentication(
    pending: PendingWizard,
    result: GatewayWizardResult,
  ) {
    val activation = result.modelActivation
    if (activation == null) {
      mutableState.value = AiSetupState.Failed(nativeString("OpenClaw completed sign-in without activating a model."))
      return
    }
    val activatedProvider = activation.modelRef.substringBefore('/').takeIf(String::isNotBlank) ?: pending.providerId
    if (activation.gatewayRestartRequired) {
      postRestart = PostRestart.LoadAuthenticatedModels(pending.label, activatedProvider, activation.modelRef)
      requestRestart(pending.label, activation.modelRef)
    } else {
      loadModelChoices(
        label = pending.label,
        providerId = activatedProvider,
        currentModelRef = activation.modelRef,
        purpose = ModelChoicePurpose.Authenticated(activation.modelRef),
        includeModelsAwaitingKey = false,
      )
    }
  }

  private fun finishActivation(
    pending: PendingWizard,
    result: GatewayWizardResult,
  ) {
    val activation = result.modelActivation
    if (activation == null) {
      mutableState.value = AiSetupState.Failed(nativeString("OpenClaw completed setup without activating a model."))
      return
    }
    if (pending.targetModelRef != null && activation.modelRef != pending.targetModelRef) {
      mutableState.value = AiSetupState.Failed(nativeString("OpenClaw activated a different model."))
      return
    }
    if (activation.gatewayRestartRequired) {
      postRestart = PostRestart.Verify
      requestRestart(pending.label, activation.modelRef)
    } else {
      verify()
    }
  }

  private fun requestRestart(
    label: String,
    modelRef: String,
  ) {
    pendingRestartLabel = label
    lastRestartTerminal = null
    restart.request(OWNER_ID, "Activate $modelRef")
    mutableState.value = AiSetupState.Restarting(label, restart.state.value)
  }

  private fun onRestartState(restartState: GatewayRestartState) {
    if (restartState.ownerIdOrNull() != OWNER_ID) return
    val label = pendingRestartLabel ?: return
    when (restartState) {
      is GatewayRestartState.Ready -> {
        if (lastRestartTerminal == restartState) return
        lastRestartTerminal = restartState
        pendingRestartLabel = null
        restart.consume(OWNER_ID)
        when (val continuation = postRestart.also { postRestart = null }) {
          is PostRestart.LoadAuthenticatedModels ->
            loadModelChoices(
              label = continuation.label,
              providerId = continuation.providerId,
              currentModelRef = continuation.baseline,
              purpose = ModelChoicePurpose.Authenticated(continuation.baseline),
              includeModelsAwaitingKey = false,
            )
          PostRestart.Verify, null -> verify()
        }
      }
      is GatewayRestartState.Failed -> {
        pendingRestartLabel = null
        postRestart = null
        restart.consume(OWNER_ID)
        mutableState.value = AiSetupState.Failed(restartState.message)
      }
      GatewayRestartState.Idle -> Unit
      else -> mutableState.value = AiSetupState.Restarting(label, restartState)
    }
  }

  private fun reconcile() {
    when {
      pendingRestartLabel != null -> restart.reconcile()
      pendingWizard != null -> wizard.reconcile()
      else -> refresh()
    }
  }

  private fun cancel() {
    clearRequestLocalSecret()
    if (pendingWizard != null) {
      wizard.cancel()
      return
    }
    when (val current = mutableState.value) {
      is AiSetupState.Working ->
        if (current.stage == AiSetupWorkingStage.Detecting || current.stage == AiSetupWorkingStage.LoadingModels) {
          operationSequence.incrementAndGet()
          returnToAccessChoices()
        }
      is AiSetupState.Choices,
      is AiSetupState.SignInChoices,
      is AiSetupState.ApiKeyChoices,
      is AiSetupState.ApiKeyEntry,
      is AiSetupState.ModelChoices,
      is AiSetupState.Failed,
      -> returnToAccessChoices()
      else -> Unit
    }
  }

  /** Leaves the settings editor without treating its transient navigation state as AI health. */
  private fun dismiss() {
    clearRequestLocalSecret()
    if (pendingWizard != null) {
      refreshAfterWizardCancellation = true
      wizard.cancel()
      return
    }
    when (val current = mutableState.value) {
      is AiSetupState.Working ->
        if (current.stage == AiSetupWorkingStage.Detecting || current.stage == AiSetupWorkingStage.LoadingModels) {
          operationSequence.incrementAndGet()
          refresh()
        }
      is AiSetupState.Choices,
      is AiSetupState.SignInChoices,
      is AiSetupState.ApiKeyChoices,
      is AiSetupState.ApiKeyEntry,
      is AiSetupState.ModelChoices,
      is AiSetupState.Failed,
      -> refresh()
      else -> Unit
    }
  }

  private fun returnToAccessChoices() {
    modelChoicePurpose = null
    detection?.let { mutableState.value = AiSetupState.Choices(it) }
  }

  private fun clearRequestLocalSecret() {
    pendingApiKey = null
  }

  private fun usableConnection(): AiGatewayConnection? {
    val connection = transport.capture()
    if (connection == null) {
      mutableState.value = AiSetupState.Disconnected
      return null
    }
    val missing = connection.missingRequiredAiMethods()
    if (missing.isNotEmpty()) {
      mutableState.value = AiSetupState.Incompatible(missing)
      return null
    }
    if (!connection.adminScope) {
      mutableState.value = AiSetupState.Failed(nativeString("Administrator access is required to configure AI."))
      return null
    }
    return connection
  }

  private fun publish(
    connection: AiGatewayConnection,
    sequence: Long,
    state: () -> AiSetupState,
  ) {
    if (operationSequence.get() != sequence) return
    transport.publish(connection) {
      if (operationSequence.get() == sequence) mutableState.value = state()
    }
  }

  private companion object {
    const val OWNER_ID = "ai-setup"
    const val SETUP_REQUEST_TIMEOUT_MS = 60_000L
  }
}

private fun AiModel.providerQualifiedRef(): String {
  val prefix = "${provider.trim()}/"
  return if (id.startsWith(prefix)) id else "$prefix$id"
}

private fun GatewayWizardState.ownerIdOrNull(): String? =
  when (this) {
    GatewayWizardState.Idle -> null
    is GatewayWizardState.Starting -> ownerId
    is GatewayWizardState.Active -> ownerId
    is GatewayWizardState.WaitingForConnection -> ownerId
    is GatewayWizardState.Recovering -> ownerId
    is GatewayWizardState.Cancelling -> ownerId
    is GatewayWizardState.Finished -> ownerId
    is GatewayWizardState.Cancelled -> ownerId
    is GatewayWizardState.Failed -> ownerId
  }

private fun GatewayRestartState.ownerIdOrNull(): String? =
  when (this) {
    GatewayRestartState.Idle -> null
    is GatewayRestartState.Checking -> ownerId
    is GatewayRestartState.WaitingForSafeRestart -> ownerId
    is GatewayRestartState.Restarting -> ownerId
    is GatewayRestartState.Reconnecting -> ownerId
    is GatewayRestartState.UnknownOutcome -> ownerId
    is GatewayRestartState.Ready -> ownerId
    is GatewayRestartState.Failed -> ownerId
  }
