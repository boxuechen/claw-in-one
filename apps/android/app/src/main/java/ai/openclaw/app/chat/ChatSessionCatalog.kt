package ai.openclaw.app.chat

import ai.openclaw.app.gateway.SessionObserverDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Owns the agent-scoped session list, search, partial merges, and publication order.
 * Selection supplies identity; settings supplies its mutation fence; cache persistence
 * and effects on active work remain explicit collaborators. No transcript or execution state.
 */
internal class ChatSessionCatalog(
  private val publicationLock: Any,
  private val selection: ChatSessionSelection,
  private val settings: ChatSessionSettings,
  private val codec: ChatSessionCatalogCodec,
  private val requestGateway: suspend (String, String?) -> String,
  private val persist: suspend (ChatCacheScope?, String, List<ChatSessionEntry>, String?) -> Unit,
  private val onSnapshotPublished: (List<ChatSessionEntry>) -> Unit,
) {
  private val mutableEntries = MutableStateFlow<List<ChatSessionEntry>>(emptyList())
  val entries = mutableEntries.asStateFlow()
  private var requestSequence = 0L
  private var archived = false
  val isArchived: Boolean get() = synchronized(publicationLock) { archived }
  val windowLimit: Int get() = entries.value.size.takeIf { it > 0 } ?: 100

  fun entry(key: String): ChatSessionEntry? = entries.value.firstOrNull { it.key == key }

  fun clear(): Unit =
    synchronized(publicationLock) {
      requestSequence += 1
      mutableEntries.value = emptyList()
      archived = false
    }

  fun reconcileSelectedOwner(): Unit =
    synchronized(publicationLock) {
      mutableEntries.value =
        reconcileGlobalObserverDigestOwner(
          entries.value,
          selection.resolveOwner(selection.key.value),
          adoptOwnerless = false,
        )
    }

  /** The cache reader owns its request generation; this owner also fences list publication. */
  fun restoreCached(
    rows: List<ChatSessionEntry>,
    gatewayScope: ChatCacheScope?,
    agentId: String,
  ): Unit =
    synchronized(publicationLock) {
      val owner = selection.captureCatalogOwner() ?: return
      if (owner.gatewayScope != gatewayScope || owner.agentId != agentId) return
      if (entries.value.isEmpty()) {
        mutableEntries.value =
          reconcileGlobalObserverDigestOwner(
            rows.map { it.copy(ownerAgentId = agentId) },
            agentId,
          )
      }
    }

  fun applySettings(
    key: ChatSessionSettingsKey,
    metadata: ChatSessionSettingsMetadata,
  ): Unit =
    synchronized(publicationLock) {
      val current = selection.captureCatalogOwner() ?: return
      if (key.gatewayScope != current.gatewayScope || key.ownerAgentId != selection.resolveOwner(key.sessionKey)) return
      mutableEntries.value = entries.value.map { if (it.key == key.sessionKey) metadata.applyTo(it) else it }
    }

  fun upsert(
    entry: ChatSessionEntry,
    preserveExistingContextUsageWithoutTotal: Boolean = false,
    replaceActiveRunIds: Boolean = false,
    clearedFields: Set<String> = emptySet(),
  ): ChatSessionEntry =
    synchronized(publicationLock) {
      val current = entries.value
      val index = current.indexOfFirst { it.key == entry.key }
      var applied = entry
      mutableEntries.value =
        if (index >= 0) {
          applied = mergeChatSessionEntry(current[index], entry, preserveExistingContextUsageWithoutTotal, replaceActiveRunIds)
          if (clearedFields.isNotEmpty()) {
            applied =
              applied.copy(
                label = if ("label" in clearedFields) null else applied.label,
              )
          }
          current.toMutableList().also { it[index] = applied }
        } else {
          listOf(entry) + current
        }
      applied
    }

  /** A visible-row deletion never implies cache/outbox deletion or retargets another owner. */
  fun remove(
    key: String,
    gatewayScope: ChatCacheScope?,
    agentId: String?,
  ): Boolean =
    synchronized(publicationLock) {
      val owner = selection.captureCatalogOwner() ?: return false
      if (owner.gatewayScope != gatewayScope || owner.agentId != agentId) return false
      mutableEntries.value = entries.value.filterNot { it.key == key }
      true
    }

  fun applyObserver(digest: SessionObserverDigest): Unit =
    synchronized(publicationLock) {
      mutableEntries.value =
        applySessionObserverDigest(
          entries.value,
          digest,
          selection.ownerAgentId.value ?: selection.resolveOwner(selection.key.value),
        )
    }

  suspend fun search(
    search: String?,
    archived: Boolean,
  ): List<ChatSessionEntry> {
    val query = search?.trim()?.takeIf(String::isNotEmpty)

    fun fallback(): List<ChatSessionEntry> = if (archived) emptyList() else filterSessionEntries(entries.value, query.orEmpty())
    val owner = selection.captureCatalogOwner() ?: return fallback()
    return try {
      val params =
        buildJsonObject {
          put("includeGlobal", JsonPrimitive(true))
          put("includeUnknown", JsonPrimitive(false))
          put("includeDerivedTitles", JsonPrimitive(true))
          put("agentId", JsonPrimitive(owner.agentId))
          put("limit", JsonPrimitive(SESSION_LIST_FETCH_LIMIT))
          query?.let { put("search", JsonPrimitive(it)) }
          if (archived) put("archived", JsonPrimitive(true))
        }
      val rows = codec.parseList(requestGateway("sessions.list", params.toString())).sessions
      if (!selection.isCurrent(owner)) return emptyList()
      rows.map { it.copy(ownerAgentId = owner.agentId) }
    } catch (err: CancellationException) {
      throw err
    } catch (_: Throwable) {
      if (selection.isCurrent(owner)) fallback() else emptyList()
    }
  }

  suspend fun refresh(
    limit: Int?,
    archived: Boolean = false,
  ): Boolean {
    val (owner, sequence) =
      synchronized(publicationLock) {
        val captured = selection.captureCatalogOwner() ?: return false
        captured to ++requestSequence
      }
    settings.beginRefresh(owner.gatewayScope)
    try {
      while (true) {
        settings.awaitPending(owner.gatewayScope)
        if (!selection.isCurrent(owner)) return false
        val revision = settings.revision(owner.gatewayScope)
        val params =
          buildJsonObject {
            put("includeGlobal", JsonPrimitive(true))
            put("includeUnknown", JsonPrimitive(false))
            put("includeDerivedTitles", JsonPrimitive(true))
            put("agentId", JsonPrimitive(owner.agentId))
            if (limit != null && limit > 0) put("limit", JsonPrimitive(limit))
            if (archived) put("archived", JsonPrimitive(true))
          }
        val parsed = codec.parseList(requestGateway("sessions.list", params.toString()))
        val rows = parsed.sessions.map { it.copy(ownerAgentId = owner.agentId) }
        if (!settings.matchesSnapshot(owner.gatewayScope, revision)) continue
        val retainedKey =
          synchronized(publicationLock) {
            if (!selection.isCurrent(owner) || sequence != requestSequence) return false
            if (!settings.matchesSnapshot(owner.gatewayScope, revision)) {
              null
            } else {
              mutableEntries.value = rows
              entry(selection.key.value)?.let { settings.applyMetadata(it.settingsMetadata()) }
              this.archived = archived
              selection.key.value.takeIf { key -> parsed.isTruncated || rows.drop(MAX_CACHED_SESSIONS).any { it.key == key } }
            }
          }
        if (!settings.matchesSnapshot(owner.gatewayScope, revision)) continue
        onSnapshotPublished(rows)
        if (!archived) persist(owner.gatewayScope, owner.agentId, rows, retainedKey)
        return true
      }
    } catch (err: CancellationException) {
      throw err
    } catch (_: Throwable) {
      return false
    } finally {
      settings.endRefresh(owner.gatewayScope)
    }
  }
}
