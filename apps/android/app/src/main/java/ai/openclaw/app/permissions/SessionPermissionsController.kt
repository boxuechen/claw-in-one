package ai.openclaw.app.permissions

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestRejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One permission owner per App; per-Chat lanes serialize reads and writes, not unrelated Chats. */
internal class SessionPermissionsController(
  private val scope: CoroutineScope,
  private val transport: SessionPermissionsTransport,
  private val onReady: () -> Unit = {},
) {
  private val codec = SessionPermissionsCodec()
  private val lanes = ConcurrentHashMap<SessionPermissionTarget, Mutex>()
  private val creations = ConcurrentHashMap<SessionPermissionTarget, SessionPermissionDraft>()
  private val completedCreations = ConcurrentHashMap<SessionPermissionTarget, String>()
  private val mutableStates = MutableStateFlow<Map<SessionPermissionTarget, SessionPermissionsState>>(emptyMap())
  val states = mutableStates.asStateFlow()
  val feature = SessionPermissionsFeature(states, ::refresh, ::choose, ::reconcileCreation)

  fun state(target: SessionPermissionTarget) = states.value[target] ?: SessionPermissionsState(target)

  suspend fun refresh(target: SessionPermissionTarget): SessionPermissionsState =
    lane(target).withLock {
      read(target)
    }

  /** The caller invokes this again at send admission; a cached Full label is never admission. */
  suspend fun prepareSend(target: SessionPermissionTarget): SessionPermissionRef? {
    val current = refresh(target)
    return current.ref?.takeIf { current.readyToSend && transport.capture() == it.connection }
  }

  /** Called only by first-send admission or an explicit retry, never by opening blank Chat. */
  suspend fun create(draft: SessionPermissionDraft): SessionPermissionRef? {
    val target = draft.target
    val lane = lane(target)
    if (!lane.tryLock()) return null
    try {
      val connection = connection(target, "sessions.create") ?: return null
      if (!connection.canChoose(draft.mode)) {
        set(state(target).copy(failure = SessionPermissionFailure.MissingAuthority))
        return null
      }
      val previous = creations.putIfAbsent(target, draft)
      if (previous != null && previous != draft) return null
      completedCreations[target]?.let { sessionId ->
        val current = read(target)
        return current.ref?.takeIf {
          current.readyToSend &&
            current.savedMode == draft.mode &&
            it.projectId == draft.projectId &&
            it.projectRoot == draft.projectRoot &&
            it.sessionId == sessionId &&
            it.connection == connection
        }
      }
      // Read the exact reserved key before retrying. That key also prevents a second Chat
      // after the Gateway's finite idempotency retention window has elapsed.
      if (previous != null) {
        val existing =
          try {
            codec.lookup(transport.request(connection, "sessions.describe", codec.describeParams(target)))
          } catch (error: CancellationException) {
            throw error
          } catch (_: Exception) {
            markUnconfirmed(target, draft.mode)
            return null
          }
        // Existing but changed is not missing: an old draft must never overwrite a later
        // permission choice, even after the idempotency cache expires.
        if (existing != null) return confirmCreation(connection, draft, null)
      }
      if (!setCurrent(connection, state(target).copy(requestedMode = draft.mode, phase = SessionPermissionPhase.Applying, failure = null))) return null
      val acknowledgement =
        try {
          codec.mutation(transport.request(connection, "sessions.create", codec.createParams(draft))).also {
            require(it.key == target.key && it.mode == draft.mode && it.projectId == draft.projectId)
          }
        } catch (error: CancellationException) {
          markUnconfirmed(target, draft.mode)
          throw error
        } catch (_: Exception) {
          markUnconfirmed(target, draft.mode)
          return null
        }
      return confirmCreation(connection, draft, acknowledgement) ?: run {
        markUnconfirmed(target, draft.mode)
        null
      }
    } catch (error: CancellationException) {
      if (creations[target] == draft) markUnconfirmed(target, draft.mode)
      throw error
    } finally {
      lane.unlock()
    }
  }

  /** Read-only recovery never retries create or sends the user's message. */
  suspend fun reconcileCreation(target: SessionPermissionTarget): SessionPermissionRef? =
    lane(target).withLock {
      if (completedCreations.containsKey(target)) return@withLock null
      val draft = creations[target] ?: return@withLock null
      val connection = connection(target, "sessions.describe") ?: return@withLock null
      confirmCreation(connection, draft, null)
    }

  private suspend fun confirmCreation(
    connection: SessionPermissionConnection,
    draft: SessionPermissionDraft,
    acknowledgement: SessionPermissionMutation?,
  ): SessionPermissionRef? {
    val row = fetch(connection, draft.target) ?: return null
    val confirmedProjectId =
      when (draft.mode) {
        SessionPermissionMode.Workspace -> {
          if (row.sessionRoot != draft.projectRoot) return null
          val projectedProjectId = row.projectId ?: acknowledgement?.projectId ?: draft.projectId
          if (projectedProjectId != draft.projectId) return null
          projectedProjectId
        }
        else -> {
          if (row.projectId != null || acknowledgement?.projectId != null) return null
          null
        }
      }
    if (
      row.pending ||
      row.mode != draft.mode ||
      (acknowledgement != null && row.sessionId != acknowledgement.sessionId)
    ) {
      return null
    }
    if (draft.modelRef != null && row.modelRef != draft.modelRef) return null
    if (draft.thinkingLevel != null && row.thinkingLevel != draft.thinkingLevel) return null
    if (draft.displayName != null && row.displayName != draft.displayName) return null
    val ref =
      SessionPermissionRef(
        target = draft.target,
        connection = connection,
        sessionId = row.sessionId,
        lifecycleRevision = acknowledgement?.lifecycleRevision,
        projectId = confirmedProjectId,
        projectRoot = draft.projectRoot,
      )
    val confirmed = SessionPermissionsState(draft.target, ref, row.mode, row.mode, phase = SessionPermissionPhase.Ready)
    if (!setCurrent(connection, confirmed)) return null
    completedCreations[draft.target] = row.sessionId
    return ref
  }

  suspend fun choose(
    target: SessionPermissionTarget,
    mode: SessionPermissionMode,
  ): Boolean {
    val lane = lane(target)
    // Duplicate taps never queue a second write behind the first one.
    if (!lane.tryLock()) return false
    try {
      val connection = connection(target, "sessions.patch") ?: return false
      if (!connection.canChoose(mode)) {
        set(state(target).copy(failure = SessionPermissionFailure.MissingAuthority))
        return false
      }
      // Preconditions belong to the state the user actually chose from. A pre-write refresh
      // must not silently replace them and override a change made by another client.
      val before = state(target)
      val ref = before.ref ?: return false
      if (before.phase != SessionPermissionPhase.Ready || ref.connection != connection) return false
      if (before.confirmedMode == mode) return true
      if (!setCurrent(connection, before.copy(requestedMode = mode, phase = SessionPermissionPhase.Applying, failure = null))) return false
      var acknowledged: SessionPermissionMutation? = null
      var identityConflict = false
      var rejectedBeforeSave = false
      try {
        acknowledged = codec.mutation(transport.request(connection, "sessions.patch", codec.patchParams(ref, checkNotNull(before.savedMode), mode)))
        require(acknowledged.key == target.key && acknowledged.sessionId == ref.sessionId && acknowledged.mode == mode)
      } catch (error: CancellationException) {
        markUnconfirmed(target, mode)
        throw error
      } catch (error: Exception) {
        // A rejected RPC can mean saved-but-apply-failed. Never assume rejection means no write.
        acknowledged = null
        identityConflict = error is GatewayRequestRejected && codec.isIdentityConflict(error.gatewayError.rawDetailsJson)
        rejectedBeforeSave = error is GatewayRequestNotEnqueued ||
          (error is GatewayRequestRejected && (error.gatewayError.code == "INVALID_REQUEST" || error.gatewayError.missingScopeDetails() != null))
      }
      if (transport.capture() != connection) {
        markUnconfirmed(target, mode)
        return false
      }
      val row = fetch(connection, target)
      if (row == null) {
        markUnconfirmed(target, mode)
        return false
      }
      if (identityConflict && !row.pending) {
        // The canonical conflict reason proves this write was rejected before changing the
        // target. Publish current truth, but never retry the user's stale choice automatically.
        setCurrent(
          connection,
          before.copy(
            ref = SessionPermissionRef(target, connection, row.sessionId, projectId = row.projectId),
            savedMode = row.mode,
            confirmedMode = row.mode,
            requestedMode = null,
            phase = SessionPermissionPhase.Ready,
            failure = SessionPermissionFailure.IdentityChanged,
          ),
        )
        return false
      }
      if (row.sessionId != ref.sessionId) {
        set(before.copy(requestedMode = mode, phase = SessionPermissionPhase.Unconfirmed, failure = SessionPermissionFailure.IdentityChanged))
        return false
      }
      val confirmed = acknowledged != null && row.mode == mode && !row.pending
      val unchangedRejection = rejectedBeforeSave && row.mode == before.savedMode && !row.pending
      val next =
        before.copy(
          ref = ref.copy(lifecycleRevision = acknowledged?.lifecycleRevision),
          savedMode = row.mode,
          confirmedMode = if (confirmed) mode else before.confirmedMode,
          requestedMode = if (confirmed || unchangedRejection) null else mode,
          phase =
            when {
              confirmed || unchangedRejection -> SessionPermissionPhase.Ready
              row.pending && acknowledged != null -> SessionPermissionPhase.Applying
              else -> SessionPermissionPhase.Unconfirmed
            },
          failure =
            when {
              confirmed -> null
              unchangedRejection -> SessionPermissionFailure.ChangeRejected
              acknowledged != null && row.mode == mode && row.pending -> null
              else -> SessionPermissionFailure.ChangeUnconfirmed
            },
        )
      if (transport.capture() != connection) {
        markUnconfirmed(target, mode)
        return false
      }
      return setCurrent(connection, next) && confirmed
    } catch (error: CancellationException) {
      if (state(target).requestedMode == mode) markUnconfirmed(target, mode)
      throw error
    } finally {
      lane.unlock()
    }
  }

  /** Only a task owner with an acknowledged Stop may call this; UI does not fabricate that proof. */
  suspend fun reconcileStopped(ref: SessionPermissionRef): Boolean =
    lane(ref.target).withLock {
      if (transport.capture() != ref.connection) return@withLock false
      val before = state(ref.target)
      if (before.ref?.sessionId != ref.sessionId) return@withLock false
      val row = fetch(ref.connection, ref.target) ?: return@withLock false
      if (row.sessionId != ref.sessionId || row.pending || transport.capture() != ref.connection) return@withLock false
      setCurrent(ref.connection, before.copy(ref = ref.copy(lifecycleRevision = null), savedMode = row.mode, confirmedMode = row.mode, requestedMode = null, phase = SessionPermissionPhase.Ready, failure = null))
    }

  fun onEvent(
    event: String,
    payload: String?,
  ) {
    if (event != "sessions.changed") return
    val key = payload?.let { runCatching { codec.changedKey(it) }.getOrNull() }
    states.value.keys
      .filter { key == null || it.key == key }
      .forEach { target -> scope.launch { refresh(target) } }
  }

  fun onConnectionChanged() {
    val current = transport.capture()
    mutableStates.update { states ->
      states.mapValues { (_, state) ->
        if (state.ref?.connection == current && current != null) {
          state
        } else {
          state.copy(
            ref = state.ref?.copy(lifecycleRevision = null),
            phase = if (state.requestedMode != null) SessionPermissionPhase.Unconfirmed else SessionPermissionPhase.Unavailable,
            failure = SessionPermissionFailure.Disconnected,
          )
        }
      }
    }
  }

  private suspend fun read(target: SessionPermissionTarget): SessionPermissionsState {
    val connection = connection(target, "sessions.describe") ?: return state(target)
    val before = state(target)
    val row = fetch(connection, target)
    if (transport.capture() != connection) return state(target)
    if (row == null) {
      val next = before.copy(phase = if (before.requestedMode != null) SessionPermissionPhase.Unconfirmed else SessionPermissionPhase.Unavailable, failure = SessionPermissionFailure.ReadFailed)
      setCurrent(connection, next)
      return state(target)
    }
    val uncertainWrite = before.requestedMode != null && before.failure != null
    val retainedProject =
      before.ref?.takeIf {
        row.projectId == null &&
          row.sessionId == it.sessionId &&
          row.mode == SessionPermissionMode.Workspace &&
          row.sessionRoot == it.projectRoot
      }
    val next =
      before.copy(
        ref =
          SessionPermissionRef(
            target,
            connection,
            row.sessionId,
            projectId = row.projectId ?: retainedProject?.projectId,
            projectRoot =
              if ((row.projectId ?: retainedProject?.projectId) != null) {
                row.sessionRoot ?: retainedProject?.projectRoot
              } else {
                null
              },
          ),
        savedMode = row.mode,
        confirmedMode = if (row.pending || uncertainWrite) before.confirmedMode else row.mode,
        requestedMode = if (uncertainWrite || row.pending) before.requestedMode else null,
        phase =
          when {
            uncertainWrite -> SessionPermissionPhase.Unconfirmed
            row.pending -> SessionPermissionPhase.Applying
            else -> SessionPermissionPhase.Ready
          },
        failure = if (uncertainWrite) before.failure else null,
      )
    setCurrent(connection, next)
    return state(target)
  }

  private suspend fun fetch(
    connection: SessionPermissionConnection,
    target: SessionPermissionTarget,
  ): SessionPermissionRow? =
    try {
      codec.describe(transport.request(connection, "sessions.describe", codec.describeParams(target))).also {
        require(it.key == target.key && it.agentId == target.agentId)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null
    }

  private fun connection(
    target: SessionPermissionTarget,
    method: String,
  ): SessionPermissionConnection? {
    val connection = transport.capture()
    val failure =
      when {
        connection == null || connection.gatewayId != target.gatewayId -> SessionPermissionFailure.Disconnected
        !connection.canRead -> SessionPermissionFailure.MissingAuthority
        method !in connection.methods || "sessions.describe" !in connection.methods -> SessionPermissionFailure.Unsupported
        else -> null
      }
    if (failure != null) {
      set(state(target).copy(phase = SessionPermissionPhase.Unavailable, failure = failure))
      return null
    }
    return connection
  }

  private fun markUnconfirmed(
    target: SessionPermissionTarget,
    mode: SessionPermissionMode,
  ) {
    set(state(target).copy(requestedMode = mode, phase = SessionPermissionPhase.Unconfirmed, failure = SessionPermissionFailure.ChangeUnconfirmed))
  }

  private fun lane(target: SessionPermissionTarget) = lanes.getOrPut(target) { Mutex() }

  private fun set(state: SessionPermissionsState) {
    val previous = mutableStates.getAndUpdate { it + (state.target to state) }[state.target]
    if (state.readyToSend && previous?.readyToSend != true) onReady()
  }

  private fun setCurrent(
    connection: SessionPermissionConnection,
    state: SessionPermissionsState,
  ): Boolean = transport.publish(connection) { set(state) }
}
