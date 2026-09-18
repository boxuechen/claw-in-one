package ai.openclaw.app.node

/** Tracks the Android Use availability actually published on the desired Node connection. */
internal class AndroidUseCapabilityPublication {
  private val lock = Any()
  private var advertised: Boolean? = null

  fun needsRefresh(available: Boolean): Boolean =
    synchronized(lock) {
      advertised != available
    }

  fun record(available: Boolean) {
    synchronized(lock) {
      advertised = available
    }
  }
}
