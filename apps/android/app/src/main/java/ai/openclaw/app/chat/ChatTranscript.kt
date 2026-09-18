package ai.openclaw.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

internal data class ChatHistoryRequest(
  val gatewayScope: ChatCacheScope?,
  val sessionKey: String,
  val agentId: String,
  val generation: Long,
  val sequence: Long,
  val tracksDefaultAgent: Boolean,
  val defaultAgentRevision: Long,
)

internal sealed interface ChatHistoryPreparation {
  data class Ready(
    val request: ChatHistoryRequest,
  ) : ChatHistoryPreparation

  data object Superseded : ChatHistoryPreparation

  data object OwnerUnavailable : ChatHistoryPreparation
}

/**
 * Canonical transcript reads, visible message projection, and cache publication.
 * Delivery supplies optimistic rows and completion evidence; it does not own these flows.
 */
internal class ChatTranscript(
  private val scope: CoroutineScope,
  private val publicationLock: Any,
  private val selection: ChatSessionSelection,
  private val currentGatewayScope: () -> ChatCacheScope?,
  private val currentDefaultAgentRevision: () -> Long,
  private val codec: ChatHistoryCodec,
  private val awaitSessionReadiness: suspend (String, ChatCacheScope?) -> Unit,
  private val requestHistory: suspend (ChatHistoryRequest) -> String,
  private val cache: ChatTranscriptCache?,
  private val cacheMutationMutex: Mutex,
) {
  private val mutableSessionId = MutableStateFlow<String?>(null)
  val sessionId = mutableSessionId.asStateFlow()
  private val mutableMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages = mutableMessages.asStateFlow()
  private val mutableAnchor = MutableStateFlow<ChatTranscriptAnchorState?>(null)
  val anchor = mutableAnchor.asStateFlow()
  private val mutableFromCache = MutableStateFlow(false)
  val fromCache = mutableFromCache.asStateFlow()
  private val mutableLoading = MutableStateFlow(false)
  val loading = mutableLoading.asStateFlow()
  private val loadGeneration = AtomicLong(0)
  val generation: Long get() = loadGeneration.get()
  private val requestSequence = AtomicLong(0)
  private var lastPublication = 0L
  val publicationSequence: Long get() = synchronized(publicationLock) { lastPublication }
  private var liveRequest: ChatHistoryRequest? = null

  fun nextLoad(markLoading: Boolean? = null): Long =
    synchronized(publicationLock) {
      markLoading?.let { mutableLoading.value = it }
      loadGeneration.incrementAndGet()
    }

  /** The coordinator publishes selection in this same lock transaction. */
  fun beginSelection(
    clearMessages: Boolean,
    markLoading: Boolean,
  ): Long =
    synchronized(publicationLock) {
      val next = nextLoad(markLoading)
      if (clearMessages) {
        mutableMessages.value = emptyList()
        mutableFromCache.value = false
      }
      mutableSessionId.value = null
      liveRequest = null
      next
    }

  fun disconnect(): Unit =
    synchronized(publicationLock) {
      nextLoad(false)
      mutableSessionId.value = null
      liveRequest = null
    }

  fun finishLoad(
    key: String,
    generation: Long,
  ): Unit =
    synchronized(publicationLock) {
      if (isCurrentLoad(key, generation)) mutableLoading.value = false
    }

  fun invalidateLive(): Unit = synchronized(publicationLock) { liveRequest = null }

  fun hasLiveHistory(key: String): Boolean =
    synchronized(publicationLock) {
      val live = liveRequest ?: return false
      live.sessionKey == key && isCurrent(live) && !mutableFromCache.value
    }

  fun isCurrentLoad(
    key: String,
    generation: Long,
  ): Boolean = isCurrentHistoryLoad(key, selection.key.value, generation, this.generation)

  suspend fun prepareRead(
    key: String,
    generation: Long,
  ): ChatHistoryPreparation {
    val (sequence, gatewayScope, tracksDefault) =
      synchronized(publicationLock) {
        Triple(requestSequence.incrementAndGet(), currentGatewayScope(), selection.tracksDefaultAgent(key))
      }
    awaitSessionReadiness(key, gatewayScope)
    return synchronized(publicationLock) {
      if (!isCurrentLoad(key, generation) || gatewayScope != currentGatewayScope()) return ChatHistoryPreparation.Superseded
      val agent = selection.resolveOwner(key) ?: return ChatHistoryPreparation.OwnerUnavailable
      val request = ChatHistoryRequest(gatewayScope, key, agent, generation, sequence, tracksDefault, currentDefaultAgentRevision())
      if (isCurrent(request)) ChatHistoryPreparation.Ready(request) else ChatHistoryPreparation.Superseded
    }
  }

  /** Null is superseded, never a successful empty history. Current failures stay observable. */
  suspend fun fetch(request: ChatHistoryRequest): ChatHistory? {
    if (!isCurrent(request)) return null
    return try {
      val response = requestHistory(request)
      val previous =
        synchronized(publicationLock) {
          if (!isCurrent(request)) return null
          mutableMessages.value
        }
      codec.parseHistory(response, request.sessionKey, previous)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      if (!isCurrent(request)) null else throw error
    }
  }

  fun isCurrent(request: ChatHistoryRequest): Boolean =
    synchronized(publicationLock) {
      isCurrentLoad(request.sessionKey, request.generation) &&
        request.gatewayScope == currentGatewayScope() &&
        request.agentId == selection.resolveOwner(selection.key.value) &&
        request.sequence >= lastPublication &&
        (
          !request.tracksDefaultAgent ||
            (request.defaultAgentRevision == currentDefaultAgentRevision() && selection.effectiveDefaultAgentId() == request.agentId)
        )
    }

  /** Publication and its cache enqueue are atomic. */
  fun publishLive(
    request: ChatHistoryRequest,
    history: ChatHistory,
    optimistic: Collection<ChatMessage>,
    completionSettled: Boolean,
  ): Boolean =
    synchronized(publicationLock) {
      if (!isCurrent(request) || history.sessionKey != request.sessionKey) return false
      val next = mergeOptimisticMessages(history.messages, optimistic)
      val previousAnchor = mutableAnchor.value?.takeIf { it.sessionKey == request.sessionKey }
      lastPublication = request.sequence
      mutableFromCache.value = false
      mutableMessages.value = next
      mutableAnchor.value =
        ChatTranscriptAnchorState(
          sessionKey = request.sessionKey,
          newestItemId = next.lastOrNull()?.id,
          completedEndedAt = if (completionSettled) history.sessionInfo?.endedAt else previousAnchor?.completedEndedAt,
          completedNewestItemId = if (completionSettled) history.messages.lastOrNull()?.id else previousAnchor?.completedNewestItemId,
        )
      mutableSessionId.value = history.sessionId
      liveRequest = request
      mutableLoading.value = false
      enqueueCacheWrite(request, history.messages)
      true
    }

  /** Cache routing proof is checked by the cache loader; it may only seed a non-live view. */
  fun publishCached(
    key: String,
    generation: Long,
    cached: List<ChatMessage>,
    optimistic: Collection<ChatMessage>,
  ): Boolean =
    synchronized(publicationLock) {
      if (!isCurrentLoad(key, generation) || cached.isEmpty() || hasLiveHistory(key)) return false
      if (!mutableMessages.value.all { it in optimistic }) return false
      mutableFromCache.value = true
      mutableMessages.value = mergeOptimisticMessages(cached, optimistic)
      true
    }

  fun appendOptimistic(message: ChatMessage): Unit =
    synchronized(publicationLock) {
      if (mutableMessages.value.none { it.idempotencyKey == message.idempotencyKey }) {
        mutableMessages.value += message
      }
    }

  fun removeOptimistic(messageId: String): Unit =
    synchronized(publicationLock) {
      mutableMessages.value = mutableMessages.value.filterNot { it.id == messageId }
    }

  fun rekeyOptimistic(
    messageId: String,
    replacement: ChatMessage,
  ): Unit =
    synchronized(publicationLock) {
      mutableMessages.value = mutableMessages.value.map { if (it.id == messageId) replacement else it }
    }

  private fun enqueueCacheWrite(
    request: ChatHistoryRequest,
    messages: List<ChatMessage>,
  ) {
    val cache = cache ?: return
    val gatewayScope = request.gatewayScope ?: return
    // Enter the shared cache queue before releasing publication. Deletion and session-list
    // writes use the same mutex; a newer snapshot cannot persist ahead of this one.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      cacheMutationMutex.withLock {
        if (gatewayScope != currentGatewayScope()) return@withLock
        runCatching { cache.saveTranscript(gatewayScope.gatewayId, request.agentId, request.sessionKey, messages) }
      }
    }
  }
}

internal fun isCurrentHistoryLoad(
  requestedSessionKey: String,
  currentSessionKey: String,
  requestGeneration: Long,
  activeGeneration: Long,
): Boolean = requestedSessionKey == currentSessionKey && requestGeneration == activeGeneration
