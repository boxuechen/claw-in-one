package ai.openclaw.app.supervisor

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

private const val SUPERVISOR_LOG_TAG = "ClawSupervisor"
private const val SUPERVISOR_POLL_INTERVAL_MILLIS = 500L
private const val SUPERVISOR_PROBE_TIMEOUT_SECONDS = 30L
private const val SUPERVISOR_INSTALL_TIMEOUT_SECONDS = 2 * 60 * 60L
private const val SUPERVISOR_GATEWAY_TIMEOUT_SECONDS = 12 * 60L

internal sealed interface SupervisorControlError {
  data object CommandCheckpointFailed : SupervisorControlError

  data object CommandWriteFailed : SupervisorControlError

  data object StatusMissing : SupervisorControlError

  data object StatusTooLarge : SupervisorControlError

  data object StatusInvalidEncoding : SupervisorControlError

  data class StatusVerificationFailed(
    val reason: SupervisorControlVerificationError,
  ) : SupervisorControlError

  data object StatusSequenceViolation : SupervisorControlError

  data object StatusCheckpointFailed : SupervisorControlError

  data object StatusReadFailed : SupervisorControlError

  data object TimedOut : SupervisorControlError
}

internal sealed interface SupervisorControlState {
  data object Stopped : SupervisorControlState

  data object Missing : SupervisorControlState

  data class Waiting(
    val command: SupervisorCommand,
    val sequence: Long,
    val latestStatus: SupervisorStatus?,
  ) : SupervisorControlState

  data class Status(
    val value: SupervisorStatus,
  ) : SupervisorControlState

  data class Failed(
    val error: SupervisorControlError,
  ) : SupervisorControlState
}

