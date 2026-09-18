package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayMethod
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.i18n.nativeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

internal data class GatewayRuntimeIdentity(
  val supervisorBootId: String,
  val supervisorGatewayGeneration: String,
  val connection: AiGatewayConnection,
)

internal fun interface GatewayRuntimeIdentitySource {
  fun current(): GatewayRuntimeIdentity?
}

internal sealed interface GatewayRestartState {
  data object Idle : GatewayRestartState

  data class Checking(
    val ownerId: String,
  ) : GatewayRestartState

  data class WaitingForSafeRestart(
    val ownerId: String,
    val activeWorkCount: Int,
    val summary: String,
    val previous: GatewayRuntimeIdentity,
  ) : GatewayRestartState

  data class Restarting(
    val ownerId: String,
    val previous: GatewayRuntimeIdentity,
  ) : GatewayRestartState

  data class Reconnecting(
    val ownerId: String,
    val previous: GatewayRuntimeIdentity,
  ) : GatewayRestartState

  data class UnknownOutcome(
    val ownerId: String,
    val previous: GatewayRuntimeIdentity,
  ) : GatewayRestartState

  data class Ready(
    val ownerId: String,
    val current: GatewayRuntimeIdentity,
  ) : GatewayRestartState

  data class Failed(
    val ownerId: String,
    val message: String,
  ) : GatewayRestartState
}

internal fun GatewayRestartState.isPlannedInterruption(): Boolean =
  this is GatewayRestartState.WaitingForSafeRestart ||
    this is GatewayRestartState.Restarting ||
    this is GatewayRestartState.Reconnecting ||
    this is GatewayRestartState.UnknownOutcome

