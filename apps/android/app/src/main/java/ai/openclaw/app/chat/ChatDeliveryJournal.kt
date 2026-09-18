package ai.openclaw.app.chat

import ai.openclaw.app.resolveAgentIdFromMainSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal enum class ChatJournalFailure { Unavailable, AttachmentInvalid, StorageUnavailable, QueueFull, AttachmentsTooLarge, StorageFull }

internal sealed interface ChatJournalAdmission {
  data class Queued(
    val item: ChatOutboxItem,
  ) : ChatJournalAdmission

  data class Failed(
    val reason: ChatJournalFailure,
  ) : ChatJournalAdmission

  data object Revoked : ChatJournalAdmission
}

internal sealed interface ChatJournalClaim {
  data class Claimed(
    val item: ChatOutboxItem,
  ) : ChatJournalClaim

  data object NotClaimed : ChatJournalClaim

  data object Unavailable : ChatJournalClaim
}

/** Durable input and delivery evidence, never a transport, permission, or presentation owner. */
internal class ChatDeliveryJournal(
  private val outbox: ChatCommandOutbox?,
  private val state: ChatOutboxState,
  private val admissionMutex: Mutex,
  private val normalizeSessionKey: (String) -> String,
  private val locallyOwnedRun: (String) -> Boolean,
  private val onStorageFailure: () -> Unit,
  private val resumeDelivery: () -> Unit,
) {
  // Process hints use the store's existing attempt identity, not a second delivery generation.
  private data class Attempt(
    val id: String,
    val version: Int,
  )

  private fun ChatOutboxItem.attempt() = Attempt(id, attemptVersion)

  private val acknowledgements = ConcurrentHashMap<Attempt, String>()
  private val unconfirmedSightings = ConcurrentHashMap<Attempt, Int>()

  fun recordAcknowledgement(
    row: ChatOutboxItem,
    runId: String,
  ) {
    if (row.id != runId) acknowledgements[row.attempt()] = runId
  }

  fun isLocallyOwned(row: ChatOutboxItem): Boolean = locallyOwnedRun(row.id) || acknowledgements[row.attempt()]?.let(locallyOwnedRun) == true

  private fun forget(row: ChatOutboxItem) {
    acknowledgements.remove(row.attempt())
    unconfirmedSightings.remove(row.attempt())
  }

  private suspend fun storageFailed() {
    state.requireRecovery()
    onStorageFailure()
  }

  private data class DeliveryOwner(
    val sessionKey: String,
    val agentId: String,
  )

  private fun owner(row: ChatOutboxItem): DeliveryOwner? {
    val agent = row.ownerAgentId ?: resolveAgentIdFromMainSessionKey(row.sessionKey) ?: return null
    return DeliveryOwner(normalizeSessionKey(row.sessionKey), agent.trim().lowercase())
  }

  private fun unresolved(row: ChatOutboxItem): Boolean =
    when (row.status) {
      ChatOutboxStatus.Queued, ChatOutboxStatus.Sending -> true
      ChatOutboxStatus.Accepted -> !isLocallyOwned(row)
      ChatOutboxStatus.Failed -> false
    }

  suspend fun enqueue(
    gateway: ChatCacheScope?,
    sessionKey: String,
    text: String,
    thinking: String,
    attachments: List<OutgoingAttachment>,
    ownerAgentId: String,
    idempotencyKey: String?,
    canEnqueue: () -> Boolean,
  ): ChatJournalAdmission {
    val store = outbox ?: return ChatJournalAdmission.Failed(ChatJournalFailure.Unavailable)
    if (gateway == null) return ChatJournalAdmission.Failed(ChatJournalFailure.Unavailable)
    val payloads =
      try {
        attachments.map { attachment ->
          OutboxAttachmentPayload(attachment.type, attachment.mimeType, attachment.fileName, Base64.getDecoder().decode(attachment.base64))
        }
      } catch (_: IllegalArgumentException) {
        return ChatJournalAdmission.Failed(ChatJournalFailure.AttachmentInvalid)
      }
    val result =
      try {
        admissionMutex.withLock {
          if (!canEnqueue()) return ChatJournalAdmission.Revoked
          store.enqueue(
            gatewayId = gateway.gatewayId,
            sessionKey = sessionKey,
            text = text,
            thinkingLevel = thinking,
            nowMs = System.currentTimeMillis(),
            attachments = payloads,
            gatedEpoch = if (text.startsWith("/")) gateway.connectionGeneration else null,
            ownerAgentId = ownerAgentId,
            idempotencyKey = idempotencyKey,
          )
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        return ChatJournalAdmission.Failed(ChatJournalFailure.StorageUnavailable)
      }
    return when (result) {
      is ChatOutboxEnqueueResult.Queued -> {
        state.refresh()
        ChatJournalAdmission.Queued(result.item)
      }
      ChatOutboxEnqueueResult.QueueFull -> ChatJournalAdmission.Failed(ChatJournalFailure.QueueFull)
      ChatOutboxEnqueueResult.AttachmentsTooLarge -> ChatJournalAdmission.Failed(ChatJournalFailure.AttachmentsTooLarge)
      ChatOutboxEnqueueResult.StorageFull -> ChatJournalAdmission.Failed(ChatJournalFailure.StorageFull)
      ChatOutboxEnqueueResult.Unavailable -> ChatJournalAdmission.Failed(ChatJournalFailure.Unavailable)
    }
  }

  /** Null means storage failure, zero means a newer attempt/status or deletion already won. */
  suspend fun update(
    row: ChatOutboxItem,
    status: ChatOutboxStatus,
    error: String?,
    expectedStatus: ChatOutboxStatus = row.status,
  ): Int? =
    try {
      outbox?.updateStatusIfAttempt(row.id, row.attemptVersion, status, row.retryCount, error, expectedStatus = expectedStatus)
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      null
    }

  suspend fun claim(
    row: ChatOutboxItem,
    resetRetry: Boolean = false,
  ): ChatJournalClaim {
    val sending = row.copy(status = ChatOutboxStatus.Sending, retryCount = if (resetRetry) 0 else row.retryCount, lastError = if (resetRetry) null else row.lastError)
    val claimed =
      try {
        outbox?.claimForSendingIfAttempt(row.id, row.attemptVersion, sending.retryCount, sending.lastError)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        null
      }
    return when (claimed) {
      null -> ChatJournalClaim.Unavailable
      0 -> ChatJournalClaim.NotClaimed
      else -> ChatJournalClaim.Claimed(sending)
    }
  }

  suspend fun settle(
    row: ChatOutboxItem?,
    status: ChatOutboxStatus,
    error: String?,
    expectedStatus: ChatOutboxStatus = ChatOutboxStatus.Sending,
  ) {
    if (row == null || outbox == null) return
    if (status != ChatOutboxStatus.Accepted) forget(row)
    if (update(row, status, error, expectedStatus = expectedStatus) == null) storageFailed()
    state.refresh()
    resumeDelivery()
  }

  suspend fun removeIfQueued(row: ChatOutboxItem): Boolean {
    val removed =
      try {
        outbox?.deleteIfQueued(row.id) == true
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        false
      }
    state.refresh()
    return removed
  }

  /** The same mutex orders Stop against both initial admission and explicit Retry. */
  suspend fun parkStopped(target: ChatStopTarget) {
    val store = outbox ?: return
    admissionMutex.withLock {
      for (row in store.load(target.connection.gatewayId)) {
        if (row.sessionKey != target.owner.sessionKey || row.ownerAgentId != target.owner.agentId || row.status != ChatOutboxStatus.Queued) continue
        store.updateStatusIfAttempt(row.id, row.attemptVersion, ChatOutboxStatus.Failed, row.retryCount, OUTBOX_CHAT_STOPPED_ERROR, expectedStatus = ChatOutboxStatus.Queued)
      }
    }
    state.refresh()
  }

  suspend fun retry(
    row: ChatOutboxItem,
    gateway: ChatCacheScope,
    ownerAgentId: String,
    canRetry: () -> Boolean,
  ) {
    val store = outbox ?: return
    val requeued =
      try {
        admissionMutex.withLock {
          if (!canRetry()) return@withLock 0
          store.requeueForRetryIfCurrent(
            gatewayId = gateway.gatewayId,
            id = row.id,
            expectedAttemptVersion = row.attemptVersion,
            expectedRetryCount = row.retryCount,
            expectedLastError = row.lastError,
            nowMs = System.currentTimeMillis(),
            gatedEpoch = row.gatedEpoch?.let { gateway.connectionGeneration },
            ownerAgentId = ownerAgentId,
          )
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        0
      }
    state.refresh()
    if (requeued > 0) {
      forget(row)
      resumeDelivery()
    }
  }

  suspend fun delete(id: String) {
    val store = outbox ?: return
    try {
      store.delete(id)
      acknowledgements.keys.removeAll { it.id == id }
      unconfirmedSightings.keys.removeAll { it.id == id }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      // Keep delivery hints while storage still owns this input.
    }
    state.refresh()
    resumeDelivery()
  }

  /** An unavailable backlog is not evidence that it is safe to overtake earlier input. */
  suspend fun hasBacklog(
    gatewayId: String,
    row: ChatOutboxItem,
  ): Boolean {
    val store = outbox ?: return false
    val rows =
      try {
        store.load(gatewayId)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        storageFailed()
        return true
      }
    val rowOwner = owner(row)
    return rows.any { it.id != row.id && it.createdAtMs < row.createdAtMs && owner(it) == rowOwner && unresolved(it) }
  }

  /** A blocked session holds its own FIFO, not unrelated sessions. */
  fun nextFlushable(
    rows: List<ChatOutboxItem>,
  ): ChatOutboxItem? {
    val blocked = mutableSetOf<Any>()
    for (row in rows) {
      val session: Any = owner(row) ?: normalizeSessionKey(row.sessionKey)
      if (row.status == ChatOutboxStatus.Queued && session !in blocked) return row
      if (unresolved(row)) blocked.add(session)
    }
    return null
  }

  suspend fun parkStaleCommands(
    rows: List<ChatOutboxItem>,
    gateway: ChatCacheScope,
  ): Boolean {
    var parked = false
    for (row in rows) {
      if (row.status != ChatOutboxStatus.Queued || row.gatedEpoch == null || row.gatedEpoch == gateway.connectionGeneration) continue
      if (update(row, ChatOutboxStatus.Failed, OUTBOX_CONNECTION_CHANGED_ERROR) == null) {
        storageFailed()
        return true // The caller reloads and its unhealthy check stops the drain.
      }
      parked = true
    }
    return parked
  }

  suspend fun loadAttachments(row: ChatOutboxItem): List<OutgoingAttachment> {
    if (row.attachments.isEmpty()) return emptyList()
    return requireNotNull(outbox).loadAttachments(row.id).map { loaded ->
      OutgoingAttachment(loaded.attachment.type, loaded.attachment.mimeType, loaded.attachment.fileName, Base64.getEncoder().encodeToString(loaded.bytes))
    }
  }

  /** Canonical user rows retire only the observed attempts; absent proof never resends. */
  suspend fun reconcileHistory(
    gatewayId: String,
    history: ChatHistory,
    ownerAgentId: String,
  ): Boolean {
    val store = outbox ?: return false
    val rows =
      try {
        store.load(gatewayId)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        return false
      }
    val proven = history.messages.mapNotNull(::persistedRowId).toSet()
    val inFlight =
      history.inFlightRun
        ?.runId
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    val sessionKey = normalizeSessionKey(history.sessionKey)
    val scoped =
      rows.filter {
        normalizeSessionKey(it.sessionKey) == sessionKey && (it.ownerAgentId ?: resolveAgentIdFromMainSessionKey(it.sessionKey)) == ownerAgentId
      }
    val confirmed = scoped.filter { it.id in proven }
    var changed = false
    if (confirmed.isNotEmpty()) {
      val removed =
        try {
          store.confirmDeliveredAttempts(confirmed.associate { it.id to it.attemptVersion })
        } catch (error: CancellationException) {
          throw error
        } catch (_: Throwable) {
          0
        }
      confirmed.forEach(::forget)
      changed = removed > 0
    }
    for (row in scoped) {
      if (row.status != ChatOutboxStatus.Accepted || row.id in proven || isLocallyOwned(row)) continue
      if (inFlight != null && (row.id == inFlight || acknowledgements[row.attempt()] == inFlight)) {
        unconfirmedSightings.remove(row.attempt())
        continue
      }
      if (unconfirmedSightings.merge(row.attempt(), 1, Int::plus)!! < 2) continue
      val persisted = update(row, ChatOutboxStatus.Failed, OUTBOX_DELIVERY_UNCONFIRMED_ERROR)
      if (persisted == null) {
        storageFailed()
      } else {
        forget(row)
        changed = changed || persisted > 0
      }
    }
    return changed
  }

  /** Deadline expiry changes durable visibility, never re-enqueues an uncertain send. */
  suspend fun parkUnconfirmed(runId: String) {
    val row =
      state.items.value.firstOrNull {
        it.status == ChatOutboxStatus.Accepted && (it.id == runId || acknowledgements[it.attempt()] == runId)
      } ?: return
    if (update(row, ChatOutboxStatus.Failed, OUTBOX_DELIVERY_UNCONFIRMED_ERROR) == null) storageFailed() else forget(row)
    state.refresh()
  }

  private fun persistedRowId(message: ChatMessage): String? {
    if (message.role.trim().lowercase() != "user") return null
    val key = message.idempotencyKey?.trim() ?: return null
    if (!key.endsWith(":user")) return null
    return key.removeSuffix(":user").takeIf(String::isNotEmpty)
  }
}
