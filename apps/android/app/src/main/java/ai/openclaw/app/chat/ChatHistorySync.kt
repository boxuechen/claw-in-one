package ai.openclaw.app.chat

import ai.openclaw.app.resolveAgentIdFromMainSessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ChatHistoryRefreshResult {
  data object Applied : ChatHistoryRefreshResult

  data object Superseded : ChatHistoryRefreshResult

  data object OwnerUnavailable : ChatHistoryRefreshResult

  data object Failed : ChatHistoryRefreshResult
}

/** Connection and default-owner facts supplied by composition; no duplicate authority. */
internal data class ChatHistoryContext(
  val gatewayScope: ChatCacheScope?,
  val defaultAgentId: String?,
  val defaultAgentRevision: Long,
  val healthy: Boolean,
)

/**
 * Cache/live/reconnect choreography and canonical transcript publication.
 * Transcript, selection, settings, and pending replies keep their owners.
 * Only read/recovery work is scheduled here; no send, Session mutation, or permission grant.
 */
internal class ChatHistorySync(
  private val scope: CoroutineScope,
  private val gatewayScopeApplyLock: Any,
  private val historyPublicationMutex: Mutex,
  private val sessionSelection: ChatSessionSelection,
  private val transcript: ChatTranscript,
  private val sessionSettings: ChatSessionSettings,
  private val sessionCatalog: ChatSessionCatalog,
  private val pendingSends: ChatPendingSends,
  private val outboxState: ChatOutboxState,
  private val drafts: ChatDraftController,
  private val transcriptCache: ChatTranscriptCache?,
  private val context: () -> ChatHistoryContext,
  private val pollHealthIfNeeded: suspend (Boolean) -> Unit,
  private val publishSessionInfo: (ChatHistory) -> Unit,
  private val onHistoryPublished: (Long, ChatHistory) -> Unit,
  private val onHistoryError: (Long, String?) -> Unit,
  private val onOfflineDefaultAgentRestored: (String) -> Unit,
  private val confirmDelivery: suspend (ChatCacheScope?, ChatHistory, String) -> Unit,
) {
  private var restoreRunStateOnReconnect = false
  private var reconnectRecoveryGeneration: Long? = null
  val recovering: Boolean get() = synchronized(gatewayScopeApplyLock) { restoreRunStateOnReconnect }

  fun disconnected(): Unit =
    synchronized(gatewayScopeApplyLock) {
      restoreRunStateOnReconnect = true
      reconnectRecoveryGeneration = null
    }

  fun retireRecovery(): Unit =
    synchronized(gatewayScopeApplyLock) {
      restoreRunStateOnReconnect = false
      reconnectRecoveryGeneration = null
    }

  /**
   * Reconnect/seq-gap recovery: refetch history for the current session without the
   * beginHistoryLoad transient-state reset. Runs pending when the request begins stay
   * owned until that authoritative snapshot resolves them; resetting healthOk here
   * would block sends after reconnect.
   */
  fun refreshForRecovery(
    forceHealth: Boolean = false,
    completesReconnectRecovery: Boolean = false,
  ) {
    val (key, generation) =
      synchronized(gatewayScopeApplyLock) {
        val key = sessionSelection.normalizeCurrentKey()
        val generation = transcript.nextLoad(markLoading = true)
        if (completesReconnectRecovery) reconnectRecoveryGeneration = generation
        key to generation
      }
    pendingSends.restoreDisconnected()
    val runIdsToReconcile = pendingSends.recoveryRunIds
    scope.launch {
      bootstrap(
        sessionKey = key,
        generation = generation,
        forceHealth = forceHealth,
        refreshSessions = true,
        runIdsToReconcile = runIdsToReconcile,
      )
    }
  }

  suspend fun bootstrap(
    sessionKey: String,
    generation: Long,
    forceHealth: Boolean,
    refreshSessions: Boolean,
    runIdsToReconcile: Set<String> = emptySet(),
  ) {
    val ownsReconnectRecovery =
      synchronized(gatewayScopeApplyLock) {
        reconnectRecoveryGeneration == generation
      }
    // Cache-first cold open: prime before the live request so ordering is deterministic and the
    // live chat.history response always replaces cached rows wholesale.
    primeFromCache(sessionKey, generation)
    try {
      val historyResult =
        fetchAndApply(
          sessionKey,
          generation,
          updateSessionInfo = true,
          runIdsToReconcile = runIdsToReconcile,
        )
      if (historyResult !is ChatHistoryRefreshResult.Applied) {
        if (
          historyResult == ChatHistoryRefreshResult.OwnerUnavailable &&
          isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation)
        ) {
          transcript.finishLoad(sessionKey, generation)
        }
        return
      }

      if (!ownsReconnectRecovery) {
        pollHealthIfNeeded(forceHealth)
      }
      if (refreshSessions) {
        sessionCatalog.refresh(limit = 50)
      }
    } catch (err: CancellationException) {
      throw err
    } catch (err: Throwable) {
      if (!isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation)) return
      onHistoryError(generation, err.message)
      transcript.finishLoad(sessionKey, generation)
    } finally {
      if (currentCoroutineContext().isActive && isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation)) {
        pendingSends.scheduleRecovery(
          sessionKey = sessionKey,
          generation = generation,
          runIds = runIdsToReconcile,
        )
      }
    }
  }

  /**
   * Coordinates live history publication across the existing state owners, replacing cache.
   * Reports when a newer load superseded this request (stale responses are dropped).
   */
  suspend fun fetchAndApply(
    sessionKey: String,
    generation: Long,
    updateSessionInfo: Boolean,
    runIdsToReconcile: Set<String> = emptySet(),
    markCompletedTranscript: Boolean = false,
  ): ChatHistoryRefreshResult {
    if (drafts.find(context().gatewayScope?.gatewayId, sessionKey)?.isLocal == true) {
      if (!isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation)) return ChatHistoryRefreshResult.Superseded
      transcript.finishLoad(sessionKey, generation)
      // A draft has no transcript to reconcile, but still owns the reconnect health barrier.
      // Skipping this leaves a connected draft unable to admit its first message indefinitely.
      completeReconnectRecoveryIfOwned(sessionKey, generation)
      return ChatHistoryRefreshResult.Applied
    }
    val runIdsOwnedAtRequest = pendingSends.visibleRunIds
    val requestModelSelectionGeneration = sessionSettings.modelRevision
    val request =
      when (val preparation = transcript.prepareRead(sessionKey, generation)) {
        is ChatHistoryPreparation.Ready -> preparation.request
        ChatHistoryPreparation.OwnerUnavailable -> return ChatHistoryRefreshResult.OwnerUnavailable
        ChatHistoryPreparation.Superseded -> return ChatHistoryRefreshResult.Superseded
      }
    val requestCacheScope = request.gatewayScope
    val requestAgentId = request.agentId
    val history = transcript.fetch(request) ?: return ChatHistoryRefreshResult.Superseded
    val applied =
      historyPublicationMutex.withLock {
        if (!synchronized(gatewayScopeApplyLock) { transcript.isCurrent(request) }) return@withLock false
        synchronized(gatewayScopeApplyLock) {
          if (!transcript.isCurrent(request)) return@synchronized false
          val runIdsOwnedAfterRequest = pendingSends.visibleRunIds - runIdsOwnedAtRequest
          if (updateSessionInfo) {
            publishSessionInfo(history)
            sessionSettings.applyHistoryModel(requestModelSelectionGeneration, history.sessionInfo?.providerQualifiedModelRef())
          }
          val pendingProjection = pendingSends.beforeHistory(history, runIdsToReconcile, markCompletedTranscript)
          if (!transcript.publishLive(request, history, pendingProjection.optimisticMessages, pendingProjection.completionSettled)) return@synchronized false
          pendingSends.afterHistory(history, runIdsToReconcile, runIdsOwnedAfterRequest)
          onHistoryPublished(generation, history)
          sessionSettings.applyHistoryThinking(history.thinkingLevel)
          true
        }
      }
    if (!applied) return ChatHistoryRefreshResult.Superseded
    completeReconnectRecoveryIfOwned(sessionKey, generation)
    confirmDelivery(requestCacheScope, history, requestAgentId)
    outboxState.refresh()
    return ChatHistoryRefreshResult.Applied
  }

  /** Lets whichever same-generation history request wins finish reconnect health recovery. */
  private suspend fun completeReconnectRecoveryIfOwned(
    sessionKey: String,
    generation: Long,
  ) {
    val ownsRecovery =
      synchronized(gatewayScopeApplyLock) {
        reconnectRecoveryGeneration == generation &&
          isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation)
      }
    if (!ownsRecovery) return
    pollHealthIfNeeded(true)
    synchronized(gatewayScopeApplyLock) {
      if (
        reconnectRecoveryGeneration == generation &&
        isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation) &&
        context().healthy
      ) {
        reconnectRecoveryGeneration = null
        restoreRunStateOnReconnect = false
      }
    }
  }

  /** Emits cached transcript/session rows for instant cold open; live data replaces them wholesale. */
  private suspend fun primeFromCache(
    sessionKey: String,
    generation: Long,
  ) {
    val cache = transcriptCache ?: return
    val requestCacheScope = context().gatewayScope ?: return
    val explicitAgentId = resolveAgentIdFromMainSessionKey(sessionKey)
    val selectedOwnerAgentId = sessionSelection.ownerAgentId.value
    val requestTracksDefaultAgent = explicitAgentId == null && selectedOwnerAgentId == null
    val requestDefaultAgentRevision = context().defaultAgentRevision
    val liveDefaultAgentId = sessionSelection.effectiveDefaultAgentId()
    val requestAgentId =
      explicitAgentId
        ?: selectedOwnerAgentId
        ?: liveDefaultAgentId
        ?: readCacheOrDefault(null) { cache.loadLastDefaultAgentId(requestCacheScope.gatewayId) }
        ?: return

    if (requestTracksDefaultAgent && liveDefaultAgentId == null) {
      // Cache I/O suspends. A newer hello/default-owner event must win before this persisted
      // fallback reaches composer state, or an offline owner can overwrite live routing proof.
      val persistedOwnerIsStillCurrent =
        requestCacheScope == context().gatewayScope &&
          context().defaultAgentRevision == requestDefaultAgentRevision &&
          context().defaultAgentId?.trim().isNullOrEmpty() &&
          sessionSelection.effectiveDefaultAgentId() == null
      if (!persistedOwnerIsStillCurrent) return
      // The persisted owner is the routing proof for an offline process restart. Publish it to
      // composer consumers too; otherwise cached history and editable drafts disagree on owner.
      sessionSelection.recordDefaultAgent(requestCacheScope.gatewayId, requestAgentId)
      // NodeRuntime owns the device-scoped key shape. Rebuild it from persisted routing proof so
      // offline sends target the same immutable session instead of the mutable `main` alias.
      onOfflineDefaultAgentRestored(requestAgentId)
    }

    fun requestOwnerIsCurrent(): Boolean =
      sessionSelection.resolveOwner(sessionSelection.key.value) == requestAgentId &&
        (
          !requestTracksDefaultAgent ||
            (
              context().defaultAgentRevision == requestDefaultAgentRevision &&
                (sessionSelection.effectiveDefaultAgentId() == requestAgentId || (liveDefaultAgentId == null && sessionSelection.effectiveDefaultAgentId() == null))
            )
        )
    val cached =
      readCacheOrDefault(emptyList()) { cache.loadTranscript(requestCacheScope.gatewayId, requestAgentId, sessionKey) }
    synchronized(gatewayScopeApplyLock) {
      val projectedMessages = pendingSends.optimisticMessages
      if (
        cached.isNotEmpty() &&
        requestCacheScope == context().gatewayScope &&
        requestOwnerIsCurrent() &&
        isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation)
      ) {
        transcript.publishCached(sessionKey, generation, cached, projectedMessages)
      }
    }
    if (sessionCatalog.entries.value.isEmpty()) {
      val cachedSessions = readCacheOrDefault(emptyList()) { cache.loadSessions(requestCacheScope.gatewayId, requestAgentId) }
      synchronized(gatewayScopeApplyLock) {
        if (
          cachedSessions.isNotEmpty() &&
          sessionCatalog.entries.value.isEmpty() &&
          requestCacheScope == context().gatewayScope &&
          requestOwnerIsCurrent()
        ) {
          sessionCatalog.restoreCached(cachedSessions, requestCacheScope, requestAgentId)
        }
      }
    }
  }

  suspend fun refreshSnapshot(
    sessionKey: String,
    generation: Long,
    runIdsToReconcile: Set<String>,
  ): ChatHistoryRefreshResult =
    try {
      fetchAndApply(
        sessionKey,
        generation,
        updateSessionInfo = true,
        runIdsToReconcile = runIdsToReconcile,
        markCompletedTranscript = runIdsToReconcile.isNotEmpty(),
      )
    } catch (err: CancellationException) {
      throw err
    } catch (_: Throwable) {
      // The bounded expiry below remains the final reconciliation path.
      ChatHistoryRefreshResult.Failed
    }

  fun refreshCurrent(
    runIdsToReconcile: Set<String> = emptySet(),
    updateSessionInfo: Boolean = false,
  ) {
    val sessionKey = sessionSelection.key.value
    val generation = transcript.generation
    scope.launch {
      try {
        fetchAndApply(
          sessionKey = sessionKey,
          generation = generation,
          updateSessionInfo = updateSessionInfo,
          runIdsToReconcile = runIdsToReconcile,
          markCompletedTranscript = runIdsToReconcile.isNotEmpty(),
        )
      } catch (err: CancellationException) {
        throw err
      } catch (_: Throwable) {
        // best-effort
      } finally {
        if (currentCoroutineContext().isActive && isCurrentHistoryLoad(sessionKey, sessionSelection.key.value, generation, transcript.generation)) {
          pendingSends.scheduleRecovery(sessionKey, generation, runIdsToReconcile)
        }
      }
    }
  }

  suspend fun refreshForAction(
    snapshot: ChatSessionActionSnapshot,
    generation: Long,
  ): ChatHistoryRefreshResult.Applied? {
    if (!sessionSelection.isCurrent(snapshot)) return null
    return try {
      fetchAndApply(
        sessionKey = snapshot.sessionKey,
        generation = generation,
        updateSessionInfo = true,
      ) as? ChatHistoryRefreshResult.Applied
    } catch (err: CancellationException) {
      throw err
    } catch (_: Throwable) {
      null
    }
  }
}

/** Optional cache failures may fall through to live reads; cancellation must not. */
private suspend fun <T> readCacheOrDefault(
  default: T,
  read: suspend () -> T,
): T =
  try {
    read()
  } catch (error: CancellationException) {
    throw error
  } catch (_: Throwable) {
    default
  }

private fun ChatSessionEntry.providerQualifiedModelRef(): String? {
  val model = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val provider = modelProvider?.trim()?.takeIf { it.isNotEmpty() } ?: return model
  return if (model.startsWith("$provider/")) model else "$provider/$model"
}
