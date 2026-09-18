package ai.openclaw.app.appdelivery

import java.io.Closeable

/**
 * Process-wide exclusion for the short transition from an isolated producer display to the real
 * phone display. It owns no Preview session and no Android Use lease; those components only use
 * it to prevent a new device operation from racing an exact Open app handoff.
 */
internal class DeviceHandoffGate {
  private val lock = Any()
  private var generation = 0L
  private var activeGeneration: Long? = null

  fun isActive(): Boolean = synchronized(lock) { activeGeneration != null }

  fun tryAcquire(): Lease? =
    synchronized(lock) {
      if (activeGeneration != null) return@synchronized null
      generation += 1
      activeGeneration = generation
      Lease(generation, ::release)
    }

  private fun release(expectedGeneration: Long) {
    synchronized(lock) {
      if (activeGeneration == expectedGeneration) activeGeneration = null
    }
  }

  internal class Lease(
    private val generation: Long,
    private val release: (Long) -> Unit,
  ) : Closeable {
    private var closed = false

    override fun close() {
      if (closed) return
      closed = true
      release(generation)
    }
  }
}
