package ai.openclaw.app.onboarding

import ai.openclaw.app.ai.AiSetupState
import ai.openclaw.app.bootstrap.BootstrapDeliveryController
import ai.openclaw.app.bootstrap.BootstrapDeliveryState
import ai.openclaw.app.bootstrap.BootstrapHandoffController
import ai.openclaw.app.bootstrap.BootstrapHandoffService
import ai.openclaw.app.bootstrap.BootstrapHandoffState
import ai.openclaw.app.eligibility.AndroidDeviceEligibilityController
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilitySnapshot
import ai.openclaw.app.supervisor.SupervisorControlController
import ai.openclaw.app.supervisor.SupervisorControlRepository
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.supervisor.SupervisorStatusStage
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class FirstRunCoordinator(
  context: Context,
  private val scope: CoroutineScope,
  private val deviceEligibility: AndroidDeviceEligibilityController,
  private val bootstrapDelivery: BootstrapDeliveryController,
  private val bootstrapHandoff: BootstrapHandoffController,
  private val supervisorRepository: SupervisorControlRepository,
  private val supervisorControl: SupervisorControlController,
  private val gatewayConnected: StateFlow<Boolean>,
  aiSetupState: StateFlow<AiSetupState>,
  onboardingReceipt: StateFlow<OnboardingReceipt?>,
  private val connectGateway: suspend (String) -> FirstRunGatewayConnectResult,
  private val completeOnboarding: (OnboardingReceipt) -> Unit,
) {
  private val appContext = context.applicationContext
  private val supervisorPresent = MutableStateFlow(supervisorRepository.load() != null)
  private val gatewayFailure = MutableStateFlow<FirstRunFailure?>(null)
  private var gatewayStartRequested = false
  private var consumedSetupCode: String? = null
  private var clearedSetupCode: String? = null
  private val localState =
    combine(
      deviceEligibility.state,
      bootstrapDelivery.state,
      bootstrapHandoff.state,
      supervisorPresent,
      supervisorControl.state,
    ) { eligibility, delivery, handoff, present, supervisor ->
      LocalFirstRunState(eligibility, delivery, handoff, present, supervisor)
    }
  val state: StateFlow<FirstRunState> =
    combine(
      localState,
      combine(
        gatewayConnected,
        aiSetupState,
        onboardingReceipt,
        gatewayFailure,
      ) { connected, aiSetup, receipt, pairingFailure ->
        RemoteFirstRunState(connected, aiSetup, receipt, pairingFailure)
      },
    ) { local, remote ->
      resolveFirstRunState(
        FirstRunSnapshot(
          onboardingReceipt = remote.receipt,
          deviceEligibility = local.deviceEligibility.eligibility,
          bootstrapDelivery = local.delivery,
          bootstrapHandoff = local.handoff,
          supervisorPresent = local.supervisorPresent,
          supervisorId = supervisorRepository.load()?.supervisorId,
          supervisor = local.supervisor,
          gatewayConnected = remote.gatewayConnected,
          aiSetup = remote.aiSetup,
          gatewayFailure = remote.gatewayFailure,
        ),
      )
    }.stateIn(
      scope,
      SharingStarted.Eagerly,
      initialState(
        onboardingReceipt = onboardingReceipt.value,
        gatewayConnected = gatewayConnected.value,
        aiSetup = aiSetupState.value,
      ),
    )

  init {
    scope.launch {
      bootstrapHandoff.state.collect { handoff ->
        if (handoff is BootstrapHandoffState.Ready) {
          supervisorPresent.value = supervisorRepository.load() != null
          supervisorControl.resume()
        }
      }
    }
    scope.launch {
      combine(
        deviceEligibility.state,
        bootstrapDelivery.state,
        bootstrapHandoff.state,
        supervisorPresent,
        onboardingReceipt,
      ) { eligibility, delivery, handoff, present, receipt ->
        receipt == null &&
          eligibility.eligibility == DeviceEligibility.Ready &&
          !present &&
          delivery is BootstrapDeliveryState.Ready &&
          handoff == BootstrapHandoffState.Stopped
      }.distinctUntilChanged()
        .filter { it }
        .collect { BootstrapHandoffService.start(appContext) }
    }
    scope.launch {
      combine(supervisorControl.state, gatewayConnected) { supervisor, connected ->
        supervisor to connected
      }.collect { (supervisor, connected) ->
        val status = (supervisor as? SupervisorControlState.Status)?.value ?: return@collect
        if (status.stage != SupervisorStatusStage.GatewayPairingReady) return@collect
        val setupCode = status.setupCode
        if (setupCode == null) {
          gatewayFailure.value = FirstRunFailure.GatewayPairing(exitCode = null)
        } else if (!connected && setupCode != consumedSetupCode) {
          when (connectGateway(setupCode)) {
            FirstRunGatewayConnectResult.Started -> {
              consumedSetupCode = setupCode
              gatewayFailure.value = null
            }
            FirstRunGatewayConnectResult.InvalidSetup ->
              gatewayFailure.value = FirstRunFailure.GatewayPairing(exitCode = null)
            FirstRunGatewayConnectResult.PortForwardingRequired ->
              gatewayFailure.value = FirstRunFailure.GatewayPortForwarding
          }
        } else if (connected && setupCode != clearedSetupCode) {
          clearedSetupCode = setupCode
          supervisorControl.resume()
        }
      }
    }
    scope.launch {
      state.collect { current ->
        when {
          current is FirstRunState.Finalizing -> completeOnboarding(OnboardingReceipt.create(current.evidence))
          current is FirstRunState.EnvironmentSetup &&
            current.step is EnvironmentSetupStep.ConnectingGateway &&
            !current.step.starting &&
            !gatewayStartRequested -> {
            gatewayStartRequested = true
            gatewayFailure.value = null
            supervisorControl.requestGatewayPairing()
          }
        }
      }
    }
  }

  fun resume() {
    deviceEligibility.refresh()
    if (state.value == FirstRunState.Completed) return
    if (deviceEligibility.state.value.eligibility != DeviceEligibility.Ready) return
    supervisorPresent.value = supervisorRepository.load() != null
    if (supervisorPresent.value) {
      val environment = state.value as? FirstRunState.EnvironmentSetup
      if (environment?.step is EnvironmentSetupStep.ConnectingGateway) gatewayStartRequested = false
      supervisorControl.resume()
    } else {
      scope.launch { bootstrapDelivery.prepare() }
    }
  }

  fun installEnvironment() {
    if (
      (state.value as? FirstRunState.EnvironmentSetup)?.step ==
      EnvironmentSetupStep.InstallReady
    ) {
      supervisorControl.applyCapabilities(emptySet())
    }
  }

  fun readyBootstrapCommand(): String? = (bootstrapDelivery.state.value as? BootstrapDeliveryState.Ready)?.command

  fun copyBootstrapCommand(): Boolean = bootstrapDelivery.copyReadyCommand()

  fun retry() {
    when (val current = state.value) {
      is FirstRunState.Failed ->
        when (current.failure) {
          is FirstRunFailure.BootstrapDelivery -> scope.launch { bootstrapDelivery.resetAndPrepare() }
          is FirstRunFailure.BootstrapHandoff ->
            scope.launch {
              retryBootstrapHandoff(
                republish = bootstrapDelivery::resetAndPrepare,
                resetMonitor = bootstrapHandoff::reset,
              )
            }
          is FirstRunFailure.Supervisor -> supervisorControl.resume()
          is FirstRunFailure.Setup -> supervisorControl.retryCapabilities(current.failure.planId)
          is FirstRunFailure.SetupPlanRejected -> supervisorControl.resume()
          is FirstRunFailure.GatewayPairing -> {
            gatewayFailure.value = null
            consumedSetupCode = null
            clearedSetupCode = null
            gatewayStartRequested = true
            supervisorControl.requestGatewayPairing()
          }
          FirstRunFailure.GatewayPortForwarding -> {
            gatewayFailure.value = null
            consumedSetupCode = null
            clearedSetupCode = null
            gatewayStartRequested = true
            supervisorControl.requestGatewayPairing()
          }
        }
      else -> resume()
    }
  }

  private fun initialState(
    onboardingReceipt: OnboardingReceipt?,
    gatewayConnected: Boolean,
    aiSetup: AiSetupState,
  ): FirstRunState =
    resolveFirstRunState(
      FirstRunSnapshot(
        onboardingReceipt = onboardingReceipt,
        deviceEligibility = deviceEligibility.state.value.eligibility,
        bootstrapDelivery = bootstrapDelivery.state.value,
        bootstrapHandoff = bootstrapHandoff.state.value,
        supervisorPresent = supervisorPresent.value,
        supervisorId = supervisorRepository.load()?.supervisorId,
        supervisor = supervisorControl.state.value,
        gatewayConnected = gatewayConnected,
        aiSetup = aiSetup,
        gatewayFailure = gatewayFailure.value,
      ),
    )
}

internal suspend fun retryBootstrapHandoff(
  republish: suspend () -> Unit,
  resetMonitor: () -> Unit,
) {
  republish()
  resetMonitor()
}

private data class LocalFirstRunState(
  val deviceEligibility: DeviceEligibilitySnapshot,
  val delivery: BootstrapDeliveryState,
  val handoff: BootstrapHandoffState,
  val supervisorPresent: Boolean,
  val supervisor: SupervisorControlState,
)

private data class RemoteFirstRunState(
  val gatewayConnected: Boolean,
  val aiSetup: AiSetupState,
  val receipt: OnboardingReceipt?,
  val gatewayFailure: FirstRunFailure?,
)
