package ai.openclaw.app.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One level-triggered worker for durable-queue delivery. */
internal class ChatDeliveryScheduler(
  private val scope: CoroutineScope,
  private val enabled: Boolean,
  private val drain: suspend () -> Unit,
) {
  private val lock = Any()
  private var requested = false
  private var worker: Job? = null

  fun requestFlush() = request()

  /** Canonical delivery proof released a successor in the queue. */
  fun requestDrain() = request()

  private fun request() {
    val next =
      synchronized(lock) {
        if (!enabled || !scope.isActive) return
        requested = true
        if (worker != null) return
        scope.launch(start = CoroutineStart.LAZY) { work() }.also { worker = it }
      }
    // A main-immediate dispatcher may start inline: no operation runs under the scheduler lock.
    next.start()
  }

  private suspend fun work() {
    val currentJob = currentCoroutineContext()[Job]
    try {
      while (currentCoroutineContext().isActive) {
        synchronized(lock) {
          if (!requested) {
            worker = null
            return
          }
          requested = false
        }
        drain()
      }
    } finally {
      synchronized(lock) {
        if (worker === currentJob) worker = null
      }
      // Unexpected failure/cancellation never restarts a pass automatically. A later explicit
      // health/admission request can re-enter; the operation owner reconciles uncertain outcomes.
    }
  }
}
