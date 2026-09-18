package ai.openclaw.app.bootstrap

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

private const val HANDOFF_LOG_TAG = "ClawBootstrapHandoff"
private const val HANDOFF_POLL_INTERVAL_MILLIS = 500L
private const val HANDOFF_TIMEOUT_SECONDS = 30 * 60L

internal sealed interface BootstrapHandoffError {
  data object MissingSession : BootstrapHandoffError

  data object EventsMissing : BootstrapHandoffError

  data object EventsUnreadable : BootstrapHandoffError

  data object EventsTooLarge : BootstrapHandoffError

  data object InvalidEncoding : BootstrapHandoffError

  data class VerificationFailed(
    val reason: BootstrapHandoffVerificationError,
  ) : BootstrapHandoffError

  data object SequenceViolation : BootstrapHandoffError

  data object StageViolation : BootstrapHandoffError

  data object CheckpointFailed : BootstrapHandoffError

  data object SupervisorActivationFailed : BootstrapHandoffError

  data object TimedOut : BootstrapHandoffError

  data class StageFailed(
    val stage: SupervisorProgress,
    val exitCode: Int,
  ) : BootstrapHandoffError
}

internal sealed interface BootstrapHandoffState {
  data object Stopped : BootstrapHandoffState

  data object Starting : BootstrapHandoffState

  data object Waiting : BootstrapHandoffState

  data class Progress(
    val stage: SupervisorProgress,
  ) : BootstrapHandoffState

  data class Ready(
    val requestId: String,
    val protocolVersion: Int,
  ) : BootstrapHandoffState

  data class Failed(
    val error: BootstrapHandoffError,
  ) : BootstrapHandoffState
}

internal class BootstrapHandoffController(
  private val repository: BootstrapHandoffRepository,
  private val activateSupervisor: (BootstrapHandoffSession) -> Boolean = { true },
  private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
  private val sleep: (Long) -> Unit = Thread::sleep,
) {
  private val generation = AtomicLong()
  private val executor =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "claw-in-one-bootstrap-handoff").apply { isDaemon = true }
    }
  private var monitorFuture: Future<*>? = null
  private val _state = MutableStateFlow<BootstrapHandoffState>(BootstrapHandoffState.Stopped)
  val state: StateFlow<BootstrapHandoffState> = _state.asStateFlow()

  @Synchronized
  fun start() {
    if (monitorFuture?.isDone == false) return
    val activeGeneration = generation.incrementAndGet()
    _state.value = BootstrapHandoffState.Starting
    monitorFuture =
      executor.submit {
        val finalState =
          runCatching {
            runBootstrapHandoffMonitor(
              repository = repository,
              activateSupervisor = activateSupervisor,
              nowEpochSeconds = nowEpochSeconds,
              sleep = sleep,
              publish = { state ->
                if (generation.get() == activeGeneration) _state.value = state
              },
            )
          }.getOrElse { error ->
            Log.e(HANDOFF_LOG_TAG, "Signed Bootstrap handoff monitor failed", error)
            BootstrapHandoffState.Failed(BootstrapHandoffError.EventsUnreadable)
          }
        if (finalState is BootstrapHandoffState.Failed) {
          Log.w(HANDOFF_LOG_TAG, "Signed Bootstrap handoff rejected: ${finalState.error}")
        }
        if (generation.get() == activeGeneration) _state.value = finalState
      }
  }

  @Synchronized
  fun reset() {
    generation.incrementAndGet()
    monitorFuture?.cancel(true)
    monitorFuture = null
    _state.value = BootstrapHandoffState.Stopped
  }
}

