package ai.openclaw.app.chat

import ai.openclaw.app.resolveAgentIdFromMainSessionKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Captured action identity for selection-bound history work. */
internal data class ChatSessionActionSnapshot(
  val gatewayScope: ChatCacheScope?,
  val sessionKey: String,
  val ownerAgentId: String,
  val selectionGeneration: Long,
)

/** Agent-scoped list identity, independent of the selected transcript's generation. */
internal data class ChatSessionCatalogOwner(
  val gatewayScope: ChatCacheScope?,
  val agentId: String,
  val tracksDefaultAgent: Boolean,
  val defaultAgentRevision: Long,
)

/** Selection and routing identity only. No history loading, cache I/O, or send admission. */
internal class ChatSessionSelection(
  private val publicationLock: Any,
  private val currentGatewayScope: () -> ChatCacheScope?,
  private val currentDefaultAgentId: () -> String?,
  private val currentDefaultAgentRevision: () -> Long,
) {
  private val mutableKey = MutableStateFlow("main")
  val key = mutableKey.asStateFlow()
  private val mutableOwnerAgentId = MutableStateFlow<String?>(null)
  val ownerAgentId = mutableOwnerAgentId.asStateFlow()
  private val mutableGeneration = MutableStateFlow(0L)
  val generation = mutableGeneration.asStateFlow()

  private var boundMainKey = "main"
  val mainKey: String get() = synchronized(publicationLock) { boundMainKey }
  private var lastVerifiedAgentId = currentDefaultAgentId()?.trim()?.takeIf(String::isNotEmpty)
  private var lastVerifiedGatewayId = currentGatewayScope()?.gatewayId
  private val mutableDefaultOwner =
    MutableStateFlow(
      lastVerifiedAgentId?.let { agent -> lastVerifiedGatewayId?.let { GatewayDefaultAgentOwner(it, agent) } },
    )
  val defaultOwner = mutableDefaultOwner.asStateFlow()
  val feature = ChatSelectionFeature(key, ownerAgentId, generation, defaultOwner)

  fun normalizeKey(raw: String): String =
    synchronized(publicationLock) {
      val key = raw.trim()
      if (key.isEmpty() || key == "main") boundMainKey else key
    }

  fun normalizeOwner(
    key: String,
    owner: String?,
  ): String? = resolveAgentIdFromMainSessionKey(key) ?: owner?.trim()?.takeIf(String::isNotEmpty)

  /** Called in the same publication transaction as the transcript's selection reset. */
  fun select(
    key: String,
    owner: String?,
  ): Boolean =
    synchronized(publicationLock) {
      val normalizedOwner = normalizeOwner(key, owner)
      val changed = mutableKey.value != key || mutableOwnerAgentId.value != normalizedOwner
      if (changed) mutableGeneration.value += 1
      mutableKey.value = key
      mutableOwnerAgentId.value = normalizedOwner
      changed
    }

  /** Recovery refresh keeps the same selection generation and all in-flight ownership. */
  fun normalizeCurrentKey(): String =
    synchronized(publicationLock) {
      normalizeKey(mutableKey.value).also { mutableKey.value = it }
    }

  /** Bind the alias; the caller coordinates any returned selection change with history. */
  fun bindMainKey(raw: String): String? =
    synchronized(publicationLock) {
      val next = raw.trim().takeIf(String::isNotEmpty) ?: return null
      val state = applyMainSessionKey(normalizeKey(mutableKey.value), boundMainKey, next)
      boundMainKey = state.appliedMainSessionKey
      state.currentSessionKey.takeIf { it != mutableKey.value }
    }

  fun resetMainKey(): Unit = synchronized(publicationLock) { boundMainKey = "main" }

  fun resolveOwner(sessionKey: String): String? =
    synchronized(publicationLock) {
      resolveAgentIdFromMainSessionKey(sessionKey) ?: mutableOwnerAgentId.value ?: effectiveDefaultAgentId()
    }

  fun tracksDefaultAgent(sessionKey: String): Boolean =
    synchronized(publicationLock) {
      resolveAgentIdFromMainSessionKey(sessionKey) == null && mutableOwnerAgentId.value == null
    }

  fun effectiveDefaultAgentId(): String? =
    synchronized(publicationLock) {
      currentDefaultAgentId()?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
      val gatewayId = currentGatewayScope()?.gatewayId ?: return null
      lastVerifiedAgentId.takeIf { lastVerifiedGatewayId == gatewayId }
    }

  /** Live hello and verified offline cache are evidence supplied by their respective owners. */
  fun recordDefaultAgent(
    gatewayId: String?,
    agentId: String,
  ): String? =
    synchronized(publicationLock) {
      val previous = lastVerifiedAgentId
      lastVerifiedAgentId = agentId
      lastVerifiedGatewayId = gatewayId
      mutableDefaultOwner.value = gatewayId?.let { GatewayDefaultAgentOwner(it, agentId) }
      previous
    }

  fun forgetDefaultAgent(gatewayId: String): Unit =
    synchronized(publicationLock) {
      if (lastVerifiedGatewayId == gatewayId) {
        lastVerifiedAgentId = null
        lastVerifiedGatewayId = null
        mutableDefaultOwner.value = null
      }
    }

  fun captureCatalogOwner(): ChatSessionCatalogOwner? =
    synchronized(publicationLock) {
      val agentId = resolveOwner(mutableKey.value) ?: return null
      ChatSessionCatalogOwner(currentGatewayScope(), agentId, tracksDefaultAgent(mutableKey.value), currentDefaultAgentRevision())
    }

  fun isCurrent(owner: ChatSessionCatalogOwner): Boolean =
    synchronized(publicationLock) {
      owner.gatewayScope == currentGatewayScope() &&
        owner.agentId == resolveOwner(mutableKey.value) &&
        (!owner.tracksDefaultAgent || owner.defaultAgentRevision == currentDefaultAgentRevision())
    }

  fun captureAction(requestedSessionKey: String): ChatSessionActionSnapshot? =
    synchronized(publicationLock) {
      val key = normalizeKey(requestedSessionKey)
      if (key != mutableKey.value) return null
      val owner = resolveOwner(key)?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
      ChatSessionActionSnapshot(currentGatewayScope(), key, owner, mutableGeneration.value)
    }

  fun isCurrentComposerOwner(expectedOwner: ChatComposerOwner): Boolean =
    synchronized(publicationLock) {
      val effectiveSessionKey = normalizeKey(key.value)
      if (effectiveSessionKey == "main" && ownerAgentId.value == null) return false
      val routingOwner =
        resolveChatComposerRoutingOwner(
          gatewayStableId = currentGatewayScope()?.gatewayId,
          gatewayDefaultAgentId = ownerAgentId.value ?: effectiveDefaultAgentId(),
          sessionKey = effectiveSessionKey,
          mainSessionKey = mainKey,
        ) ?: return false
      expectedOwner == routingOwner
    }

  fun isCurrent(snapshot: ChatSessionActionSnapshot): Boolean =
    synchronized(publicationLock) {
      snapshot.gatewayScope == currentGatewayScope() &&
        snapshot.sessionKey == mutableKey.value &&
        snapshot.ownerAgentId == resolveOwner(mutableKey.value)?.trim()?.lowercase() &&
        snapshot.selectionGeneration == mutableGeneration.value
    }
}