internal class SupervisorControlController(
  private val repository: SupervisorControlRepository,
  private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
  private val sleep: (Long) -> Unit = Thread::sleep,
) {
  private val generation = AtomicLong()
  private val executor =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "claw-in-one-supervisor-control").apply { isDaemon = true }
    }
  private var operation: Future<*>? = null
  private val _state = MutableStateFlow<SupervisorControlState>(SupervisorControlState.Stopped)
  val state: StateFlow<SupervisorControlState> = _state.asStateFlow()
  private val _isMonitoring = MutableStateFlow(false)
  val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

  /** Inspect/rejoin the current operation without cancelling an active monitor. */
  @Synchronized
  fun observe() {
    if (_isMonitoring.value) return
    resume()
  }

  /** Environment recovery shares this owner and cannot replace in-flight setup work. */
  @Synchronized
  fun recover(command: SupervisorCommand): Boolean {
    require(
      command.operation in
        setOf(
          SupervisorOperation.EnsureGateway,
          SupervisorOperation.RequestGatewayPairing,
          SupervisorOperation.RetryCapabilities,
          SupervisorOperation.SkipCapability,
        ),
    )
    if (_isMonitoring.value || repository.load()?.pendingCommandSequence != null) return false
    startOperation { _, activeGeneration -> issueAndMonitor(command, activeGeneration) }
    return true
  }

  fun resume() {
    startOperation { session, activeGeneration ->
      val pending = session.pendingCommandSequence
      if (pending != null && session.pendingCommand?.operation != SupervisorOperation.Probe) {
        val command = checkNotNull(session.pendingCommand)
        monitor(command, pending, activeGeneration)
      } else {
        issueAndMonitor(SupervisorCommand.Probe, activeGeneration)
      }
    }
  }

  fun applyCapabilities(capabilities: Set<DevelopmentCapability>) {
    val planId = ByteArray(16).also(SecureRandom()::nextBytes).toHex()
    startOperation { _, activeGeneration ->
      issueAndMonitor(
        SupervisorCommand.applyCapabilities(planId, capabilities),
        activeGeneration,
      )
    }
  }

  fun retryCapabilities(planId: String) {
    startOperation { _, activeGeneration ->
      issueAndMonitor(SupervisorCommand.retryCapabilities(planId), activeGeneration)
    }
  }

  fun skipCapability(
    planId: String,
    capability: DevelopmentCapability,
  ) {
    startOperation { _, activeGeneration ->
      issueAndMonitor(SupervisorCommand.skipCapability(planId, capability), activeGeneration)
    }
  }

  fun ensureGateway() {
    startOperation { _, activeGeneration ->
      issueAndMonitor(SupervisorCommand.EnsureGateway, activeGeneration)
    }
  }

  fun requestGatewayPairing() {
    startOperation { _, activeGeneration ->
      issueAndMonitor(SupervisorCommand.RequestGatewayPairing, activeGeneration)
    }
  }

  @Synchronized
  fun stop() {
    generation.incrementAndGet()
    operation?.cancel(true)
    operation = null
    _isMonitoring.value = false
    _state.value = SupervisorControlState.Stopped
  }

  @Synchronized
  private fun startOperation(
    block: SupervisorControlController.(SupervisorControlSession, Long) -> SupervisorControlState,
  ) {
    val activeGeneration = generation.incrementAndGet()
    operation?.cancel(true)
    val session = repository.load()
    if (session == null) {
      _state.value = SupervisorControlState.Missing
      operation = null
      _isMonitoring.value = false
      return
    }
    _isMonitoring.value = true
    operation =
      executor.submit {
        val finalState =
          runCatching { block(session, activeGeneration) }
            .getOrElse { error ->
              Log.e(SUPERVISOR_LOG_TAG, "Supervisor control operation failed", error)
              SupervisorControlState.Failed(SupervisorControlError.StatusReadFailed)
            }
        synchronized(this) {
          if (generation.get() == activeGeneration) {
            _state.value = finalState
            _isMonitoring.value = false
          }
        }
      }
  }

  private fun issueAndMonitor(
    command: SupervisorCommand,
    activeGeneration: Long,
  ): SupervisorControlState =
    when (val written = repository.writeCommand(command, nowEpochSeconds())) {
      is SupervisorCommandWriteResult.Written ->
        monitor(command, written.sequence, activeGeneration)
      SupervisorCommandWriteResult.MissingSession -> SupervisorControlState.Missing
      SupervisorCommandWriteResult.CheckpointFailed ->
        SupervisorControlState.Failed(SupervisorControlError.CommandCheckpointFailed)
      SupervisorCommandWriteResult.WriteFailed ->
        SupervisorControlState.Failed(SupervisorControlError.CommandWriteFailed)
    }

  private fun monitor(
    command: SupervisorCommand,
    sequence: Long,
    activeGeneration: Long,
  ): SupervisorControlState {
    val startedAt = nowEpochSeconds()
    var latestStatus = repository.load()?.lastStatus()
    publish(SupervisorControlState.Waiting(command, sequence, latestStatus), activeGeneration)
    while (!Thread.currentThread().isInterrupted) {
      when (val read = repository.readStatus(nowEpochSeconds())) {
        is SupervisorStatusReadResult.Updated -> latestStatus = read.status
        is SupervisorStatusReadResult.Unchanged -> latestStatus = read.status
        is SupervisorStatusReadResult.Stale -> latestStatus = read.lastVerifiedStatus
        SupervisorStatusReadResult.Missing ->
          return SupervisorControlState.Failed(SupervisorControlError.StatusMissing)
        SupervisorStatusReadResult.MissingSession -> return SupervisorControlState.Missing
        SupervisorStatusReadResult.TooLarge ->
          return SupervisorControlState.Failed(SupervisorControlError.StatusTooLarge)
        SupervisorStatusReadResult.InvalidEncoding ->
          return SupervisorControlState.Failed(SupervisorControlError.StatusInvalidEncoding)
        is SupervisorStatusReadResult.VerificationFailed ->
          return SupervisorControlState.Failed(
            SupervisorControlError.StatusVerificationFailed(read.error),
          )
        SupervisorStatusReadResult.SequenceViolation ->
          return SupervisorControlState.Failed(SupervisorControlError.StatusSequenceViolation)
        SupervisorStatusReadResult.CheckpointFailed ->
          return SupervisorControlState.Failed(SupervisorControlError.StatusCheckpointFailed)
        SupervisorStatusReadResult.Failed ->
          return SupervisorControlState.Failed(SupervisorControlError.StatusReadFailed)
      }
      val status = latestStatus
      if (status != null) {
        publish(SupervisorControlState.Status(status), activeGeneration)
        if (status.commandSequence >= sequence && command.isTerminalAt(status.stage)) {
          return SupervisorControlState.Status(status)
        }
      }
      val timeout =
        when (command.operation) {
          SupervisorOperation.Probe -> SUPERVISOR_PROBE_TIMEOUT_SECONDS
          SupervisorOperation.ApplyCapabilities,
          SupervisorOperation.RetryCapabilities,
          SupervisorOperation.SkipCapability,
          -> SUPERVISOR_INSTALL_TIMEOUT_SECONDS
          SupervisorOperation.EnsureGateway,
          SupervisorOperation.RequestGatewayPairing,
          -> SUPERVISOR_GATEWAY_TIMEOUT_SECONDS
        }
      if (nowEpochSeconds() - startedAt > timeout) {
        return SupervisorControlState.Failed(SupervisorControlError.TimedOut)
      }
      try {
        sleep(SUPERVISOR_POLL_INTERVAL_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    return SupervisorControlState.Stopped
  }

  private fun publish(
    state: SupervisorControlState,
    activeGeneration: Long,
  ) {
    if (generation.get() == activeGeneration) _state.value = state
  }
}

internal fun SupervisorCommand.isTerminalAt(stage: SupervisorStatusStage): Boolean =
  when (operation) {
    SupervisorOperation.Probe ->
      stage in
        setOf(
          SupervisorStatusStage.CapabilitiesRequired,
          SupervisorStatusStage.CapabilitiesReady,
          SupervisorStatusStage.CapabilityFailed,
          SupervisorStatusStage.GatewayNotStarted,
          SupervisorStatusStage.GatewayConfiguring,
          SupervisorStatusStage.GatewayStarting,
          SupervisorStatusStage.GatewayHealthy,
          SupervisorStatusStage.GatewayReady,
          SupervisorStatusStage.GatewayPairingReady,
          SupervisorStatusStage.GatewayPairingFailed,
          SupervisorStatusStage.GatewayFailed,
        )
    SupervisorOperation.ApplyCapabilities,
    SupervisorOperation.RetryCapabilities,
    SupervisorOperation.SkipCapability,
    ->
      stage in
        setOf(
          SupervisorStatusStage.CapabilitiesReady,
          SupervisorStatusStage.GatewayReady,
          SupervisorStatusStage.CapabilityFailed,
          SupervisorStatusStage.CapabilityPlanRejected,
          SupervisorStatusStage.GatewayFailed,
        )
    SupervisorOperation.EnsureGateway ->
      stage == SupervisorStatusStage.GatewayReady || stage == SupervisorStatusStage.GatewayFailed
    SupervisorOperation.RequestGatewayPairing ->
      stage == SupervisorStatusStage.GatewayPairingReady ||
        stage == SupervisorStatusStage.GatewayPairingFailed
  }

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
