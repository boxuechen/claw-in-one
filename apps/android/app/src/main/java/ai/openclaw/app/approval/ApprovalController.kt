package ai.openclaw.app.approval

import ai.openclaw.app.gateway.GatewayRequestDefinitiveFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ApprovalConnection(
  val stableId: String,
  val generation: Long,
  val catalogRevision: Long,
  val methods: Set<String>,
)

/** Transport owns socket/auth epochs; the inbox owns review and mutation state only. */
internal interface ApprovalTransport {
  fun capture(): ApprovalConnection?

  fun publish(
    connection: ApprovalConnection,
    block: () -> Unit,
  ): Boolean

  suspend fun request(
    connection: ApprovalConnection,
    method: String,
    params: String,
  ): String
}

internal class ApprovalController(
  private val scope: CoroutineScope,
  private val transport: ApprovalTransport,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val codec = ApprovalCodec()
  private val lock = Any()
  private val mutableState = MutableStateFlow(ApprovalInboxState())
  val state = mutableState.asStateFlow()

  /** Actions use the inbox's lifetime, so dismissing a review cannot abandon its decision. */
  fun feature(refreshEnabled: Boolean): ApprovalFeature =
    ApprovalFeature(
      state = state,
      actions =
        ApprovalActions(
          refresh = { if (refreshEnabled) scope.launch { refresh() } },
          resolve = { id, kind, decision ->
            // Register the exact record/connection before leaving the caller's turn.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { resolve(id, kind, decision) }
          },
          dismiss = ::dismiss,
        ),
    )

  private var revision = 0L
  private var publication = 0L
  private var expiryJob: Job? = null
  private val terminalIds = linkedSetOf<String>()
  private val writes = mutableMapOf<Pair<String, String>, Write>()
  private val attributions = mutableMapOf<String, ApprovalAttribution>()
  private val eventTimes = mutableMapOf<String, Long>()
  private var selectedSession: ApprovalSession? = null
  private var activeSessions = emptySet<ApprovalSession>()
  private var candidates = emptySet<ApprovalSession>()
  private val subscriptions =
    ApprovalSessionSubscriptions(scope, transport, ::acceptReplay) { connection, target ->
      publish(connection) {
        mutableState.value = state.value.copy(incompleteSessions = state.value.incompleteSessions + target)
      }
    }

  fun setSessions(
    selected: ApprovalSession?,
    active: Set<ApprovalSession>,
  ) = synchronized(lock) {
    selectedSession = selected
    activeSessions = active
    updateSubscriptions()
  }

  private fun updateSubscriptions() {
    val waiting = state.value.rows.mapNotNull { it.attribution.sourceSessionKey?.let(::ApprovalSession) }
    subscriptions.update(listOfNotNull(selectedSession).toSet() + activeSessions + waiting + candidates)
  }

  private class Write(
    val connection: ApprovalConnection,
    val row: ApprovalRow,
    val decision: String,
  ) {
    var inFlight = true
  }

  fun clear(retirePendingWrites: Boolean) =
    synchronized(lock) {
      revision++
      expiryJob?.cancel()
      subscriptions.reset()
      candidates = emptySet()
      if (retirePendingWrites) {
        writes.clear()
        terminalIds.clear()
        attributions.clear()
        eventTimes.clear()
      }
      mutableState.value = ApprovalInboxState()
    }

  fun dismiss(notice: ApprovalNotice) =
    synchronized(lock) {
      if (mutableState.value.notice == notice) mutableState.value = mutableState.value.copy(notice = null)
    }

  fun onEvent(
    event: String,
    payload: String?,
  ) {
    val connection = transport.capture() ?: return
    if (event == "session.approval") {
      val change = runCatching { codec.sessionEvent(payload ?: return) }.getOrNull() ?: return
      publish(connection) {
        mergeAttribution(change.approval.id, ApprovalAttribution(change.sourceSessionKey, setOf(change.audience)))
        when (val snapshot = change.approval) {
          is ApprovalSnapshot.Pending -> acceptPending(connection, snapshot.row, change.updatedAtMs)
          is ApprovalSnapshot.Terminal -> retire(connection, snapshot, appliedHere = false)
        }
        updateSubscriptions()
        scheduleExpiry()
      }
      return
    }
    val kind =
      when {
        event.startsWith("exec.approval.") -> ApprovalKind.Exec
        event.startsWith("plugin.approval.") -> ApprovalKind.Plugin
        else -> return
      }
    if (!event.endsWith(".requested") && !event.endsWith(".resolved")) return
    payload?.let {
      publish(connection) {
        candidates = (candidates + codec.candidates(it)).take(64).toSet()
        updateSubscriptions()
      }
    }
    val id = codec.eventId(payload)
    scope.launch { refresh(id?.let { mapOf(it to kind) }.orEmpty(), connection) }
  }

  suspend fun refresh(
    extraIds: Map<String, ApprovalKind> = emptyMap(),
    expectedConnection: ApprovalConnection? = null,
  ) {
    val connection = transport.capture() ?: return
    if (expectedConnection != null && expectedConnection != connection) return
    var epoch = 0L
    if (!publish(connection) {
        epoch = ++revision
        mutableState.value = mutableState.value.copy(refreshing = true, failure = null)
      }
    ) {
      return
    }
    try {
      require(connection.methods.containsAll(REQUIRED_METHODS)) { "Canonical approvals unavailable" }
      val discoveredCandidates = mutableSetOf<ApprovalSession>()
      val discovered =
        ApprovalKind.entries.flatMap { kind ->
          val payload = transport.request(connection, "${kind.wire}.approval.list", "{}")
          discoveredCandidates += codec.candidates(payload)
          publish(connection) {
            if (epoch == revision) {
              candidates = (candidates + codec.candidates(payload)).take(64).toSet()
              updateSubscriptions()
            }
          }
          codec.ids(payload, kind)
        }
      require(discovered.groupBy { it.id }.values.all { rows -> rows.map { it.kind }.distinct().size == 1 })
      val previous = state.value.rows.associateBy { it.id }
      val pendingWrites = synchronized(lock) { writes.values.filter { it.connection.stableId == connection.stableId }.map { it.row } }
      val lookup = (discovered + previous.values + pendingWrites).associate { it.id to it.kind } + extraIds
      val snapshots = mutableListOf<ApprovalSnapshot>()
      val failures = mutableListOf<ApprovalRow>()
      for ((id, kind) in lookup) {
        try {
          snapshots += codec.get(transport.request(connection, "approval.get", approvalGetParams(id)), id, kind)
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (_: Throwable) {
          val row = discovered.firstOrNull { it.id == id } ?: previous[id] ?: pendingWrites.firstOrNull { it.id == id }
          row?.let { failures += it.copy(details = null, allowedDecisions = emptyList(), failure = ApprovalFailure.LoadDetails) }
        }
      }
      publish(connection) {
        if (epoch != revision) return@publish
        val visible =
          state.value.rows
            .map { it.id }
            .toSet() + pendingWrites.map { it.id }
        snapshots.filterIsInstance<ApprovalSnapshot.Terminal>().forEach { terminal ->
          retire(connection, terminal, appliedHere = false, showNotice = terminal.id in visible)
        }
        val rows = snapshots.filterIsInstance<ApprovalSnapshot.Pending>().map { it.row } + failures + state.value.rows.filter { it.id !in lookup }
        mutableState.value =
          state.value.copy(
            rows =
              rows
                .filterNot { it.id in terminalIds }
                .map { row ->
                  val write = writes[connection.stableId to row.id]
                  row.copy(
                    attribution = mergeAttribution(row.id, row.attribution),
                    resolvingDecision = write?.decision,
                    allowedDecisions = if (row.expiresAtMs <= now()) emptyList() else row.allowedDecisions,
                    failure = if (write != null && !write.inFlight) ApprovalFailure.OutcomeUnknown else row.failure,
                  )
                }.sortedBy { it.createdAtMs },
          )
        scheduleExpiry()
        candidates = discoveredCandidates.take(64).toSet()
        updateSubscriptions()
        subscriptions.refresh()
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Throwable) {
      publish(connection) { if (epoch == revision) mutableState.value = state.value.copy(failure = ApprovalFailure.LoadInbox) }
    } finally {
      publish(connection) { if (epoch == revision) mutableState.value = state.value.copy(refreshing = false) }
    }
  }

  suspend fun resolve(
    id: String,
    kind: ApprovalKind,
    decision: String,
  ) {
    if (!validApprovalId(id)) return
    val connection = transport.capture() ?: return
    var registered: Write? = null
    publish(connection) {
      val row = state.value.rows.firstOrNull { it.id == id && it.kind == kind } ?: return@publish
      if (row.expiresAtMs <= now() || decision !in row.allowedDecisions || row.details == null) return@publish
      val key = connection.stableId to id
      if (writes.containsKey(key) || id in terminalIds || !connection.methods.containsAll(REQUIRED_METHODS)) return@publish
      val write = Write(connection, row, decision)
      writes[key] = write
      registered = write
      revision++
      mutableState.value =
        state.value.copy(
          refreshing = false,
          rows =
            state.value.rows.map {
              if (it.id == id) it.copy(resolvingDecision = decision, failure = null) else it
            },
        )
    }
    val write = registered ?: return
    try {
      val raw = transport.request(connection, "approval.resolve", approvalResolveParams(id, kind, decision))
      val result = codec.resolve(raw, id, kind, decision)
      publish(connection) {
        if (writes[connection.stableId to id] !== write) {
          // A terminal event can arrive before our RPC acknowledgement. Only the
          // canonical applied result proves this reviewer won; never infer it from an event.
          val notice = state.value.notice
          if (result.applied && id in terminalIds && notice?.approvalId == id && notice.status == result.approval.status && notice.decision == result.approval.decision) {
            mutableState.value = state.value.copy(notice = notice.copy(appliedHere = true))
          }
          return@publish
        }
        revision++
        retire(connection, result.approval, result.applied)
      }
    } catch (_: GatewayRequestDefinitiveFailure) {
      publish(connection) {
        if (writes[connection.stableId to id] !== write) return@publish
        writes.remove(connection.stableId to id)
        revision++
        mutableState.value =
          state.value.copy(
            rows =
              state.value.rows.map {
                if (it.id == id) it.copy(resolvingDecision = null, failure = ApprovalFailure.Resolve) else it
              },
          )
      }
    } catch (cancel: CancellationException) {
      throw cancel
    } catch (_: Throwable) {
      // An enqueued write with no authoritative result remains blocked until readback.
    } finally {
      val needsReadback =
        synchronized(lock) {
          write.inFlight = false
          writes[connection.stableId to id] === write
        }
      if (needsReadback) {
        val current = transport.capture()
        if (current?.stableId == connection.stableId) {
          publish(current) {
            mutableState.value =
              state.value.copy(
                rows =
                  state.value.rows.map {
                    if (it.id == id) it.copy(resolvingDecision = decision, failure = ApprovalFailure.OutcomeUnknown) else it
                  },
              )
          }
          // Never replay a write. Reconnection/readback may only establish the durable winner.
          scope.launch { refresh(mapOf(id to kind), current) }
        }
      }
    }
  }

  private fun retire(
    connection: ApprovalConnection,
    terminal: ApprovalSnapshot.Terminal,
    appliedHere: Boolean,
    showNotice: Boolean = true,
  ) {
    val previous = state.value.rows.firstOrNull { it.id == terminal.id }
    val source = terminal.source
    val canonicalSource =
      source?.sessionKey?.let { key ->
        state.value.sessionKeys[ApprovalSession(key, source.agentId)]
          ?: key.takeIf { it == attributions[terminal.id]?.sourceSessionKey || it in state.value.sessionKeys.values }
      }
    val attribution = mergeAttribution(terminal.id, ApprovalAttribution(canonicalSource))
    writes.remove(connection.stableId to terminal.id)
    val wasTerminal = terminal.id in terminalIds
    terminalIds.add(terminal.id)
    if (terminalIds.size > 256) {
      val oldest = terminalIds.first()
      terminalIds.remove(oldest)
      attributions.remove(oldest)
      eventTimes.remove(oldest)
    }
    val oldOutcome = state.value.outcomes.firstOrNull { it.approval.id == terminal.id }
    val outcome = ApprovalOutcome(terminal, previous ?: oldOutcome?.row, attribution)
    mutableState.value =
      state.value.copy(
        rows = state.value.rows.filterNot { it.id == terminal.id },
        outcomes = (state.value.outcomes.filterNot { it.approval.id == terminal.id } + outcome).takeLast(64),
        notice = if (showNotice && !wasTerminal) ApprovalNotice(terminal.id, terminal.status, terminal.decision, appliedHere, ++publication, attribution.sourceSessionKey.takeUnless { attribution.conflicted }) else state.value.notice,
      )
    updateSubscriptions()
  }

  private fun mergeAttribution(
    id: String,
    incoming: ApprovalAttribution,
  ): ApprovalAttribution {
    val previous = attributions[id] ?: ApprovalAttribution()
    val conflict =
      previous.conflicted ||
        incoming.conflicted ||
        (previous.sourceSessionKey != null && incoming.sourceSessionKey != null && previous.sourceSessionKey != incoming.sourceSessionKey)
    return ApprovalAttribution(
      sourceSessionKey = if (conflict) null else incoming.sourceSessionKey ?: previous.sourceSessionKey,
      audiences = previous.audiences + incoming.audiences,
      conflicted = conflict,
    ).also { attributions[id] = it }
  }

  private fun acceptPending(
    connection: ApprovalConnection,
    incoming: ApprovalRow,
    updatedAtMs: Long,
  ) {
    if (incoming.id in terminalIds || updatedAtMs < (eventTimes[incoming.id] ?: 0)) return
    val previous = state.value.rows.firstOrNull { it.id == incoming.id }
    if (previous != null && previous.kind != incoming.kind) {
      mutableState.value =
        state.value.copy(
          rows =
            state.value.rows.map {
              if (it.id == incoming.id) it.copy(details = null, allowedDecisions = emptyList(), failure = ApprovalFailure.LoadDetails) else it
            },
        )
      scope.launch { refresh(mapOf(previous.id to previous.kind), connection) }
      return
    }
    eventTimes[incoming.id] = updatedAtMs
    val write = writes[connection.stableId to incoming.id]
    val row =
      incoming.copy(
        attribution = mergeAttribution(incoming.id, incoming.attribution),
        resolvingDecision = write?.decision,
        allowedDecisions = if (incoming.expiresAtMs <= now()) emptyList() else incoming.allowedDecisions,
        failure = if (write != null && !write.inFlight) ApprovalFailure.OutcomeUnknown else null,
      )
    mutableState.value = state.value.copy(rows = (state.value.rows.filterNot { it.id == row.id } + row).sortedBy { it.createdAtMs })
  }

  private fun acceptReplay(
    connection: ApprovalConnection,
    target: ApprovalSession,
    replay: ApprovalSessionReplay,
  ) {
    var missing = emptyMap<String, ApprovalKind>()
    publish(connection) {
      mutableState.value =
        state.value.copy(
          sessionKeys = state.value.sessionKeys + (target to replay.audience),
          incompleteSessions = if (replay.truncated) state.value.incompleteSessions + target else state.value.incompleteSessions - target,
        )
      replay.approvals.forEach { row ->
        acceptPending(connection, row.copy(attribution = row.attribution.copy(audiences = setOf(replay.audience))), replay.updatedAtMs)
      }
      val ids = replay.approvals.map { it.id }.toSet()
      missing =
        state.value.rows
          .filter { replay.audience in it.attribution.audiences && it.id !in ids }
          .associate { it.id to it.kind }
      // Missing entries, including a complete replay, need canonical terminal readback.
      // A subscription must never fabricate expiry/denial or clear an unknown write.
      scheduleExpiry()
    }
    if (missing.isNotEmpty()) scope.launch { refresh(missing, connection) }
  }

  private fun publish(
    connection: ApprovalConnection,
    block: () -> Unit,
  ): Boolean = transport.publish(connection) { synchronized(lock) { block() } }

  private fun scheduleExpiry() {
    expiryJob?.cancel()
    val next =
      state.value.rows
        .map { it.expiresAtMs }
        .filter { it > now() }
        .minOrNull() ?: return
    expiryJob =
      scope.launch {
        delay((next - now()).coerceAtLeast(1))
        val connection = transport.capture() ?: return@launch
        publish(connection) {
          mutableState.value =
            state.value.copy(
              rows =
                state.value.rows.map {
                  if (it.expiresAtMs <= now()) it.copy(allowedDecisions = emptyList()) else it
                },
            )
        }
        refresh()
      }
  }

  companion object {
    val REQUIRED_METHODS = setOf("exec.approval.list", "plugin.approval.list", "approval.get", "approval.resolve")
  }
}
