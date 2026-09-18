package ai.openclaw.app.runtime

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.ai.GatewayRestartState
import ai.openclaw.app.ai.isPlannedInterruption
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilityFeature
import ai.openclaw.app.supervisor.SupervisorCommand
import ai.openclaw.app.supervisor.SupervisorControlController
import ai.openclaw.app.supervisor.SupervisorControlState
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.uniqueActiveCapabilityConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/** One application-level owner for cold start, live recovery, and environment presentation. */
internal class RuntimeCoordinator(
  scope: CoroutineScope,
  private val supervisor: SupervisorControlController,
  private val connection: StateFlow<GatewayConnectionDisplay>,
  private val version: StateFlow<String?>,
  private val deviceEligibility: DeviceEligibilityFeature,
  private val reconnect: () -> Unit,
  private val plannedRestart: StateFlow<GatewayRestartState> = MutableStateFlow(GatewayRestartState.Idle),
  private val acknowledgement: RuntimeAcknowledgementStore = InMemoryRuntimeAcknowledgementStore(),
  private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
  private val acknowledgedGeneration = MutableStateFlow(acknowledgement.load())
  private val sessionEntered = MutableStateFlow(false)
  private var unhealthySinceEpochSeconds: Long? = null
  private val autoEnsureStatusKeys = mutableSetOf<Pair<String, Long>>()

  private data class RuntimeInputs(
    val supervisor: SupervisorControlState,
    val monitoring: Boolean,
    val connection: GatewayConnectionDisplay,
    val version: String?,
    val deviceEligibility: DeviceEligibility,
  )

  private data class RuntimeSession(
    val acknowledgedGeneration: RuntimeGeneration?,
    val entered: Boolean,
    val nowEpochSeconds: Long,
    val plannedGatewayRestart: Boolean,
  )

  val state: StateFlow<RuntimeState> =
    combine(
      supervisor.state,
      supervisor.isMonitoring,
      connection,
      version,
      deviceEligibility.state,
    ) { supervisor, monitoring, connection, version, eligibility ->
      RuntimeInputs(supervisor, monitoring, connection, version, eligibility.eligibility)
    }.combine(
      combine(
        acknowledgedGeneration,
        sessionEntered,
        flow {
          while (currentCoroutineContext().isActive) {
            emit(nowEpochSeconds())
            delay(1_000)
          }
        },
        plannedRestart,
      ) { generation, entered, now, restart ->
        RuntimeSession(generation, entered, now, restart.isPlannedInterruption())
      },
    ) { input, session ->
      val now = session.nowEpochSeconds
      val currentStatus =
        when (val current = input.supervisor) {
          is SupervisorControlState.Status -> current.value
          is SupervisorControlState.Waiting -> current.latestStatus
          else -> null
        }
      val healthyCandidate =
        operationalGatewayGeneration(
          currentStatus,
          input.connection.isConnected,
          session.acknowledgedGeneration,
        ) != null
      if (healthyCandidate) {
        unhealthySinceEpochSeconds = null
      } else if (unhealthySinceEpochSeconds == null) {
        unhealthySinceEpochSeconds = now
      }
      resolveRuntimeState(
        supervisor = input.supervisor,
        monitoring = input.monitoring,
        gateway = input.connection,
        gatewayVersion = input.version,
        acknowledgedGeneration = session.acknowledgedGeneration,
        sessionEntered = session.entered,
        nowEpochSeconds = now,
        unhealthyDurationSeconds = now - (unhealthySinceEpochSeconds ?: now),
        deviceEligibility = input.deviceEligibility,
        plannedGatewayRestart = session.plannedGatewayRestart,
      )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), currentState())

  val feature = RuntimeFeature(state = state, observe = ::observe, perform = ::perform, confirmReady = ::confirmReady)

  /** Active while a product surface is visible; process lifetime remains owned elsewhere. */
  suspend fun observe() {
    deviceEligibility.refresh()
    while (currentCoroutineContext().isActive) {
      if (deviceEligibility.state.value.eligibility != DeviceEligibility.Ready) {
        delay(5_000)
        continue
      }
      val status = currentSupervisorStatus()
      val statusKey = status?.let { it.supervisorBootId to it.eventSequence }
      val ensured =
        status != null &&
          !supervisor.isMonitoring.value &&
          status.stage in AUTO_ENSURE_STAGES &&
          statusKey !in autoEnsureStatusKeys &&
          supervisor.recover(SupervisorCommand.EnsureGateway)
      if (ensured) {
        autoEnsureStatusKeys.add(checkNotNull(statusKey))
      } else if (supervisor.state.value.canAutoProbe()) {
        supervisor.observe()
      }
      delay(5_000)
    }
  }

  fun perform(action: RuntimeAction) {
    val current = currentState()
    if (action !in current.actions) return
    when (action) {
      RuntimeAction.Refresh -> supervisor.observe()
      RuntimeAction.Reconnect -> reconnect()
      RuntimeAction.EnsureGateway -> supervisor.recover(SupervisorCommand.EnsureGateway)
      RuntimeAction.RequestGatewayPairing -> supervisor.recover(SupervisorCommand.RequestGatewayPairing)
      RuntimeAction.RepairCapabilityPlan ->
        current.setup
          ?.selectedCapabilities
          ?.toSet()
          ?.let(supervisor::applyCapabilities)
      RuntimeAction.RetryCapabilityPlan ->
        current.setup?.planId?.let { supervisor.recover(SupervisorCommand.retryCapabilities(it)) }
      RuntimeAction.SkipOptionalCapability -> {
        val setup = current.setup ?: return
        val planId = setup.planId ?: return
        val capability =
          setup.currentComponent?.let { uniqueActiveCapabilityConsumer(it, setup.selectedCapabilities) } ?: return
        supervisor.recover(SupervisorCommand.skipCapability(planId, capability))
      }
      RuntimeAction.RefreshDeviceEligibility -> deviceEligibility.refresh()
      RuntimeAction.OpenDeviceInfo,
      RuntimeAction.OpenDeveloperSettings,
      RuntimeAction.OpenSystemUpdate,
      RuntimeAction.OpenSystemTerminal,
      -> Unit
    }
  }

  fun confirmReady() {
    val current = state.value
    if (current.gate.phase !in setOf(RuntimeGatePhase.ReadyToEnter, RuntimeGatePhase.ReadyToResume)) return
    val generation = current.gate.generation ?: return
    acknowledgement.save(generation)
    acknowledgedGeneration.value = generation
    sessionEntered.value = true
  }

  private fun currentSupervisorStatus() =
    when (val current = supervisor.state.value) {
      is SupervisorControlState.Status -> current.value
      is SupervisorControlState.Waiting -> current.latestStatus
      else -> null
    }

  private fun currentState() =
    resolveRuntimeState(
      supervisor = supervisor.state.value,
      monitoring = supervisor.isMonitoring.value,
      gateway = connection.value,
      gatewayVersion = version.value,
      acknowledgedGeneration = acknowledgedGeneration.value,
      sessionEntered = sessionEntered.value,
      nowEpochSeconds = nowEpochSeconds(),
      unhealthyDurationSeconds = 0,
      deviceEligibility = deviceEligibility.state.value.eligibility,
      plannedGatewayRestart = plannedRestart.value.isPlannedInterruption(),
    )
}

private val AUTO_ENSURE_STAGES =
  setOf(SupervisorStatusStage.CapabilitiesReady, SupervisorStatusStage.GatewayNotStarted)

private fun SupervisorControlState.canAutoProbe(): Boolean = this == SupervisorControlState.Stopped || this is SupervisorControlState.Status

internal class RuntimeFeature(
  val state: StateFlow<RuntimeState>,
  val observe: suspend () -> Unit,
  val perform: (RuntimeAction) -> Unit,
  val confirmReady: () -> Unit,
)

internal interface RuntimeAcknowledgementStore {
  fun load(): RuntimeGeneration?

  fun save(generation: RuntimeGeneration)
}

private class InMemoryRuntimeAcknowledgementStore : RuntimeAcknowledgementStore {
  private var generation: RuntimeGeneration? = null

  override fun load(): RuntimeGeneration? = generation

  override fun save(generation: RuntimeGeneration) {
    this.generation = generation
  }
}
