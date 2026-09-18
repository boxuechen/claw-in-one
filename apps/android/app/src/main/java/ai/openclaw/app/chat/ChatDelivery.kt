package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.resolveAgentIdFromMainSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.util.UUID

/** Existing selection/connection revisions captured before settings or permission can suspend. */
internal data class ChatDeliveryOrigin(
  val connection: ChatCacheScope?,
  val owner: ChatComposerOwner,
  val selectionGeneration: Long,
  val defaultAgentRevision: Long?,
) {
  fun ownsUi(current: ChatDeliveryContext): Boolean =
    current.owner == owner &&
      current.selectionGeneration == selectionGeneration &&
      (defaultAgentRevision == null || defaultAgentRevision == current.defaultAgentRevision)
}

/** Read-only composition snapshot; this is not another connection, settings, or permission store. */
internal data class ChatDeliveryContext(
  val connection: ChatCacheScope?,
  val owner: ChatComposerOwner?,
  val selectionGeneration: Long,
  val defaultAgentRevision: Long,
  val sessionKey: String,
  val healthy: Boolean,
  val supportsThinking: Boolean,
)

/** A prepared input; creation, settings and permission application have already completed. */
internal class ChatDeliveryRequest(
  val origin: ChatDeliveryOrigin,
  val sessionKey: String,
  val text: String,
  val thinking: String,
  attachments: List<OutgoingAttachment>,
  val idempotencyKey: String?,
  val lease: GatewaySession.RequestLease?,
  val stopRevision: Long,
) {
  val attachments = attachments.toList()
}

internal sealed interface ChatDeliveryEvent {
  data object Idle : ChatDeliveryEvent

  data class Error(
    val failure: ChatDeliveryFailure?,
  ) : ChatDeliveryEvent
}

internal sealed interface ChatDeliveryFailure {
  data object GatewayUnavailable : ChatDeliveryFailure

  data object TerminalFailed : ChatDeliveryFailure

  data class Admission(
    val reason: ChatJournalFailure,
  ) : ChatDeliveryFailure

  data class Transport(
    val message: String?,
  ) : ChatDeliveryFailure
}

/**
 * Direct and queued delivery sequencing around the existing journal, pending runs and Stop.
 * Owns the drain scheduler, not selection, transcript publication, UI, or permission authority.
 * A received failure or unknown outcome never creates an automatic resend.
 */