internal fun runBootstrapHandoffMonitor(
  repository: BootstrapHandoffRepository,
  activateSupervisor: (BootstrapHandoffSession) -> Boolean = { true },
  nowEpochSeconds: () -> Long,
  sleep: (Long) -> Unit,
  publish: (BootstrapHandoffState) -> Unit,
): BootstrapHandoffState {
  var session =
    repository.loadActive()
      ?: return BootstrapHandoffState.Failed(BootstrapHandoffError.MissingSession)
  publish(
    if (session.lastProgressOrdinal >= 0) {
      BootstrapHandoffState.Progress(SupervisorProgress.entries[session.lastProgressOrdinal])
    } else {
      BootstrapHandoffState.Waiting
    },
  )

  while (!Thread.currentThread().isInterrupted) {
    when (val read = repository.readEvents(session)) {
      BootstrapHandoffReadResult.Missing ->
        return BootstrapHandoffState.Failed(BootstrapHandoffError.EventsMissing)
      BootstrapHandoffReadResult.TooLarge ->
        return BootstrapHandoffState.Failed(BootstrapHandoffError.EventsTooLarge)
      BootstrapHandoffReadResult.InvalidEncoding ->
        return BootstrapHandoffState.Failed(BootstrapHandoffError.InvalidEncoding)
      BootstrapHandoffReadResult.Failed ->
        return BootstrapHandoffState.Failed(BootstrapHandoffError.EventsUnreadable)
      is BootstrapHandoffReadResult.Content -> {
        for (line in read.completeLines) {
          val verified =
            verifyBootstrapHandoffLine(
              line = line,
              expectedRequestId = session.requestId,
              secretHex = session.handoffSecretHex,
              nowEpochSeconds = nowEpochSeconds(),
            )
          val event =
            when (verified) {
              is BootstrapHandoffVerificationResult.Accepted -> verified.event
              is BootstrapHandoffVerificationResult.Rejected -> {
                return BootstrapHandoffState.Failed(
                  BootstrapHandoffError.VerificationFailed(verified.error),
                )
              }
            }
          if (event.sequence <= session.lastSequence) continue
          if (event.sequence != session.lastSequence + 1) {
            return BootstrapHandoffState.Failed(BootstrapHandoffError.SequenceViolation)
          }
          when (event) {
            is BootstrapHandoffEvent.Progress -> {
              if (event.stage.ordinal != session.lastProgressOrdinal + 1) {
                return BootstrapHandoffState.Failed(BootstrapHandoffError.StageViolation)
              }
              if (!repository.checkpoint(session.requestId, event.sequence, event.stage)) {
                return BootstrapHandoffState.Failed(BootstrapHandoffError.CheckpointFailed)
              }
              session =
                session.copy(
                  lastSequence = event.sequence,
                  lastProgressOrdinal = event.stage.ordinal,
                )
              publish(BootstrapHandoffState.Progress(event.stage))
            }
            is BootstrapHandoffEvent.SupervisorReady -> {
              if (
                session.lastProgressOrdinal != SupervisorProgress.StartingSupervisor.ordinal ||
                event.sequence != session.lastSequence + 1
              ) {
                return BootstrapHandoffState.Failed(BootstrapHandoffError.StageViolation)
              }
              if (!activateSupervisor(session)) {
                return BootstrapHandoffState.Failed(
                  BootstrapHandoffError.SupervisorActivationFailed,
                )
              }
              repository.discardActive(session.requestId)
              return BootstrapHandoffState.Ready(session.requestId, event.protocolVersion)
            }
            is BootstrapHandoffEvent.Failed -> {
              if (event.stage.ordinal != session.lastProgressOrdinal) {
                return BootstrapHandoffState.Failed(BootstrapHandoffError.StageViolation)
              }
              return BootstrapHandoffState.Failed(
                BootstrapHandoffError.StageFailed(event.stage, event.exitCode),
              )
            }
          }
        }
      }
    }
    if (nowEpochSeconds() - session.createdAtEpochSeconds > HANDOFF_TIMEOUT_SECONDS) {
      return BootstrapHandoffState.Failed(BootstrapHandoffError.TimedOut)
    }
    try {
      sleep(HANDOFF_POLL_INTERVAL_MILLIS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }
  return BootstrapHandoffState.Stopped
}
