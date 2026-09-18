package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** Captured by Chat before launching asynchronous work; selection is never consulted again. */
internal data class ChatStopTarget(
  val owner: ChatComposerOwner,
  val sessionId: String,
  val connection: ChatCacheScope,
  val runIds: Set<String>,
)

internal enum class ChatStopPhase { Stopping, Unconfirmed, Confirmed }

internal data class ChatStopState(
  val target: ChatStopTarget,
  val phase: ChatStopPhase,
  val revision: Long,
)

/**
 * Owns Stop acknowledgement and read-only recovery, not permission persistence or native grants.
 * The pinned abort protocol has no expectedSessionId: use exact run IDs, never a broad key-only
 * abort that could stop a replacement Session. Fresh list rows provide live-run evidence;
 * sessions.describe alone does not include that evidence.
 */
internal class ChatStopController(
  private val scope: CoroutineScope,
  private val currentConnection: () -> ChatCacheScope?,
  private val captureLease: (ChatCacheScope) -> GatewaySession.RequestLease?,
  private val onStopping: suspend (ChatStopTarget) -> Unit = {},
  private val onStopped: suspend (ChatStopTarget) -> Boolean,
  private val stopNativeControl: (ChatStopTarget) -> Boolean = { false },
) {
  private val lock = Any()
  private val mutableStates = MutableStateFlow<Map<ChatComposerOwner, ChatStopState>>(emptyMap())
  val states = mutableStates.asStateFlow()

  fun blocksSend(owner: ChatComposerOwner) = states.value[owner]?.phase?.let { it != ChatStopPhase.Confirmed } == true

  fun revision(owner: ChatComposerOwner) = states.value[owner]?.revision ?: 0L

  /** Serializes the last send-enqueue check with a user's Stop, including queued sends. */
  fun enqueue(
    owner: ChatComposerOwner,
    revision: Long,
    action: () -> Unit,
  ): Boolean =
    synchronized(lock) {
      if (blocksSend(owner) || revision(owner) != revision) return false
      action()
      true
    }

  fun stop(target: ChatStopTarget): Boolean = launch(target, dispatch = true)

  /** Checking an unknown result never repeats chat.abort or resumes the interrupted task. */
  fun reconcile(owner: ChatComposerOwner): Boolean {
    val previous = states.value[owner] ?: return false
    val connection = currentConnection()?.takeIf { it.gatewayId == owner.gatewayStableId } ?: return false
    return launch(previous.target.copy(connection = connection), dispatch = false)
  }

  private fun launch(
    target: ChatStopTarget,
    dispatch: Boolean,
  ): Boolean {
    if (target.sessionId.isBlank() ||
      target.connection.gatewayId != target.owner.gatewayStableId ||
      !target.owner.sessionKey.startsWith("agent:${target.owner.agentId}:")
    ) {
      return false
    }
    val lease =
      synchronized(lock) {
        if (states.value[target.owner]?.phase == ChatStopPhase.Stopping || currentConnection() != target.connection) return false
        val captured = captureLease(target.connection) ?: return false
        if (!captured.isCurrent()) return false
        mutableStates.update { it + (target.owner to ChatStopState(target, ChatStopPhase.Stopping, revision(target.owner) + 1)) }
        // Native revocation is synchronous, before queue/disk/network work can suspend Stop.
        // A failure here must not prevent the subsequent Gateway cancellation attempt.
        revokeNative(target)
        captured
      }
    scope.launch {
      var confirmed = false
      try {
        // Storage failure must not prevent cancellation of already running work. It does
        // prevent completion: queued input must be parked before this Chat can continue.
        val queueParked =
          try {
            onStopping(target)
            true
          } catch (error: CancellationException) {
            throw error
          } catch (_: Exception) {
            false
          }
        val before = read(target, lease)
        val executionTarget = target.copy(runIds = target.runIds + before.runIds)
        revokeNative(executionTarget)
        if (dispatch) {
          // A false active flag can precede registration of a locally admitted send. Include
          // the captured local run IDs, but never turn missing IDs into abort-all.
          val runs = executionTarget.runIds
          check(!before.active || runs.isNotEmpty())
          for (runId in runs) {
            val params =
              buildJsonObject {
                put("sessionKey", JsonPrimitive(target.owner.sessionKey))
                put("agentId", JsonPrimitive(target.owner.agentId))
                put("runId", JsonPrimitive(runId))
              }
            val ack = root(request(target, lease, "chat.abort", params.toString()))
            check(ack.flag("ok") == true && ack.flag("aborted") != null)
          }
        }
        val after = if (dispatch) read(target, lease) else before
        if (queueParked && !after.active && after.runIds.isEmpty() && isCurrent(target, lease)) {
          confirmed = revokeNative(executionTarget) && onStopped(target) && isCurrent(target, lease)
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        // Lost ack, a replaced Session, or unavailable live-run metadata is not success.
      } finally {
        mutableStates.update {
          val state = it[target.owner]
          if (state?.target == target) it + (target.owner to state.copy(phase = if (confirmed) ChatStopPhase.Confirmed else ChatStopPhase.Unconfirmed)) else it
        }
      }
    }
    return true
  }

  private fun revokeNative(target: ChatStopTarget): Boolean =
    try {
      stopNativeControl(target)
    } catch (_: Exception) {
      false
    }

  private data class Activity(
    val active: Boolean,
    val runIds: Set<String>,
  )

  private suspend fun read(
    target: ChatStopTarget,
    lease: GatewaySession.RequestLease,
  ): Activity {
    val params =
      buildJsonObject {
        put("agentId", JsonPrimitive(target.owner.agentId))
        put("search", JsonPrimitive(target.owner.sessionKey))
        put("limit", JsonPrimitive(200))
        put("archived", JsonPrimitive("all"))
        put("includeUnknown", JsonPrimitive(false))
      }
    val rows = root(request(target, lease, "sessions.list", params.toString()))["sessions"] as? JsonArray ?: error("Missing Session list")
    val row = rows.mapNotNull { it as? JsonObject }.singleOrNull { it.string("key") == target.owner.sessionKey } ?: error("Session unavailable")
    check(row.string("sessionId") == target.sessionId)
    row.string("agentId")?.let { check(it == target.owner.agentId) }
    val active = row.flag("hasActiveRun") ?: error("Missing live-run evidence")
    val ids =
      when (val raw = row["activeRunIds"]) {
        // A live Session row may omit IDs even while active. The caller can still use
        // exact IDs captured from its send acknowledgement/events; dispatch below rejects
        // an active row when neither source supplies an identity. Never use abort-all.
        null -> emptySet()
        is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) ?: error("Invalid run ID") }.toSet()
        else -> error("Invalid run IDs")
      }
    check(active || ids.isEmpty())
    return Activity(active, ids)
  }

  private fun isCurrent(
    target: ChatStopTarget,
    lease: GatewaySession.RequestLease,
  ) = currentConnection() == target.connection && lease.isCurrent()

  private suspend fun request(
    target: ChatStopTarget,
    lease: GatewaySession.RequestLease,
    method: String,
    params: String,
  ): String {
    check(isCurrent(target, lease))
    val result =
      lease.request(method, params, withEnqueue = { enqueue ->
        check(isCurrent(target, lease))
        enqueue()
      })
    check(isCurrent(target, lease))
    return result
  }

  private fun root(raw: String) = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid response")

  private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