internal class ChatDelivery(
  private val scope: CoroutineScope,
  private val commandOutbox: ChatCommandOutbox?,
  private val journal: ChatDeliveryJournal,
  private val outboxState: ChatOutboxState,
  private val pendingSends: ChatPendingSends,
  private val runState: ChatRunState,
  private val sendDispatcher: ChatSendDispatcher,
  private val stops: ChatStopController,
  private val context: () -> ChatDeliveryContext,
  private val normalizeSessionKey: (String) -> String,
  private val awaitSettings: suspend (ChatCacheScope, String, String) -> Boolean,
  private val prepareSessionSend: suspend (String?, String, String) -> Boolean,
  private val captureRequestLease: (ChatCacheScope?) -> GatewaySession.RequestLease?,
  private val readHistory: suspend (ChatCacheScope, ChatComposerOwner) -> ChatHistory,
  private val refreshHistory: suspend (Set<String>) -> Unit,
  private val onUnavailable: () -> Unit,
  private val publish: (ChatDeliveryEvent) -> Unit,
  private val recoveryHistoryRetryDelayMs: Long = 750L,
) {
  private val scheduler =
    ChatDeliveryScheduler(
      scope = scope,
      enabled = commandOutbox != null,
      drain = ::flushOutboxPass,
    )

  fun requestFlush() = scheduler.requestFlush()

  /** Canonical proof can release a session's FIFO head; no network mutation is replayed here. */
  suspend fun confirmHistory(
    gateway: ChatCacheScope?,
    history: ChatHistory,
    ownerAgentId: String,
  ) {
    val gatewayId = gateway?.gatewayId ?: return
    if (journal.reconcileHistory(gatewayId, history, ownerAgentId)) {
      outboxState.refresh()
      if (context().healthy) requestFlush()
    }
  }

  suspend fun send(request: ChatDeliveryRequest): Boolean {
    val sendCacheScope = request.origin.connection
    val sendGatewayId = sendCacheScope?.gatewayId
    val capturedOwner = request.origin.owner
    val effectiveSessionKey = capturedOwner.sessionKey
    val sessionKey = request.sessionKey
    val text = request.text
    val thinking = request.thinking
    val attachments = request.attachments
    val idempotencyKey = request.idempotencyKey
    val stopRevision = request.stopRevision
    val sendLease = request.lease

    fun isCapturedOwnerCurrent() = context().owner == capturedOwner

    fun ownsCapturedUi() = request.origin.ownsUi(context())

    // Every send is journaled before the composer clears or any network attempt can lose
    // ownership; the durable row is the single recovery owner across process death.
    val journaled =
      when (commandOutbox) {
        null -> {
          if (!context().healthy) {
            publish(ChatDeliveryEvent.Error(ChatDeliveryFailure.GatewayUnavailable))
            return false
          }
          null
        }
        else -> {
          when (
            val admitted =
              journal.enqueue(
                gateway = sendCacheScope,
                sessionKey = effectiveSessionKey,
                text = text,
                thinking = thinking,
                attachments = attachments,
                ownerAgentId = capturedOwner.agentId,
                idempotencyKey = idempotencyKey,
                canEnqueue = { !stops.blocksSend(capturedOwner) && stops.revision(capturedOwner) == stopRevision },
              )
          ) {
            is ChatJournalAdmission.Queued -> {
              if (ownsCapturedUi()) publish(ChatDeliveryEvent.Error(null))
              admitted.item
            }
            is ChatJournalAdmission.Failed -> {
              if (ownsCapturedUi()) publish(ChatDeliveryEvent.Error(ChatDeliveryFailure.Admission(admitted.reason)))
              return false
            }
            ChatJournalAdmission.Revoked -> return false
          }
        }
      }
    if (stops.blocksSend(capturedOwner) || stops.revision(capturedOwner) != stopRevision) {
      if (journaled == null) return false
      // Enqueue may have been suspended while Stop parked the earlier queue snapshot.
      // Retain this late admission as a failed row instead of restoring duplicate input.
      journal.settle(journaled, ChatOutboxStatus.Failed, OUTBOX_CHAT_STOPPED_ERROR, expectedStatus = ChatOutboxStatus.Queued)
      return true
    }
    if (journaled != null && !ownsCapturedUi()) {
      // Restore the draft only when the still-queued row is atomically removed. A reconnect
      // flush may already own it; then the durable row remains the single input owner.
      return !journal.removeIfQueued(journaled)
    }
    if (journaled != null) {
      if (!context().healthy) {
        // Captured for reconnect: the queued bubble is visible and flush delivers it later.
        return true
      }
      // The startup recovery sweep flips every 'sending' row to delivery-unconfirmed. Claiming
      // only after it completes means the sweep can never hit this live dispatch; a failed
      // sweep leaves the row queued so reconnect flush owns delivery instead.
      val outbox = commandOutbox
      if (outbox == null || !outboxState.awaitRecovery()) {
        onUnavailable()
        outboxState.refresh()
        return true
      }
      if (journal.hasBacklog(requireNotNull(sendGatewayId), journaled)) {
        // An older row for this session is still queued or unresolved; a direct dispatch
        // would reorder the conversation, so the FIFO flush owns delivery.
        scheduler.requestFlush()
        return true
      }
      // Atomically claim the row for this direct dispatch: a vanished row (user delete) or a
      // concurrent flush claim must not lead to a second send of the same idempotency key.
      val claimed = journal.claim(journaled, resetRetry = true)
      outboxState.refresh()
      if (claimed == ChatJournalClaim.Unavailable) {
        // The claim could not be made durable, so the admitted row still has no dispatcher.
        // Hand delivery to the flush lane instead of reporting success with no active owner.
        scheduler.requestFlush()
        return true
      }
      if (claimed == ChatJournalClaim.NotClaimed) return true
      if (journaled.gatedEpoch != null && journaled.gatedEpoch != context().connection?.connectionGeneration) {
        // A reconnect landed between admission and this claim; command-shaped input never
        // auto-replays across connection epochs, so the claimed row parks for explicit retry.
        journal.settle(journaled, ChatOutboxStatus.Failed, OUTBOX_CONNECTION_CHANGED_ERROR)
        return true
      }
    }

    val runId = journaled?.id ?: idempotencyKey ?: UUID.randomUUID().toString()

    val optimisticMessage = optimisticUserMessage(runId = runId, text = text, attachments = attachments)
    pendingSends.record(
      ChatPendingSend(
        owner = capturedOwner,
        runId = runId,
        optimisticMessage = optimisticMessage,
      ),
    )

    // Durable admission can suspend while the user changes chats. Route the captured row, but
    // project it only into the exact owner generation that initiated the send.
    fun projectRunToCurrentOwner() {
      pendingSends.projection(runId)?.let(pendingSends::project)
    }
    if (ownsCapturedUi()) projectRunToCurrentOwner()

    fun settleProjectedRun(settledRunId: String) {
      runState.retire(settledRunId)
      pendingSends.clear(settledRunId)
      pendingSends.removeOptimistic(settledRunId)
      pendingSends.forgetReply(settledRunId)
    }

    suspend fun settleUnconfirmed(message: String?): Boolean {
      // A transmitted rejection or unexpected result cannot prove this key never ran.
      journal.settle(journaled, ChatOutboxStatus.Failed, OUTBOX_DELIVERY_UNCONFIRMED_ERROR)
      settleProjectedRun(runId)
      if (isCapturedOwnerCurrent()) publish(ChatDeliveryEvent.Error(ChatDeliveryFailure.Transport(message)))
      // The durable row owns the input; only a journal-less send restores the composer.
      return journaled != null
    }

    // Keep dispatch and settlement in the delivery scope. Leaving the Chat UI after a claim
    // must not strand Sending input or cancel persistence of its eventual response.
    val dispatch =
      scope.async {
        val result =
          sendDispatcher.send(
            ChatSendRequest(
              owner = capturedOwner,
              // The journaled wire key is also the identity recovery will use.
              sessionKey = journaled?.sessionKey ?: sessionKey,
              text = text,
              thinking = thinking,
              idempotencyKey = runId,
              attachments = attachments,
            ),
            sendLease,
            stopRevision,
          )
        when (result) {
          is ChatSendResult.Response -> {
            try {
              val ack = result.ack
              // Row transitions are durable state for the dispatching gateway and apply even when the
              // UI scope moved on mid-request; only UI updates below are scope-guarded. A terminal
              // failure ack proves transmission, not that this idempotency key never ran (a timeout ack
              // can outlive a still-admitted run), so the row parks for review instead of deleting.
              if (ack.isTerminalFailure) {
                journal.settle(journaled, ChatOutboxStatus.Failed, OUTBOX_DELIVERY_UNCONFIRMED_ERROR)
              } else {
                journal.settle(journaled, ChatOutboxStatus.Accepted, null)
                val ackRunId = ack.runId
                if (journaled != null && ackRunId != null && ackRunId != journaled.id) {
                  journal.recordAcknowledgement(journaled, ackRunId)
                }
              }
              val actualRunId = ack.runId ?: runId
              if (!ack.isTerminal) projectRunToCurrentOwner()
              if (actualRunId != runId) {
                pendingSends.transfer(runId, actualRunId, optimisticMessage)
              }
              if (!ack.isTerminal && !pendingSends.isPending(actualRunId)) {
                pendingSends.armHidden(actualRunId)
              }
              if (ack.isTerminal) {
                settleProjectedRun(actualRunId)
                if (ack.isTerminalSuccess) {
                  if (isCapturedOwnerCurrent()) {
                    publish(ChatDeliveryEvent.Idle)
                    refreshHistory(setOf(actualRunId))
                  }
                  true
                } else {
                  // Terminal timeout/error means the gateway did not accept a runnable turn.
                  // Surface failed acceptance instead of letting a cleared composer look successful.
                  if (isCapturedOwnerCurrent()) {
                    publish(ChatDeliveryEvent.Idle)
                    publish(ChatDeliveryEvent.Error(ChatDeliveryFailure.TerminalFailed))
                  }
                  // The parked row owns the input; restoring the draft would duplicate it.
                  journaled != null
                }
              } else {
                true
              }
            } catch (error: CancellationException) {
              throw error
            } catch (error: Throwable) {
              settleUnconfirmed(error.message)
            }
          }
          is ChatSendResult.NotEnqueued -> {
            // Only transport proof of non-enqueue allows later queue delivery. Stop instead
            // parks the original row; neither path restores a duplicate composer draft.
            if (journaled != null) {
              val stopped = result.message == OUTBOX_CHAT_STOPPED_ERROR
              journal.settle(journaled, if (stopped) ChatOutboxStatus.Failed else ChatOutboxStatus.Queued, result.message)
              settleProjectedRun(runId)
              if (!stopped && sendCacheScope == context().connection) onUnavailable()
              outboxState.refresh()
              true
            } else {
              settleProjectedRun(runId)
              if (isCapturedOwnerCurrent()) publish(ChatDeliveryEvent.Error(ChatDeliveryFailure.Transport(result.message)))
              false
            }
          }
          is ChatSendResult.Rejected -> settleUnconfirmed(result.message)
          ChatSendResult.OutcomeUnknown -> {
            // Keep live ownership while canonical history can still resolve a lost ACK.
            journal.settle(journaled, ChatOutboxStatus.Accepted, null)
            if (!isCapturedOwnerCurrent()) {
              settleProjectedRun(runId)
              return@async true
            }
            projectRunToCurrentOwner()
            pendingSends.markUnknown(runId)
            if (context().healthy) {
              refreshHistory(setOf(runId))
            }
            true
          }
          is ChatSendResult.Failed -> settleUnconfirmed(result.message)
        }
      }
    return dispatch.await()
  }

  private fun optimisticUserMessage(
    runId: String,
    text: String,
    attachments: List<OutgoingAttachment>,
  ): ChatMessage {
    val userContent =
      buildList {
        add(ChatMessageContent(type = "text", text = text))
        for (att in attachments) {
          add(
            ChatMessageContent(
              type = att.type,
              mimeType = att.mimeType,
              fileName = att.fileName,
              base64 = att.base64,
            ),
          )
        }
      }
    return ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = userContent,
      timestampMs = System.currentTimeMillis(),
      idempotencyKey = "$runId:user",
    )
  }

  private suspend fun flushOutboxPass() {
    val outbox = commandOutbox ?: return
    // The unscoped recovery sweep must succeed before this process claims a row. A transient
    // storage failure stays retryable, but never lets younger queued work bypass an ambiguous send.
    if (!outboxState.awaitRecovery()) {
      onUnavailable()
      outboxState.refresh()
      return
    }
    var flushedAny = false
    try {
      // The whole flush is bound to one gateway scope; a connection switch mid-flush stops it
      // and the next health transition flushes under the new scope.
      val flushScope = context().connection ?: return
      runCatching { outbox.expireStale(flushScope.gatewayId, System.currentTimeMillis()) }
      outboxState.refresh()
      while (context().healthy && context().connection == flushScope) {
        val rows = runCatching { outbox.load(flushScope.gatewayId) }.getOrDefault(emptyList())
        if (journal.parkStaleCommands(rows, flushScope)) {
          outboxState.refresh()
          continue
        }
        val next = journal.nextFlushable(rows) ?: break
        when (sendOutboxItem(outbox, next, flushScope)) {
          OutboxSendOutcome.Sent -> flushedAny = true
          OutboxSendOutcome.Continue -> {}
          OutboxSendOutcome.Stop -> break
        }
      }
      // Accepted rows from an earlier process have no live run ownership; prove them against
      // canonical history now so restarts either retire them or surface them for review. The
      // second pass (after a short delay) both confirms turns whose transcript write lagged the
      // ACK and provides the second sighting that parks genuinely lost sends. Confirmations can
      // release queued successors in the same session, so they request a rerun of the drain.
      if (reconcileOrphanAcceptedRows(outbox, flushScope) > 0) {
        delay(recoveryHistoryRetryDelayMs)
        if (context().healthy && context().connection == flushScope) {
          reconcileOrphanAcceptedRows(outbox, flushScope)
        }
      }
    } finally {
      outboxState.refresh()
      if (flushedAny) {
        // Durable history replaces the queued bubbles; reconciliation matches by idempotency key.
        refreshHistory(emptySet())
      }
    }
  }

  /** Reconciles orphaned accepted rows against per-session history; returns how many remain. */
  private suspend fun reconcileOrphanAcceptedRows(
    outbox: ChatCommandOutbox,
    flushScope: ChatCacheScope,
  ): Int {
    val rows = runCatching { outbox.load(flushScope.gatewayId) }.getOrDefault(emptyList())
    val orphanSessions =
      rows
        .filter { it.status == ChatOutboxStatus.Accepted && !journal.isLocallyOwned(it) }
        .mapNotNull { row ->
          val agentId = row.ownerAgentId ?: resolveAgentIdFromMainSessionKey(row.sessionKey) ?: return@mapNotNull null
          ChatComposerOwner(flushScope.gatewayId, agentId, normalizeSessionKey(row.sessionKey))
        }.toSet()
    if (orphanSessions.isEmpty()) return 0
    var changed = false
    for (owner in orphanSessions) {
      if (!context().healthy || context().connection != flushScope) break
      val history =
        try {
          readHistory(flushScope, owner)
        } catch (err: CancellationException) {
          throw err
        } catch (_: Throwable) {
          // Keep the rows accepted; the next flush or history apply reconciles them.
          continue
        }
      changed = journal.reconcileHistory(flushScope.gatewayId, history, owner.agentId) || changed
    }
    if (changed) {
      outboxState.refresh()
      // A confirmed row may have been the head blocking queued successors in its session;
      // the level-triggered request makes the drain run another pass so released rows send.
      scheduler.requestDrain()
    }
    return runCatching { outbox.load(flushScope.gatewayId) }
      .getOrDefault(emptyList())
      .count { it.status == ChatOutboxStatus.Accepted && !journal.isLocallyOwned(it) }
  }

  // Sent: acknowledged, awaiting canonical history proof. Continue: row vanished or failed after a gateway response.
  // Stop: transport or persistence state cannot safely advance to younger work.
  private enum class OutboxSendOutcome { Sent, Continue, Stop }

  private enum class GatewayResponseState { Received, Unknown }

  private sealed interface OutboxSendResult {
    data class Accepted(
      val runId: String,
    ) : OutboxSendResult

    /** The request never entered the socket queue, so reconnect may retry it automatically. */
    data class NotDispatched(
      val error: String,
    ) : OutboxSendResult

    /** Dispatch may have succeeded, so only explicit user intent may retry the command. */
    data class DeliveryUnconfirmed(
      val gatewayResponse: GatewayResponseState,
    ) : OutboxSendResult

    /** The canonical alias now resolves to a different agent than the one captured at admission. */
    data object OwnerChanged : OutboxSendResult
  }

  private suspend fun sendOutboxItem(
    outbox: ChatCommandOutbox,
    item: ChatOutboxItem,
    flushScope: ChatCacheScope,
  ): OutboxSendOutcome {
    val sendLease = captureRequestLease(flushScope)
    val ownerAgentId = item.ownerAgentId ?: resolveAgentIdFromMainSessionKey(item.sessionKey)
    if (ownerAgentId == null) {
      // Rows without a durable owner must stay visible for explicit review;
      // dispatching now would bind them to whichever default agent happens to be current.
      val parked = journal.update(item, ChatOutboxStatus.Failed, OUTBOX_OWNER_CHANGED_ERROR)
      if (parked == null) {
        outboxState.requireRecovery()
        onUnavailable()
        return OutboxSendOutcome.Stop
      }
      outboxState.refresh()
      return OutboxSendOutcome.Continue
    }
    // Reconnect flushes share the live-send settings boundary. Claiming before this wait
    // could durably dispatch a queued turn against the previous model or thinking state. Use
    // the row's owner because the visible chat may switch while this queued turn is waiting.
    if (!awaitSettings(flushScope, normalizeSessionKey(item.sessionKey), ownerAgentId)) {
      return OutboxSendOutcome.Stop
    }
    val sendOwner = ChatComposerOwner(flushScope.gatewayId, ownerAgentId, item.sessionKey)
    if (stops.blocksSend(sendOwner)) return OutboxSendOutcome.Stop
    val stopRevision = stops.revision(sendOwner)
    if (!prepareSessionSend(flushScope.gatewayId, item.sessionKey, ownerAgentId)) return OutboxSendOutcome.Stop
    if (stops.blocksSend(sendOwner) || stops.revision(sendOwner) != stopRevision) return OutboxSendOutcome.Stop
    // Only a durable claim supplies the Sending snapshot used by all later transitions.
    val claimed = journal.claim(item)
    outboxState.refresh()
    if (claimed == ChatJournalClaim.Unavailable) {
      // Never bypass an older row when its claim could not be made durable.
      onUnavailable()
      return OutboxSendOutcome.Stop
    }
    if (claimed !is ChatJournalClaim.Claimed) return OutboxSendOutcome.Continue
    val sending = claimed.item
    // Bytes are loaded once per claimed row; a storage failure parks it instead of sending
    // a message without the attachments the user staged with it.
    val attachments =
      try {
        journal.loadAttachments(sending)
      } catch (err: CancellationException) {
        throw err
      } catch (_: Throwable) {
        val parked = journal.update(sending, ChatOutboxStatus.Failed, "attachments unavailable")
        if (parked == null) outboxState.requireRecovery()
        outboxState.refresh()
        return if (parked == null) {
          onUnavailable()
          OutboxSendOutcome.Stop
        } else {
          OutboxSendOutcome.Continue
        }
      }
    return when (val result = attemptOutboxSend(outbox, sending, flushScope.gatewayId, ownerAgentId, attachments, stopRevision, sendLease)) {
      is OutboxSendResult.Accepted -> {
        // Ack received: keep the row as accepted until canonical history proves the user turn
        // persisted; the started ACK alone is not durable proof (issue #86946 tracks the gap).
        if (result.runId != sending.id) journal.recordAcknowledgement(sending, result.runId)
        val persisted = journal.update(sending, ChatOutboxStatus.Accepted, null)
        if (persisted == null) outboxState.requireRecovery()
        outboxState.refresh()
        if (persisted == null) {
          // The accepted row is still Sending; the re-armed recovery sweep parks it once
          // storage recovers, and canonical history proof can still retire it later.
          onUnavailable()
          OutboxSendOutcome.Stop
        } else {
          // A zero update means a concurrent delete raced the ack; history still owns proof.
          if (persisted > 0) {
            adoptFlushedSend(
              item = sending,
              attachments = attachments,
              ackRunId = result.runId,
              gatewayId = flushScope.gatewayId,
              ownerAgentId = ownerAgentId,
            )
          }
          OutboxSendOutcome.Sent
        }
      }
      is OutboxSendResult.NotDispatched -> {
        if (result.error == OUTBOX_CHAT_STOPPED_ERROR) {
          val parked = journal.update(sending, ChatOutboxStatus.Failed, OUTBOX_CHAT_STOPPED_ERROR)
          if (parked == null) outboxState.requireRecovery()
          outboxState.refresh()
          return OutboxSendOutcome.Stop
        }
        // This frame never entered the socket queue, so reconnect may retry it safely.
        val requeued = journal.update(sending, ChatOutboxStatus.Queued, result.error)
        if (requeued == null) outboxState.requireRecovery()
        outboxState.refresh()
        onUnavailable()
        OutboxSendOutcome.Stop
      }
      OutboxSendResult.OwnerChanged -> {
        val parked = journal.update(sending, ChatOutboxStatus.Failed, OUTBOX_OWNER_CHANGED_ERROR)
        if (parked == null) outboxState.requireRecovery()
        outboxState.refresh()
        if (parked == null) {
          onUnavailable()
          OutboxSendOutcome.Stop
        } else {
          OutboxSendOutcome.Continue
        }
      }
      is OutboxSendResult.DeliveryUnconfirmed -> {
        // Every transmitted failure is ambiguous: gateway error responses can be cached after
        // agent dispatch, and gateway dedupe is process-local and time-bounded.
        val persisted =
          journal.update(
            sending,
            ChatOutboxStatus.Failed,
            OUTBOX_DELIVERY_UNCONFIRMED_ERROR,
          )
        if (persisted == null) outboxState.requireRecovery()
        outboxState.refresh()
        when {
          persisted == null -> {
            // The ambiguous row is still Sending. Stop before younger work; the re-armed
            // recovery sweep will park it after storage becomes available again.
            onUnavailable()
            OutboxSendOutcome.Stop
          }
          result.gatewayResponse == GatewayResponseState.Unknown -> {
            onUnavailable()
            OutboxSendOutcome.Stop
          }
          else -> {
            // Sending is delivery-owned and Retry only transitions Failed. A zero update can
            // only mean a concurrent delete removed the claimed row; a received response makes
            // either zero or a durable Failed transition safe to advance past.
            OutboxSendOutcome.Continue
          }
        }
      }
    }
  }

  /**
   * Adopts run ownership for a flush-dispatched row in the visible session so streaming, the
   * pending spinner, and reply reconciliation behave exactly like a direct send. The optimistic
   * bubble replaces the queued row bubble until canonical history carries the turn.
   */
  private fun adoptFlushedSend(
    item: ChatOutboxItem,
    attachments: List<OutgoingAttachment>,
    ackRunId: String,
    gatewayId: String,
    ownerAgentId: String,
  ) {
    val runId = item.id
    if (pendingSends.isLocallyOwned(runId) || pendingSends.isLocallyOwned(ackRunId)) return
    val optimistic = optimisticUserMessage(runId = runId, text = item.text, attachments = attachments)
    val projection =
      ChatPendingSend(
        owner =
          ChatComposerOwner(
            gatewayStableId = gatewayId,
            agentId = ownerAgentId,
            sessionKey = normalizeSessionKey(item.sessionKey),
          ),
        runId = runId,
        optimisticMessage = optimistic,
      )
    pendingSends.record(projection)
    pendingSends.armHidden(runId)
    pendingSends.project(projection)
    // Chat events for this turn arrive under the acknowledged run id; mirroring the direct
    // path's ownership transfer keeps the live run from looking foreign and timing out.
    if (ackRunId != runId) pendingSends.transfer(runId, ackRunId, optimistic)
  }

  private suspend fun attemptOutboxSend(
    outbox: ChatCommandOutbox,
    item: ChatOutboxItem,
    gatewayId: String,
    ownerAgentId: String,
    attachments: List<OutgoingAttachment>,
    stopRevision: Long,
    sendLease: GatewaySession.RequestLease?,
  ): OutboxSendResult {
    return try {
      val queuedSessionKey = normalizeSessionKey(item.sessionKey)
      val canonicalAgentId = resolveAgentIdFromMainSessionKey(queuedSessionKey)
      if (canonicalAgentId != null && canonicalAgentId != ownerAgentId) {
        return OutboxSendResult.OwnerChanged
      }
      if (queuedSessionKey != item.sessionKey) {
        // A row captured under the pre-hello "main" alias resolves exactly once, against the
        // canonical main session active at first dispatch. Pinning it before the request means
        // a later default-agent change can never redirect this input on a retry, so a pin
        // that cannot be made durable must stop the dispatch while the row is still safe.
        val pinned =
          try {
            outbox.pinSessionKey(item.id, queuedSessionKey)
            true
          } catch (err: CancellationException) {
            throw err
          } catch (_: Throwable) {
            false
          }
        if (!pinned) return OutboxSendResult.NotDispatched("could not pin the delivery session")
      }
      // Android only knows the active session's selected model. Unknown queued sessions fail
      // open, preserving the thinking level captured when they were enqueued.
      val thinking =
        if (
          context().let { queuedSessionKey == it.sessionKey && !it.supportsThinking }
        ) {
          "off"
        } else {
          item.thinkingLevel
        }
      val result =
        sendDispatcher.send(
          ChatSendRequest(
            owner = ChatComposerOwner(gatewayId, ownerAgentId, item.sessionKey),
            sessionKey = queuedSessionKey,
            text = item.text,
            thinking = thinking,
            idempotencyKey = item.id,
            attachments = attachments,
          ),
          sendLease,
          stopRevision,
        )
      when (result) {
        is ChatSendResult.Response -> {
          val ack = result.ack
          when (ack.normalizedStatus) {
            "ok", "started", "in_flight" ->
              if (ack.runId.isNullOrBlank()) {
                OutboxSendResult.DeliveryUnconfirmed(GatewayResponseState.Received)
              } else {
                OutboxSendResult.Accepted(ack.runId)
              }
            else -> OutboxSendResult.DeliveryUnconfirmed(GatewayResponseState.Received)
          }
        }
        is ChatSendResult.NotEnqueued -> OutboxSendResult.NotDispatched(result.message ?: "send failed")
        is ChatSendResult.Rejected -> OutboxSendResult.DeliveryUnconfirmed(GatewayResponseState.Received)
        ChatSendResult.OutcomeUnknown, is ChatSendResult.Failed ->
          OutboxSendResult.DeliveryUnconfirmed(GatewayResponseState.Unknown)
      }
    } catch (error: CancellationException) {
      // Teardown leaves the durable claim for startup recovery; never turn it into a retry.
      throw error
    } catch (_: Throwable) {
      OutboxSendResult.DeliveryUnconfirmed(GatewayResponseState.Unknown)
    }
  }
}
