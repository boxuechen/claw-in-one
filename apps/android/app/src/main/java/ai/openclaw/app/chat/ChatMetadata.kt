package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val SESSION_SCOPED_CHAT_METADATA_CAPABILITY = "session-scoped-chat-metadata"

/** Owns command availability and refresh ordering, not model selection or Chat history. */
internal class ChatMetadata(
  private val scope: CoroutineScope,
  private val json: Json,
  private val publicationLock: Any,
  private val selection: ChatSessionSelection,
  private val currentGatewayScope: () -> ChatCacheScope?,
  private val currentCatalogRevision: () -> Long,
  private val gatewayAdvertisesCapability: (String) -> Boolean?,
  private val isLocalDraft: (ChatCacheScope?, String) -> Boolean,
  private val captureRequestLease: (ChatCacheScope?) -> GatewaySession.RequestLease?,
) {
  private data class Target(
    val gatewayScope: ChatCacheScope?,
    val agentId: String,
    val sessionKey: String?,
  ) {
    fun params(): String =
      buildJsonObject {
        put("agentId", JsonPrimitive(agentId))
        sessionKey?.let { put("sessionKey", JsonPrimitive(it)) }
      }.toString()
  }

  private data class Read(
    val sequence: Long,
    val target: Target,
    val owner: ChatSessionCatalogOwner,
    val selectionGeneration: Long,
    val catalogRevision: Long,
  )

  private enum class LoadState { Unloaded, Loaded }

  private val mutableCommands = MutableStateFlow<List<ChatCommandEntry>>(emptyList())
  val commands = mutableCommands.asStateFlow()
  private var sequence = 0L
  private var target: Target? = null
  private var loadedCatalogRevision: Long? = null
  private var loadState = LoadState.Unloaded

  private fun currentTarget(): Target? {
    val key = selection.key.value
    val agent = selection.resolveOwner(key) ?: return null
    val gateway = currentGatewayScope()
    val sessionScoped = !isLocalDraft(gateway, key) && gatewayAdvertisesCapability(SESSION_SCOPED_CHAT_METADATA_CAPABILITY) == true
    return Target(gateway, agent, key.takeIf { sessionScoped })
  }

  /** Clear at the selection/connection publication boundary, before queued replies can run. */
  fun clear(): Unit = synchronized(publicationLock) { reset(null) }

  fun selectionChanged(): Unit =
    synchronized(publicationLock) {
      val next = currentTarget()
      if (target != next) reset(next)
    }

  private fun reset(next: Target?) {
    sequence += 1
    target = next
    loadedCatalogRevision = null
    loadState = LoadState.Unloaded
    mutableCommands.value = emptyList()
  }

  fun isLoaded(): Boolean =
    synchronized(publicationLock) {
      val current = currentTarget() ?: return false
      target == current && loadedCatalogRevision == currentCatalogRevision() && loadState == LoadState.Loaded
    }

  /** A session-profile mutation does not invalidate another session's availability. */
  fun matchesSession(
    key: String?,
    agentId: String?,
  ): Boolean =
    synchronized(publicationLock) {
      val current = currentTarget() ?: return false
      key != null && current.sessionKey == key && current.agentId == agentId
    }

  fun refresh() {
    val read = prepareRead() ?: return
    val lease = captureRequestLease(read.target.gatewayScope) ?: return
    scope.launch { fetch(read, lease) }
  }

  suspend fun refreshAwait() {
    val read = prepareRead() ?: return
    val lease = captureRequestLease(read.target.gatewayScope) ?: return
    fetch(read, lease)
  }

  private fun prepareRead(): Read? =
    synchronized(publicationLock) {
      // Retire prior reads now, not when this coroutine eventually gets CPU time.
      sequence += 1
      val next = currentTarget() ?: return null
      if (target != next) reset(next)
      val owner = selection.captureCatalogOwner() ?: return null
      // The transport takes its own lifecycle lock. Capture that lease only after
      // releasing this lock; enqueue/publication will recheck the captured identity.
      Read(sequence, next, owner, selection.generation.value, currentCatalogRevision())
    }

  private fun isCurrent(read: Read): Boolean =
    read.sequence == sequence &&
      read.target == currentTarget() &&
      selection.isCurrent(read.owner) &&
      read.selectionGeneration == selection.generation.value &&
      read.catalogRevision == currentCatalogRevision()

  private suspend fun fetch(
    read: Read,
    lease: GatewaySession.RequestLease,
  ) {
    try {
      if (!synchronized(publicationLock) { isCurrent(read) }) return
      val response = lease.request("chat.metadata", read.target.params())
      json.parseToJsonElement(response) as? JsonObject ?: return
      val commands = parseChatCommands(json, response)
      synchronized(publicationLock) {
        if (!isCurrent(read) || !lease.isCurrent()) return
        mutableCommands.value = commands
        loadState = LoadState.Loaded
        loadedCatalogRevision = read.catalogRevision
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      // A failed read is not a replacement for accepted availability in this scope.
    }
  }
}
