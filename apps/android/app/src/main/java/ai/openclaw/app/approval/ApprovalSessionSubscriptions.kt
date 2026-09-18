package ai.openclaw.app.approval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns only subscription leases. Canonical approval records belong to the inbox. */
internal class ApprovalSessionSubscriptions(
  private val scope: CoroutineScope,
  private val transport: ApprovalTransport,
  private val onReplay: (ApprovalConnection, ApprovalSession, ApprovalSessionReplay) -> Unit,
  private val onIncomplete: (ApprovalConnection, ApprovalSession) -> Unit,
) {
  private val codec = ApprovalCodec()
  private val mutex = Mutex()

  @Volatile private var desired = emptySet<ApprovalSession>()

  @Volatile private var revision = 0L
  private var connection: ApprovalConnection? = null
  private var appliedRevision = -1L
  private val subscribed = mutableMapOf<ApprovalSession, String>()

  fun update(targets: Set<ApprovalSession>) {
    val bounded = targets.filter { it.key.isNotBlank() }.take(64).toSet()
    if (desired == bounded) return
    desired = bounded
    refresh()
  }

  fun reset() {
    revision++
    // The socket owns remote lease disposal. A successor reconnects explicitly.
  }

  fun refresh() {
    val expectedRevision = revision
    scope.launch {
      mutex.withLock {
        if (expectedRevision != revision) return@withLock
        val current = transport.capture() ?: return@withLock
        if (connection != current || appliedRevision != expectedRevision) {
          subscribed.clear()
          connection = current
          appliedRevision = expectedRevision
        }

        fun current(): Boolean = expectedRevision == revision && transport.capture() == current
        if (!current.methods.containsAll(METHODS)) {
          desired.forEach { onIncomplete(current, it) }
          return@withLock
        }
        for (target in subscribed.keys.toSet() - desired) {
          try {
            val canonical = subscribed[target]
            val retainedAlias = subscribed.any { (alias, stream) -> alias in desired && stream == canonical }
            if (!retainedAlias) transport.request(current, "sessions.messages.unsubscribe", params(target, false))
            subscribed.remove(target)
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (_: Throwable) {
            // Retain the lease until readback/retry or the socket closes.
          }
          if (!current()) return@withLock
        }
        for (target in desired - subscribed.keys) {
          try {
            val replay = codec.subscription(transport.request(current, "sessions.messages.subscribe", params(target, true)))
            if (!current()) return@withLock
            subscribed[target] = replay.audience
            onReplay(current, target, replay)
          } catch (cancel: CancellationException) {
            throw cancel
          } catch (_: Throwable) {
            if (!current()) return@withLock
            onIncomplete(current, target)
          }
        }
      }
    }
  }

  private fun params(
    target: ApprovalSession,
    subscribe: Boolean,
  ): String =
    buildJsonObject {
      put("key", target.key)
      target.agentId?.let { put("agentId", it) }
      if (subscribe) put("includeApprovals", true)
    }.toString()

  companion object {
    val METHODS = setOf("sessions.messages.subscribe", "sessions.messages.unsubscribe")
  }
}
