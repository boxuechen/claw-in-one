package ai.openclaw.app.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ChatPendingSend(
  val owner: ChatComposerOwner,
  val runId: String,
  val optimisticMessage: ChatMessage,
)

internal data class ChatPendingSendContext(
  val connection: ChatCacheScope?,
  val owner: ChatComposerOwner?,
  val sessionKey: String,
  val healthy: Boolean,
  val advertisedRunIds: Set<String>,
)

internal enum class ChatPendingHistoryResult { Applied, Superseded, Unavailable }

internal sealed interface ChatPendingSendEvent {
  data object Changed : ChatPendingSendEvent

  data object Projected : ChatPendingSendEvent

  data object Idle : ChatPendingSendEvent

  data object ReplyTimedOut : ChatPendingSendEvent

  data object ConfirmationTimedOut : ChatPendingSendEvent

  data class Streaming(
    val runId: String,
    val text: String,
  ) : ChatPendingSendEvent
}

internal data class ChatPendingHistoryProjection(
  val optimisticMessages: List<ChatMessage>,
  val completionSettled: Boolean,
)

/**
 * Ephemeral send/reply ownership and watchdogs; durable delivery and permission stay outside.
 * Canonical user rows retire optimistic echoes, not unresolved replies. Background projections
 * survive selection changes so returning to a Chat restores only its own work.
 */
