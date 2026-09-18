package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal const val SESSION_UNREAD_ACK_CAPABILITY = "session-unread-ack-contract"

/** Owns visited-session unread episodes and automatic read acknowledgements.
 * Explicit user metadata/lifecycle mutations remain separate from this asynchronous refresh.
 */
internal class ChatSessionReads(
  private val scope: CoroutineScope,
  private val publicationLock: Any,
  private val selection: ChatSessionSelection,
  private val currentGatewayScope: () -> ChatCacheScope?,
  private val gatewayAdvertisesCapability: (String) -> Boolean?,
  private val captureRequestLease: (ChatCacheScope?) -> GatewaySession.RequestLease?,
  private val onAcknowledged: () -> Unit,
  private val onFailure: (String?) -> Unit,
) {
  private data class Target(
    val gatewayScope: ChatCacheScope?,
    val key: String,
    val agentId: String,
  )

  private class Visit(
    val target: Target,
    val lease: GatewaySession.RequestLease?,
  ) {
    var observed = false
    var activationMarker: Long? = null
    var pending: Acknowledgement? = null
  }

  private class Acknowledgement(
    val visit: Visit,
    val lease: GatewaySession.RequestLease,
    val marker: Long?,
    val conditional: Boolean,
  ) {
    fun params(): String =
      buildJsonObject {
        put("key", JsonPrimitive(visit.target.key))
        put("agentId", JsonPrimitive(visit.target.agentId))
        put("unread", JsonPrimitive(false))
        if (conditional) put("expectedMarkedUnreadAt", marker?.let(::JsonPrimitive) ?: JsonNull)
      }.toString()
  }

  private var active: Visit? = null
  private var activationRevision = 0L

  fun clear(): Unit =
    synchronized(publicationLock) {
      activationRevision++
      active = null
    }

  /** Explicit navigation precedes selection publication; call without holding the
   * publication lock. Retain the physical lease for this visit's later unread episodes.
   */
  fun activate(
    key: String,
    ownerAgentId: String?,
    readEntry: () -> ChatSessionEntry?,
  ) {
    if (key.isBlank()) return
    val (revision, gatewayScope, selectionGeneration) =
      synchronized(publicationLock) {
        Triple(++activationRevision, currentGatewayScope(), selection.generation.value)
      }
    val lease = captureRequestLease(gatewayScope)
    synchronized(publicationLock) {
      if (revision != activationRevision || gatewayScope != currentGatewayScope() || selectionGeneration != selection.generation.value) return
      val entry = readEntry()
      val agent = selection.normalizeOwner(key, ownerAgentId) ?: entry?.ownerAgentId ?: selection.resolveOwner(key) ?: return
      val target = Target(gatewayScope, key, agent)
      val visit = active?.takeIf { it.target == target && it.lease?.isCurrent() == true } ?: Visit(target, lease).also { active = it }
      observe(visit, entry)
    }
  }

  /** Draft/history selection also retires a visit, even when it needs no acknowledgement. */
  fun selectionChanged(): Unit =
    synchronized(publicationLock) {
      active?.let { if (!isSelected(it)) active = null }
    }

  fun observe(entry: ChatSessionEntry?): Unit =
    synchronized(publicationLock) {
      val visit = active ?: return
      if (isSelected(visit)) observe(visit, entry)
    }

  private fun isSelected(visit: Visit): Boolean =
    visit.target.gatewayScope == currentGatewayScope() &&
      visit.target.key == selection.key.value &&
      visit.target.agentId == selection.resolveOwner(selection.key.value)

  private fun observe(
    visit: Visit,
    entry: ChatSessionEntry?,
  ) {
    if (entry == null || entry.key != visit.target.key) return
    if (entry.ownerAgentId != null && entry.ownerAgentId != visit.target.agentId) return
    if (!visit.observed) {
      visit.observed = true
      visit.activationMarker = entry.markedUnreadAt
    }
    if (entry.unread == false) {
      // Only a server-confirmed read ends the episode. Transport success alone does not.
      visit.activationMarker = null
      visit.pending = null
      return
    }
    if (entry.markedUnreadAt != null && entry.markedUnreadAt != visit.activationMarker) return
    if (entry.unread != true || visit.pending != null) return
    val lease = visit.lease?.takeIf { it.isCurrent() } ?: return
    val acknowledgement = Acknowledgement(visit, lease, entry.markedUnreadAt, gatewayAdvertisesCapability(SESSION_UNREAD_ACK_CAPABILITY) == true)
    visit.pending = acknowledgement
    scope.launch { acknowledge(acknowledgement) }
  }

  private fun isPending(acknowledgement: Acknowledgement): Boolean =
    active === acknowledgement.visit &&
      acknowledgement.visit.pending === acknowledgement &&
      acknowledgement.visit.target.gatewayScope == currentGatewayScope()

  private suspend fun acknowledge(acknowledgement: Acknowledgement) {
    var enqueued = false
    try {
      acknowledgement.lease.request("sessions.patch", acknowledgement.params(), withEnqueue = { enqueue ->
        synchronized(publicationLock) {
          if (!isPending(acknowledgement)) throw GatewayRequestNotEnqueued("Read acknowledgement retired")
          enqueue()
          enqueued = true
        }
      })
      synchronized(publicationLock) {
        if (isPending(acknowledgement) && isSelected(acknowledgement.visit) && acknowledgement.lease.isCurrent()) onAcknowledged()
      }
    } catch (error: CancellationException) {
      // Cancellation after enqueue is not proof of rejection; wait for canonical readback.
      if (!enqueued) releaseFailed(acknowledgement, null)
      throw error
    } catch (error: GatewayRequestOutcomeUnknown) {
      // Do not retry an ambiguous mutation on every unread snapshot.
      publishFailure(acknowledgement, error.message)
    } catch (error: Exception) {
      releaseFailed(acknowledgement, error.message)
    }
  }

  private fun releaseFailed(
    acknowledgement: Acknowledgement,
    message: String?,
  ): Unit =
    synchronized(publicationLock) {
      if (!isPending(acknowledgement)) return
      publishFailure(acknowledgement, message)
      acknowledgement.visit.pending = null
    }

  private fun publishFailure(
    acknowledgement: Acknowledgement,
    message: String?,
  ): Unit =
    synchronized(publicationLock) {
      if (isPending(acknowledgement) && isSelected(acknowledgement.visit) && acknowledgement.lease.isCurrent()) onFailure(message)
    }
}
