package ai.openclaw.app.accessibility

import ai.openclaw.app.NodeApp
import ai.openclaw.app.androiduse.AndroidUseRevocation
import ai.openclaw.app.node.MobileUiTarget
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class OpenClawAccessibilityService : AccessibilityService() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var androidUseStopOverlay: AndroidUseStopOverlay? = null
  private var targetValidationJob: Job? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    val controller = (application as NodeApp).androidUseLeaseController
    androidUseStopOverlay = AndroidUseStopOverlay.create(this, controller, serviceScope)?.also { it.attach() }
    connectionState.connect(this)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      scheduleTargetValidation()
    }
    // Coordinate staleness epoch: advance on any event that can move or change on-screen content.
    // Pure focus/hover/announcement events are intentionally excluded.
    when (event?.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
      -> recordUiChange(event)
      else -> Unit
    }
  }

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: Intent?): Boolean {
    disconnect()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    disconnect()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun disconnect() {
    targetValidationJob?.cancel()
    targetValidationJob = null
    (application as NodeApp).androidUseLeaseController.revoke(AndroidUseRevocation.AccessibilityDisconnected)
    androidUseStopOverlay?.detach()
    androidUseStopOverlay = null
    connectionState.disconnect(this)
  }

  @Suppress("DEPRECATION")
  internal fun foregroundPackageName(): String? {
    val root = rootInActiveWindow ?: return null
    return try {
      root.packageName?.toString()
    } finally {
      root.recycle()
    }
  }

  @Suppress("DEPRECATION")
  internal fun rootForTarget(target: MobileUiTarget): AccessibilityNodeInfo? {
    return when (target) {
      is MobileUiTarget.MainDisplay ->
        rootInActiveWindow?.let { root ->
          if (root.packageName?.toString() == target.packageName) {
            root
          } else {
            root.recycle()
            null
          }
        }
      is MobileUiTarget.VScreenDisplay -> {
        // AccessibilityService.windows is scoped to the default display. VScreen runs
        // on a VirtualDisplay, so resolve the exact display before inspecting package roots.
        val targetWindows = windowsOnAllDisplays[target.displayId].orEmpty()
        val candidates =
          targetWindows.mapNotNull { window ->
            try {
              val root = window.root ?: return@mapNotNull null
              if (root.packageName?.toString() != target.packageName) {
                root.recycle()
                return@mapNotNull null
              }
              AccessibilityWindowCandidate(
                value = root,
                focused = window.isFocused,
                active = window.isActive,
                layer = window.layer,
              )
            } finally {
              window.recycle()
            }
          }
        val selected = selectAccessibilityWindowCandidate(candidates)
        candidates.forEach { candidate ->
          if (candidate.value !== selected) candidate.value.recycle()
        }
        selected
      }
    }
  }

  @Suppress("DEPRECATION")
  internal fun packageNameForTarget(target: MobileUiTarget): String? {
    val root = rootForTarget(target) ?: return null
    return try {
      root.packageName?.toString()
    } finally {
      root.recycle()
    }
  }

  private fun scheduleTargetValidation() {
    targetValidationJob?.cancel()
    targetValidationJob =
      serviceScope.launch {
        // Window-state events can be delivered after a newer window is already active. Validate
        // the settled foreground root instead of trusting the event's stale package name.
        delay(TARGET_CHANGE_SETTLE_MS)
        (application as NodeApp).androidUseLeaseController.revokeIfTargetChanged(
          foregroundPackageName(),
        )
      }
  }

  companion object {
    private const val TARGET_CHANGE_SETTLE_MS = 150L
    private val connectionState = ObservableServiceInstance<OpenClawAccessibilityService>()
    private val uiEpochs = AccessibilityUiEpochs()

    val instance: OpenClawAccessibilityService?
      get() = connectionState.connection.value.instance

    internal val connection: StateFlow<AccessibilityServiceConnection<OpenClawAccessibilityService>> =
      connectionState.connection

    val isConnected: StateFlow<Boolean> = connectionState.isConnected

    val connectionGeneration: Long
      get() = connectionState.connection.value.generation

    /** Gracefully disables system access before the product hides its components. */
    internal fun disableForComponentHide() {
      connectionState.connection.value.instance?.let { service ->
        service.disableSelf()
        service.disconnect()
      }
    }

    internal fun uiEpoch(target: MobileUiTarget): Long = uiEpochs.current(target)

    internal fun advanceUiEpoch(target: MobileUiTarget): Long = uiEpochs.advance(target)

    private fun recordUiChange(event: AccessibilityEvent) {
      val packageName = event.packageName?.toString()?.takeIf(String::isNotBlank) ?: return
      val displayId =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          event.displayId
        } else {
          0
        }
      uiEpochs.advance(displayId = displayId, packageName = packageName)
    }
  }
}

/** Package-scoped epochs keep unrelated displays and system decorations from invalidating a target. */
internal class AccessibilityUiEpochs {
  private val epochs = ConcurrentHashMap<AccessibilityUiEpochKey, AtomicLong>()

  fun current(target: MobileUiTarget): Long = current(target.displayId, target.packageName)

  fun advance(target: MobileUiTarget): Long = advance(target.displayId, target.packageName)

  private fun current(
    displayId: Int,
    packageName: String,
  ): Long = epochs[AccessibilityUiEpochKey(displayId, packageName)]?.get() ?: 0

  fun advance(
    displayId: Int,
    packageName: String,
  ): Long = epochs.computeIfAbsent(AccessibilityUiEpochKey(displayId, packageName)) { AtomicLong(0) }.incrementAndGet()
}

private data class AccessibilityUiEpochKey(
  val displayId: Int,
  val packageName: String,
)

internal data class AccessibilityWindowCandidate<T>(
  val value: T,
  val focused: Boolean,
  val active: Boolean,
  val layer: Int,
)

/** Selects one deterministic target window; an equal-priority tie fails closed. */
internal fun <T> selectAccessibilityWindowCandidate(candidates: List<AccessibilityWindowCandidate<T>>): T? {
  val ranked =
    candidates.sortedWith(
      compareByDescending<AccessibilityWindowCandidate<T>> { it.focused }
        .thenByDescending { it.active }
        .thenByDescending { it.layer },
    )
  val selected = ranked.firstOrNull() ?: return null
  val ambiguous =
    ranked.drop(1).firstOrNull()?.let { next ->
      next.focused == selected.focused && next.active == selected.active && next.layer == selected.layer
    } == true
  return if (ambiguous) null else selected.value
}

internal data class AccessibilityServiceConnection<T : Any>(
  val instance: T?,
  val generation: Long,
)

internal class ObservableServiceInstance<T : Any> {
  private val mutableConnection = MutableStateFlow(AccessibilityServiceConnection<T>(instance = null, generation = 0))
  private val mutableIsConnected = MutableStateFlow(false)

  val connection: StateFlow<AccessibilityServiceConnection<T>> = mutableConnection.asStateFlow()
  val isConnected: StateFlow<Boolean> = mutableIsConnected.asStateFlow()

  fun connect(instance: T) {
    val current = mutableConnection.value
    mutableConnection.value = AccessibilityServiceConnection(instance, generation = current.generation + 1)
    mutableIsConnected.value = true
  }

  fun disconnect(instance: T) {
    val current = mutableConnection.value
    // Only the current instance may clear connectivity: during a service-replacement
    // race the old instance's teardown must not publish false after the replacement
    // already connected, or NodeRuntime would withdraw the mobile UI capability.
    if (current.instance !== instance) return
    mutableConnection.value = current.copy(instance = null)
    mutableIsConnected.value = false
  }
}