/** One owner for preflight, safe restart admission and new-generation proof. */
internal class GatewayRestartCoordinator(
  private val scope: CoroutineScope,
  private val transport: AiGatewayTransport,
  private val identitySource: GatewayRuntimeIdentitySource,
  private val json: Json,
  private val actionsEnabled: Boolean = true,
  private val restartTimeoutMs: Long = 5 * 60_000L,
) {
  private val mutableState = MutableStateFlow<GatewayRestartState>(GatewayRestartState.Idle)
  val state = mutableState.asStateFlow()
  private val operationSequence = AtomicLong()
  private var timeoutJob: Job? = null

  fun request(
    ownerId: String,
    reason: String,
  ) {
    if (!actionsEnabled || ownerId.isBlank() || mutableState.value.blocksNewRestart()) return
    val identity = identitySource.current()
    if (identity == null || !identity.connection.adminScope) {
      mutableState.value = GatewayRestartState.Failed(ownerId, nativeString("Reconnect with administrator access before restarting OpenClaw."))
      return
    }
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = GatewayRestartState.Checking(ownerId)
    scope.launch(start = CoroutineStart.UNDISPATCHED) { requestRestart(sequence, ownerId, reason, identity) }
  }

  fun onRuntimeIdentityChanged() {
    val current = identitySource.current()
    val state = mutableState.value
    val previous = state.previousIdentity() ?: return
    if (current == null) {
      if (state is GatewayRestartState.Restarting || state is GatewayRestartState.WaitingForSafeRestart) {
        mutableState.value = GatewayRestartState.Reconnecting(state.ownerId(), previous)
      }
      return
    }
    if (current.isNewGenerationAfter(previous)) {
      timeoutJob?.cancel()
      mutableState.value = GatewayRestartState.Ready(state.ownerId(), current)
    }
  }

  fun reconcile() {
    val state = mutableState.value
    val previous = state.previousIdentity() ?: return
    val current = identitySource.current()
    if (current?.isNewGenerationAfter(previous) == true) {
      timeoutJob?.cancel()
      mutableState.value = GatewayRestartState.Ready(state.ownerId(), current)
    } else if (current == null) {
      mutableState.value = GatewayRestartState.Reconnecting(state.ownerId(), previous)
    }
  }

  fun consume(ownerId: String) {
    if (mutableState.value.ownerIdOrNull() != ownerId) return
    timeoutJob?.cancel()
    operationSequence.incrementAndGet()
    mutableState.value = GatewayRestartState.Idle
  }

  private suspend fun requestRestart(
    sequence: Long,
    ownerId: String,
    reason: String,
    identity: GatewayRuntimeIdentity,
  ) {
    try {
      val preflight =
        parseGatewayRestartPreflight(
          transport.request(
            identity.connection,
            GatewayMethod.GatewayRestartPreflight.rawValue,
            "{}",
          ),
          json,
        ) ?: error("Malformed restart preflight")
      val result =
        parseGatewayRestartRequestResult(
          transport.request(
            identity.connection,
            GatewayMethod.GatewayRestartRequest.rawValue,
            gatewayRestartRequestParams(reason),
          ),
          json,
        ) ?: error("Malformed restart result")
      publish(identity.connection, sequence) {
        when (result.status) {
          GatewayRestartRequestStatus.Deferred ->
            GatewayRestartState.WaitingForSafeRestart(
              ownerId = ownerId,
              activeWorkCount = result.preflight.totalActive,
              summary = result.preflight.summary,
              previous = identity,
            )
          GatewayRestartRequestStatus.Scheduled,
          GatewayRestartRequestStatus.Coalesced,
          -> GatewayRestartState.Restarting(ownerId, identity)
        }
      }
      if (operationSequence.get() == sequence) scheduleTimeout(sequence, ownerId, identity)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: GatewayRequestOutcomeUnknown) {
      if (operationSequence.get() == sequence) {
        mutableState.value = GatewayRestartState.UnknownOutcome(ownerId, identity)
        scheduleTimeout(sequence, ownerId, identity)
      }
    } catch (_: Throwable) {
      publish(identity.connection, sequence) {
        GatewayRestartState.Failed(ownerId, nativeString("OpenClaw could not schedule this restart."))
      }
    }
  }

  private fun scheduleTimeout(
    sequence: Long,
    ownerId: String,
    identity: GatewayRuntimeIdentity,
  ) {
    timeoutJob?.cancel()
    timeoutJob =
      scope.launch {
        delay(restartTimeoutMs)
        if (operationSequence.get() == sequence && mutableState.value.previousIdentity() == identity) {
          mutableState.value =
            GatewayRestartState.UnknownOutcome(
              ownerId,
              identity,
            )
        }
      }
  }

  private fun publish(
    connection: AiGatewayConnection,
    sequence: Long,
    state: () -> GatewayRestartState,
  ) {
    if (operationSequence.get() != sequence) return
    transport.publish(connection) {
      if (operationSequence.get() == sequence) mutableState.value = state()
    }
  }
}

private fun GatewayRuntimeIdentity.isNewGenerationAfter(previous: GatewayRuntimeIdentity): Boolean =
  supervisorBootId == previous.supervisorBootId &&
    supervisorGatewayGeneration != previous.supervisorGatewayGeneration &&
    connection.stableId == previous.connection.stableId &&
    connection.generation != previous.connection.generation

private fun GatewayRestartState.previousIdentity(): GatewayRuntimeIdentity? =
  when (this) {
    is GatewayRestartState.WaitingForSafeRestart -> previous
    is GatewayRestartState.Restarting -> previous
    is GatewayRestartState.Reconnecting -> previous
    is GatewayRestartState.UnknownOutcome -> previous
    GatewayRestartState.Idle,
    is GatewayRestartState.Checking,
    is GatewayRestartState.Ready,
    is GatewayRestartState.Failed,
    -> null
  }

private fun GatewayRestartState.ownerId(): String = checkNotNull(ownerIdOrNull())

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

private fun GatewayRestartState.blocksNewRestart(): Boolean =
  this !is GatewayRestartState.Idle &&
    this !is GatewayRestartState.Ready &&
    this !is GatewayRestartState.Failed
