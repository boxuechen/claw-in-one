package ai.openclaw.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Queue presentation and process-start recovery. Dispatch remains a separate owner. */
internal class ChatOutboxState(
  scope: CoroutineScope,
  private val outbox: ChatCommandOutbox?,
  private val publicationLock: Any,
  private val currentGatewayScope: () -> ChatCacheScope?,
) {
  private val mutableItems = MutableStateFlow<List<ChatOutboxItem>>(emptyList())
  val items = mutableItems.asStateFlow()
  private val mutableRestored = MutableStateFlow(outbox == null)
  val restored = mutableRestored.asStateFlow()
  private var readSequence = 0L
  private val recoveryMutex = Mutex()
  private var recovered = false

  private val startup =
    outbox?.let { store ->
      scope.launch {
        if (recover()) {
          currentGatewayScope()?.let { gateway ->
            try {
              store.expireStale(gateway.gatewayId, System.currentTimeMillis())
            } catch (error: CancellationException) {
              throw error
            } catch (_: Throwable) {
              // Delivery retries expiration before claiming; startup may still show durable rows.
            }
          }
        }
        refresh()
      }
    }

  /** Every direct or queued dispatcher must cross this barrier before claiming a row. */
  suspend fun awaitRecovery(): Boolean {
    startup?.join()
    return recover()
  }

  private suspend fun recover(): Boolean =
    recoveryMutex.withLock {
      if (recovered || outbox == null) return@withLock true
      try {
        outbox.failSendingAfterRestart()
        recovered = true
        true
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        false
      }
    }

  suspend fun requireRecovery() {
    recoveryMutex.withLock { recovered = false }
  }

  fun clear(): Unit =
    synchronized(publicationLock) {
      readSequence++
      mutableItems.value = emptyList()
      mutableRestored.value = outbox == null
    }

  suspend fun refresh() {
    val store = outbox ?: return
    val (sequence, gateway) =
      synchronized(publicationLock) {
        val gateway = currentGatewayScope()
        val sequence = ++readSequence
        if (gateway == null) {
          mutableItems.value = emptyList()
          mutableRestored.value = false
        }
        sequence to gateway
      }
    gateway ?: return
    val loaded =
      try {
        store.load(gateway.gatewayId)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        null
      }
    synchronized(publicationLock) {
      if (sequence != readSequence || gateway != currentGatewayScope()) return
      if (loaded == null) {
        mutableRestored.value = false
      } else {
        mutableItems.value = loaded
        mutableRestored.value = true
      }
    }
  }
}