internal class ChatPendingSends(
  private val scope: CoroutineScope,
  private val lock: Any,
  private val transcript: ChatTranscript,
  private val runState: ChatRunState,
  private val context: () -> ChatPendingSendContext,
  private val refreshHistory: suspend (String, Long, Set<String>) -> ChatPendingHistoryResult,
  private val parkUnconfirmed: suspend (String) -> Unit,
  private val publish: (ChatPendingSendEvent) -> Unit,
  private val timeoutMs: Long = 120_000,
  private val recoveryDelayMs: Long = 750,
) {
  private val pending = mutableSetOf<String>()
  private val disconnected = mutableSetOf<String>()
  private val timedOut = mutableSetOf<String>()
  private val terminalWithoutReply = mutableSetOf<String>()
  private val unknown = mutableSetOf<String>()
  private val optimistic = mutableMapOf<String, ChatMessage>()
  private val replies = mutableMapOf<String, ChatMessage>()
  private val projections = mutableMapOf<String, ChatPendingSend>()
  private val timers = mutableMapOf<String, Job>()
  private var recoveryGeneration = -1L
  private var recovery: Job? = null
  private var latestInFlightRunId: String? = null

  val visibleRunIds: Set<String> get() = synchronized(lock) { pending.toSet() }
  val replyRunIds: Set<String> get() = synchronized(lock) { replies.keys.toSet() }
  val optimisticMessages: List<ChatMessage> get() = synchronized(lock) { optimistic.values.toList() }
  val recoveryRunIds: Set<String> get() = synchronized(lock) { pending + optimistic.keys + replies.keys }
  val locallyOwnedRunIds: Set<String> get() = synchronized(lock) { pending + projections.keys + unknown + replies.keys }

  fun projection(runId: String): ChatPendingSend? = synchronized(lock) { projections[runId] }

  fun isPending(runId: String): Boolean = synchronized(lock) { runId in pending }

  fun hasReply(runId: String): Boolean = synchronized(lock) { runId in replies }

  fun isVisibleOwned(runId: String): Boolean = synchronized(lock) { runId in pending || runId in replies }

  fun isLocallyOwned(runId: String): Boolean = synchronized(lock) { runId in pending || runId in projections || runId in unknown || runId in replies }

  fun consumeTimeout(runId: String): Boolean = synchronized(lock) { timedOut.remove(runId) }

  fun markUnknown(runId: String): Unit =
    synchronized(lock) {
      unknown.add(runId)
    }

  fun markTerminalWithoutReply(runId: String): Unit =
    synchronized(lock) {
      terminalWithoutReply.add(runId)
    }

  fun forgetReply(runId: String): Unit =
    synchronized(lock) {
      replies.remove(runId)
    }

  fun record(send: ChatPendingSend): Unit = synchronized(lock) { projections[send.runId] = send }

  fun clockKey(runId: String): String =
    synchronized(lock) {
      projections[runId]?.optimisticMessage?.id ?: optimistic[runId]?.id ?: replies[runId]?.id ?: runId
    }

  fun rememberDisconnected(): Unit =
    synchronized(lock) {
      disconnected.addAll(pending)
    }

  fun restoreDisconnected(): Unit =
    synchronized(lock) {
      val restored = disconnected.toSet()
      pending.addAll(restored)
      disconnected.clear()
      restored.forEach(::armVisible)
      publish(ChatPendingSendEvent.Changed)
    }

  fun project(send: ChatPendingSend): Unit =
    synchronized(lock) {
      if (send.owner != context().owner) {
        unproject(send.runId)
        return
      }
      val id = send.runId
      if (id in optimistic && id in pending) return
      optimistic[id] = send.optimisticMessage
      replies[id] = send.optimisticMessage
      transcript.appendOptimistic(send.optimisticMessage)
      pending.add(id)
      armVisible(id)
      publish(ChatPendingSendEvent.Projected)
      publish(ChatPendingSendEvent.Changed)
    }

  fun restoreForCurrentOwner(): Unit =
    synchronized(lock) {
      val owner = context().owner ?: return
      projections.values
        .filter { it.owner == owner }
        .sortedBy { it.runId }
        .forEach(::project)
    }

  private fun unproject(runId: String) {
    timers.remove(runId)?.cancel()
    removeOptimistic(runId)
    replies.remove(runId)
    disconnected.remove(runId)
    pending.remove(runId)
    runState.clearUnownedNonterminal(runId, advertised = runId in context().advertisedRunIds)
    idleIfSettled()
    if (runId in projections) armHidden(runId)
    publish(ChatPendingSendEvent.Changed)
  }

  fun clear(
    runId: String,
    publishRunState: Boolean = true,
  ): Unit =
    synchronized(lock) {
      projections.remove(runId)
      timers.remove(runId)?.cancel()
      unknown.remove(runId)
      disconnected.remove(runId)
      pending.remove(runId)
      runState.clearUnownedNonterminal(runId, advertised = runId in context().advertisedRunIds)
      if (publishRunState) publish(ChatPendingSendEvent.Changed)
    }

  fun idleIfSettled(): Unit =
    synchronized(lock) {
      if (pending.isEmpty()) publish(ChatPendingSendEvent.Idle)
    }

  fun clearAll(
    clearOptimisticMessages: Boolean = true,
    preserveDisconnectedOwnership: Boolean = false,
    clearRunTelemetry: Boolean = true,
  ): Unit =
    synchronized(lock) {
      timers.values.forEach { it.cancel() }
      timers.clear()
      if (clearOptimisticMessages) {
        recovery?.cancel()
        recoveryGeneration = -1
        recovery = null
        optimistic.clear()
        replies.clear()
        timedOut.clear()
        terminalWithoutReply.clear()
        unknown.clear()
      }
      if (!preserveDisconnectedOwnership) disconnected.clear()
      pending.clear()
      if (clearRunTelemetry) runState.clear()
      projections.keys.toList().forEach(::armHidden)
      publish(ChatPendingSendEvent.Changed)
    }

  fun removeOptimistic(runId: String): Unit =
    synchronized(lock) {
      val message = optimistic.remove(runId) ?: return
      transcript.removeOptimistic(message.id)
    }

  fun transfer(
    oldRunId: String,
    newRunId: String,
    fallbackMessage: ChatMessage,
    messageIdempotencyKey: String? = fallbackMessage.idempotencyKey,
    publishRunState: Boolean = true,
  ): Unit =
    synchronized(lock) {
      if (oldRunId == newRunId) return
      val projection = projections.remove(oldRunId)
      val echo = optimistic.remove(oldRunId)
      val reply = replies.remove(oldRunId)
      val wasPending = oldRunId in pending
      val terminal = terminalWithoutReply.remove(oldRunId)
      unknown.remove(oldRunId)
      timers.remove(oldRunId)?.cancel()
      val original = echo ?: reply ?: fallbackMessage
      // Gateway run identity and the client key on the user message are independent.
      val rekeyed = original.copy(idempotencyKey = messageIdempotencyKey)
      if (echo != null) optimistic[newRunId] = rekeyed
      if (reply != null) replies[newRunId] = rekeyed
      if (terminal) terminalWithoutReply.add(newRunId)
      transcript.rekeyOptimistic(original.id, rekeyed)
      val wasProjected = echo != null || reply != null || wasPending
      disconnected.remove(oldRunId)
      pending.remove(oldRunId)
      if (wasProjected) pending.add(newRunId)
      runState.transfer(oldRunId, newRunId)
      if (wasProjected) armVisible(newRunId)
      if (projection != null) {
        projections[newRunId] = projection.copy(runId = newRunId, optimisticMessage = rekeyed)
        if (!wasProjected) armHidden(newRunId)
      }
      if (publishRunState) publish(ChatPendingSendEvent.Changed)
    }

  /** Called inside the shared transcript-publication boundary, before canonical rows publish. */
  fun beforeHistory(
    history: ChatHistory,
    runIdsToReconcile: Set<String>,
    markCompletedTranscript: Boolean,
  ): ChatPendingHistoryProjection =
    synchronized(lock) {
      transferLostAck(history)
      resolveReplies(history.messages)
      val snapshotRunId =
        history.inFlightRun
          ?.runId
          ?.trim()
          ?.takeIf(String::isNotEmpty)
      latestInFlightRunId = snapshotRunId
      val projectedIds = runIdsToReconcile.filterTo(mutableSetOf()) { it in optimistic }
      val retained = retainUnmatchedOptimisticMessages(history.messages, optimistic.values).toSet()
      optimistic.entries.removeAll { it.value !in retained }
      if (snapshotRunId == null) {
        projectedIds
          .filterNot { it in unknown && it in replies }
          .filterNot { it in optimistic }
          .forEach { clear(it, publishRunState = false) }
      } else {
        runIdsToReconcile
          .filter { it != snapshotRunId && it !in optimistic && it !in replies }
          .forEach { clear(it, publishRunState = false) }
      }
      ChatPendingHistoryProjection(
        optimistic.values.toList(),
        markCompletedTranscript && runIdsToReconcile.none { it in replies } && history.sessionInfo?.endedAt != null,
      )
    }

  /** Complete only after the coordinator successfully publishes this same history snapshot. */
  fun afterHistory(
    history: ChatHistory,
    runIdsToReconcile: Set<String>,
    runIdsOwnedAfterRequest: Set<String>,
  ): Unit =
    synchronized(lock) {
      if (history.inFlightRun == null) {
        runIdsToReconcile.filterNot { it in unknown && it in replies }.forEach { clear(it, publishRunState = false) }
      }
      idleIfSettled()
      adoptInFlight(history, runIdsOwnedAfterRequest)
      publish(ChatPendingSendEvent.Changed)
    }

  private fun transferLostAck(history: ChatHistory) {
    val snapshotRunId =
      history.inFlightRun
        ?.runId
        ?.trim()
        ?.takeIf(String::isNotEmpty) ?: return
    if (snapshotRunId in replies) return
    val localRunId = (pending + disconnected).singleOrNull() ?: return
    if (localRunId !in unknown) return
    val message = replies[localRunId] ?: return
    val key = message.idempotencyKey?.trim()
    val content = messageContentIdentityKey(message)
    val persisted =
      history.messages.firstOrNull {
        (it.idempotencyKey?.trim() == key || it.idempotencyKey?.trim() == "$snapshotRunId:user") && messageContentIdentityKey(it) == content
      } ?: return
    transfer(localRunId, snapshotRunId, message, persisted.idempotencyKey, publishRunState = false)
  }

  private fun resolveReplies(incoming: List<ChatMessage>) {
    val resolved =
      replies
        .filter { (id, message) ->
          val userIndex = incoming.indexOfFirst { incomingMessageConsumesOptimistic(it, message) }
          userIndex >= 0 &&
            (
              id in terminalWithoutReply ||
                incoming
                  .drop(userIndex + 1)
                  .takeWhile { it.role.trim().lowercase() != "user" }
                  .any { it.role.trim().lowercase() == "assistant" }
            )
        }.keys
        .toList()
    resolved.forEach(replies::remove)
    resolved.forEach(terminalWithoutReply::remove)
  }

  private fun adoptInFlight(
    history: ChatHistory,
    runIdsOwnedAfterRequest: Set<String>,
  ) {
    val run = history.inFlightRun ?: return
    val id = run.runId.trim().takeIf(String::isNotEmpty) ?: return
    if (runIdsOwnedAfterRequest.isNotEmpty() && id !in runIdsOwnedAfterRequest) return
    if (pending.isNotEmpty() && id !in pending) return
    if (pending.isEmpty() && replies.isNotEmpty() && id !in replies) return
    pending.add(id)
    armVisible(id)
    if (run.text.isNotEmpty()) publish(ChatPendingSendEvent.Streaming(id, run.text))
  }

  fun armHidden(runId: String): Unit =
    synchronized(lock) {
      timers.remove(runId)?.cancel()
      val job =
        scope.launch(start = CoroutineStart.LAZY) {
          delay(timeoutMs)
          val mine = currentCoroutineContext()[Job]
          val expired =
            synchronized(lock) {
              if (mine?.isActive != true || timers[runId] !== mine) return@synchronized false
              timers.remove(runId)
              runId !in pending && projections.remove(runId) != null
            }
          if (expired) parkUnconfirmed(runId)
        }
      timers[runId] = job
      job.start()
    }

  private fun armVisible(runId: String) {
    timers.remove(runId)?.cancel()
    val job =
      scope.launch(start = CoroutineStart.LAZY) {
        delay(timeoutMs)
        val mine = currentCoroutineContext()[Job]
        val before =
          synchronized(lock) {
            if (timers[runId] !== mine) return@launch
            Triple(context(), transcript.generation, transcript.publicationSequence)
          }
        val result = refreshHistory(before.first.sessionKey, before.second, emptySet())
        val expired =
          synchronized(lock) {
            if (mine?.isActive != true || timers[runId] !== mine) return@synchronized false
            val now = context()
            if (now.connection != before.first.connection || now.owner != before.first.owner || now.sessionKey != before.first.sessionKey) {
              unproject(runId)
              return@synchronized false
            }
            val fresh = result == ChatPendingHistoryResult.Applied || transcript.publicationSequence > before.third
            timers.remove(runId) // Completion must not cancel itself before durable parking.
            if ((fresh && latestInFlightRunId == runId) || (!fresh && result == ChatPendingHistoryResult.Superseded)) {
              armVisible(runId)
              return@synchronized false
            }
            val unresolved = runId in replies
            clear(runId)
            idleIfSettled()
            if (!unresolved) return@synchronized false
            removeOptimistic(runId)
            replies.remove(runId)
            terminalWithoutReply.remove(runId)
            timedOut.add(runId)
            publish(ChatPendingSendEvent.ReplyTimedOut)
            true
          }
        if (expired) parkUnconfirmed(runId)
      }
    timers[runId] = job
    job.start()
  }

  fun scheduleRecovery(
    sessionKey: String,
    generation: Long,
    runIds: Set<String>,
  ): Unit =
    synchronized(lock) {
      val ids = runIds + replies.keys
      if (ids.isEmpty() || (ids.none { it in pending } && ids.none { it in replies })) return
      if (generation < recoveryGeneration) return
      recovery?.cancel()
      recoveryGeneration = generation
      val connection = context().connection
      val job =
        scope.launch(start = CoroutineStart.LAZY) {
          val mine = currentCoroutineContext()[Job]

          fun isCurrent(): Boolean =
            synchronized(lock) {
              val now = context()
              mine?.isActive == true && recovery === mine && connection == now.connection && now.healthy && transcript.isCurrentLoad(sessionKey, generation)
            }
          delay(recoveryDelayMs)
          if (!isCurrent()) return@launch
          refreshHistory(sessionKey, generation, ids)
          if (!isCurrent() || synchronized(lock) { ids.any { it in pending } || ids.none { it in replies } }) return@launch
          delay(timeoutMs - recoveryDelayMs)
          if (!isCurrent()) return@launch
          refreshHistory(sessionKey, generation, ids)
          val expired =
            synchronized(lock) {
              if (!isCurrent() || ids.any { it in pending }) return@synchronized emptyList()
              val remaining = ids.filter { it in replies }
              if (remaining.isNotEmpty()) {
                remaining.forEach(::removeOptimistic)
                remaining.forEach(replies::remove)
                remaining.forEach(terminalWithoutReply::remove)
                publish(ChatPendingSendEvent.ConfirmationTimedOut)
              }
              remaining
            }
          for (id in expired) parkUnconfirmed(id)
        }
      recovery = job
      job.start()
    }
}
