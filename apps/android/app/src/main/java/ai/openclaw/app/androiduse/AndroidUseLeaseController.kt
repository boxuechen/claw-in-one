package ai.openclaw.app.androiduse

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.node.MobileUiTarget
import ai.openclaw.app.vscreen.VScreenTarget
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val ANDROID_USE_LEASE_DURATION_MS = 5 * 60 * 1000L

internal data class AndroidUseExecutionIdentity(
  val sessionId: String,
  val runId: String,
  val generation: String,
)

internal data class AndroidUseLeaseIdentity(
  val controlId: String,
  val ownerKey: String,
  val targetPackage: String,
  val execution: AndroidUseExecutionIdentity,
  val display: AndroidUseDisplay = AndroidUseDisplay.Main,
  val assignmentId: String? = null,
  val target: MobileUiTarget = MobileUiTarget.MainDisplay(targetPackage),
)

internal enum class AndroidUseDisplay {
  Main,
  VScreen,
}

internal enum class AndroidUseActivity {
  Waiting,
  Executing,
}

internal sealed interface AndroidUseControlState {
  data class Inactive(
    val lastRevocation: AndroidUseRevocation? = null,
  ) : AndroidUseControlState

  data class Active(
    val targetPackage: String,
    val target: MobileUiTarget,
    val expiresAtElapsedRealtimeMs: Long,
    val activity: AndroidUseActivity = AndroidUseActivity.Waiting,
  ) : AndroidUseControlState
}

internal enum class AndroidUseRevocation {
  UserStop,
  ConsentDisabled,
  Expired,
  GatewayDisconnected,
  AccessibilityDisconnected,
  StopSurfaceUnavailable,
  TargetChanged,
  ExecutionFailed,
  HostRevoked,
  HumanInput,
}

/** Cancels only the leased action, not the Node request that must report its outcome. */
internal class AndroidUseControlRevoked : CancellationException("Android Use control was revoked")

internal sealed interface AndroidUseLeaseDecision {
  data class Allowed(
    val targetPackage: String,
    val expiresAtElapsedRealtimeMs: Long,
  ) : AndroidUseLeaseDecision

  data class Rejected(
    val code: String,
    val message: String,
  ) : AndroidUseLeaseDecision
}

/**
 * Process-owned authority for one short-lived Android Use control lease.
 *
 * Owner fingerprints never enter UI state. The controller persists nothing, keeps one owner
 * across all Chats, and rejects replayed control IDs until process replacement.
 */
internal class AndroidUseLeaseController(
  private val scope: CoroutineScope,
  private val consentGranted: () -> Boolean = { false },
  private val clock: () -> Long = SystemClock::elapsedRealtime,
  private val leaseDurationMs: Long = ANDROID_USE_LEASE_DURATION_MS,
  private val handoffBlocked: () -> Boolean = { false },
) {
  private val lock = Any()
  private val mutableState = MutableStateFlow<AndroidUseControlState>(AndroidUseControlState.Inactive())
  private val seenControlIds = LinkedHashSet<String>()

  private data class RunIdentity(
    val ownerKey: String,
    val sessionId: String,
    val runId: String,
  )

  private val retiredRuns = LinkedHashSet<RunIdentity>()

  private data class ActiveControl(
    val identity: AndroidUseLeaseIdentity,
    val node: GatewaySession.RequestLease,
    val operator: GatewaySession.RequestLease?,
  )

  private var activeControl: ActiveControl? = null
  private val activeIdentity: AndroidUseLeaseIdentity? get() = activeControl?.identity
  private var expiryJob: Job? = null
  private var executionJob: Job? = null
  private var stopSurfaceAvailable = false

  val state: StateFlow<AndroidUseControlState> = mutableState.asStateFlow()

  fun setStopSurfaceAvailable(available: Boolean) {
    synchronized(lock) {
      stopSurfaceAvailable = available
      if (!available && activeIdentity != null) {
        revokeLocked(AndroidUseRevocation.StopSurfaceUnavailable)
      }
    }
  }

  fun checkConsent(): AndroidUseLeaseDecision.Rejected? =
    synchronized(lock) {
      if (consentGranted()) return@synchronized null
      if (activeIdentity != null) revokeLocked(AndroidUseRevocation.ConsentDisabled)
      rejected("ANDROID_USE_DISABLED", "Enable Android Use for this phone before starting control")
    }

  fun acquire(
    node: GatewaySession.RequestLease,
    operator: GatewaySession.RequestLease?,
    identity: AndroidUseLeaseIdentity,
    foregroundPackage: String?,
  ): AndroidUseLeaseDecision =
    synchronized(lock) {
      checkAcquisition(node, identity)?.let { return@synchronized it }
      if (operator != null && (!operator.isCurrent() || operator.endpointStableId != node.endpointStableId)) {
        return@synchronized rejected("GATEWAY_DISCONNECTED", "The Android Use connection changed before acquisition")
      }
      if (identity.target is MobileUiTarget.MainDisplay && foregroundPackage != identity.targetPackage) {
        return@synchronized rejected(
          "TARGET_NOT_FOREGROUND",
          "The approved Android app is not in the foreground",
        )
      }

      rememberControlId(identity.controlId)
      val expiresAt = clock() + leaseDurationMs
      activeControl = ActiveControl(identity, node, operator)
      mutableState.value = AndroidUseControlState.Active(identity.targetPackage, identity.target, expiresAt)
      scheduleExpiry(identity.controlId, leaseDurationMs)
      AndroidUseLeaseDecision.Allowed(identity.targetPackage, expiresAt)
    }

  /** Preflight before launching an app; acquire rechecks after the asynchronous handoff. */
  fun checkAcquisition(
    node: GatewaySession.RequestLease,
    identity: AndroidUseLeaseIdentity,
  ): AndroidUseLeaseDecision.Rejected? =
    synchronized(lock) {
      expireLocked()
      when {
        !consentGranted() -> rejected("ANDROID_USE_DISABLED", "Enable Android Use for this phone before starting control")
        handoffBlocked() -> rejected("CONTROL_BUSY", "The phone is being handed to the user")
        !node.isCurrent() -> rejected("GATEWAY_DISCONNECTED", "The Android Use connection is no longer active")
        RunIdentity(identity.ownerKey, identity.execution.sessionId, identity.execution.runId) in retiredRuns ->
          rejected("RUN_STOPPED", "This Android Use run has been stopped")
        !stopSurfaceAvailable -> rejected("STOP_SURFACE_UNAVAILABLE", "Android Use cannot start because Stop is not available")
        activeIdentity != null -> rejected("CONTROL_BUSY", "Another Android Use task already controls this phone")
        identity.controlId in seenControlIds -> rejected("CONTROL_REPLAYED", "This Android Use control ID has already been consumed")
        else -> null
      }
    }

  fun authorize(
    node: GatewaySession.RequestLease,
    identity: AndroidUseLeaseIdentity,
  ): AndroidUseLeaseDecision =
    synchronized(lock) {
      expireLocked()
      if (!consentGranted()) {
        revokeLocked(AndroidUseRevocation.ConsentDisabled)
        return@synchronized rejected("ANDROID_USE_DISABLED", "Android Use was disabled")
      }
      if (activeControl?.node !== node || !node.isCurrent()) {
        return@synchronized rejected("CONTROL_INACTIVE", "Android Use control is not active on this connection")
      }
      val active =
        activeIdentity
          ?: return@synchronized rejected("CONTROL_INACTIVE", "Android Use control is not active")
      if (active != identity) {
        if (
          active.controlId == identity.controlId &&
          active.ownerKey == identity.ownerKey &&
          active.targetPackage == identity.targetPackage &&
          active.execution == identity.execution &&
          active.target != identity.target
        ) {
          revokeLocked(AndroidUseRevocation.TargetChanged)
          return@synchronized rejected(
            "TARGET_CHANGED",
            "The approved Android target changed; Android Use stopped",
          )
        }
        return@synchronized rejected(
          "CONTROL_OWNER_MISMATCH",
          "Android Use control belongs to another approved task",
        )
      }
      val state =
        mutableState.value as? AndroidUseControlState.Active
          ?: return@synchronized rejected("CONTROL_INACTIVE", "Android Use control is not active")
      AndroidUseLeaseDecision.Allowed(state.targetPackage, state.expiresAtElapsedRealtimeMs)
    }

  fun revoke(reason: AndroidUseRevocation): Boolean =
    synchronized(lock) {
      if (activeIdentity == null) return@synchronized false
      revokeLocked(reason)
      true
    }

  /** A delayed retirement belongs only to the physical connections captured by this control. */
  fun invalidateConnection(connection: GatewaySession.RequestLease): Boolean =
    synchronized(lock) {
      val control = activeControl ?: return@synchronized false
      if (control.node !== connection && control.operator !== connection) return@synchronized false
      revokeLocked(AndroidUseRevocation.GatewayDisconnected)
      true
    }

  /** Revoke-only host cleanup remains valid after preview loss and cannot touch another run. */
  fun revokeControl(
    identity: AndroidUseLeaseIdentity,
    reason: AndroidUseRevocation,
  ): Boolean =
    synchronized(lock) {
      // Retire a delayed acquisition even if its matching revoke arrived first.
      rememberControlId(identity.controlId)
      val active = activeIdentity ?: return@synchronized false
      if (active.controlId != identity.controlId ||
        active.ownerKey != identity.ownerKey ||
        active.targetPackage != identity.targetPackage ||
        active.execution != identity.execution
      ) {
        return@synchronized false
      }
      revokeLocked(reason)
      true
    }

  /** Successful run completion retires input authority without tearing down its read-only Preview. */
  fun releaseControl(identity: AndroidUseLeaseIdentity): Boolean =
    synchronized(lock) {
      rememberControlId(identity.controlId)
      val active = activeIdentity ?: return@synchronized false
      if (active.controlId != identity.controlId ||
        active.ownerKey != identity.ownerKey ||
        active.targetPackage != identity.targetPackage ||
        active.execution != identity.execution
      ) {
        return@synchronized false
      }
      retiredRuns += RunIdentity(identity.ownerKey, identity.execution.sessionId, identity.execution.runId)
      while (retiredRuns.size > 128) retiredRuns.remove(retiredRuns.first())
      releaseLocked()
      true
    }

  fun attachExecution(
    node: GatewaySession.RequestLease,
    identity: AndroidUseLeaseIdentity,
    job: Job,
  ): Boolean =
    synchronized(lock) {
      if (authorize(node, identity) !is AndroidUseLeaseDecision.Allowed) return@synchronized false
      check(executionJob == null)
      executionJob = job
      (mutableState.value as? AndroidUseControlState.Active)?.let { active ->
        mutableState.value = active.copy(activity = AndroidUseActivity.Executing)
      }
      true
    }

  fun detachExecution(job: Job) {
    synchronized(lock) {
      if (executionJob === job) {
        executionJob = null
        (mutableState.value as? AndroidUseControlState.Active)?.let { active ->
          mutableState.value = active.copy(activity = AndroidUseActivity.Waiting)
        }
      }
    }
  }

  /** Chat Stop has no control token: retire only its captured Session/run identities. */
  fun revokeRuns(
    ownerKey: String,
    sessionId: String,
    runIds: Set<String>,
  ): Boolean =
    synchronized(lock) {
      require(ownerKey.matches(Regex("[0-9a-f]{64}")) && sessionId.isNotBlank() && runIds.all(String::isNotBlank))
      for (runId in runIds) retiredRuns += RunIdentity(ownerKey, sessionId, runId)
      while (retiredRuns.size > 128) retiredRuns.remove(retiredRuns.first())
      val active = activeIdentity ?: return@synchronized true
      if (active.ownerKey != ownerKey || active.execution.sessionId != sessionId) return@synchronized true
      // Missing run identity is not permission to stop every run in a Chat.
      if (active.execution.runId !in runIds) return@synchronized false
      revokeLocked(AndroidUseRevocation.UserStop)
      true
    }

  /** Open app handoff may retire only a matching Android Use consumer, never another task. */
  fun revokeForHandoff(
    ownerKey: String,
    sessionId: String,
    runId: String,
  ): Boolean =
    synchronized(lock) {
      require(ownerKey.matches(Regex("[0-9a-f]{64}")) && sessionId.isNotBlank() && runId.isNotBlank())
      val active = activeIdentity
      if (active != null &&
        (active.ownerKey != ownerKey || active.execution.sessionId != sessionId || active.execution.runId != runId)
      ) {
        return@synchronized false
      }
      retiredRuns += RunIdentity(ownerKey, sessionId, runId)
      while (retiredRuns.size > 128) retiredRuns.remove(retiredRuns.first())
      if (active != null) revokeLocked(AndroidUseRevocation.UserStop)
      true
    }

  /** VScreen is only a target owner. Retiring it cannot revoke main-display or newer work. */
  fun revokeVScreen(targetOwner: VScreenTarget): Boolean =
    synchronized(lock) {
      val active = activeIdentity ?: return@synchronized false
      val target = active.target as? MobileUiTarget.VScreenDisplay ?: return@synchronized false
      if (target.attachmentId != targetOwner.attachmentId ||
        target.targetGeneration != targetOwner.targetGeneration ||
        target.bindingRevision != targetOwner.revision ||
        target.displayId != targetOwner.displayId
      ) {
        return@synchronized false
      }
      revokeLocked(AndroidUseRevocation.TargetChanged)
      true
    }

  /** The first exact direct-human down preempts only the matching VScreen consumer. */
  fun revokeForHumanInput(
    attachmentId: String,
    targetGeneration: Long,
  ): Boolean =
    synchronized(lock) {
      val target = activeIdentity?.target as? MobileUiTarget.VScreenDisplay ?: return@synchronized false
      if (target.attachmentId != attachmentId || target.targetGeneration != targetGeneration) return@synchronized false
      revokeLocked(AndroidUseRevocation.HumanInput)
      true
    }

  fun revokeIfTargetChanged(foregroundPackage: String?): Boolean =
    synchronized(lock) {
      val active = activeIdentity ?: return@synchronized false
      if (active.target !is MobileUiTarget.MainDisplay) return@synchronized false
      if (foregroundPackage == null || foregroundPackage == active.targetPackage) {
        return@synchronized false
      }
      revokeLocked(AndroidUseRevocation.TargetChanged)
      true
    }

  private fun expireLocked() {
    val control = activeControl ?: return
    if (!control.node.isCurrent() || control.operator?.isCurrent() == false) {
      revokeLocked(AndroidUseRevocation.GatewayDisconnected)
      return
    }
    val active = mutableState.value as? AndroidUseControlState.Active ?: return
    if (clock() >= active.expiresAtElapsedRealtimeMs) revokeLocked(AndroidUseRevocation.Expired)
  }

  private fun scheduleExpiry(
    controlId: String,
    delayMs: Long,
  ) {
    expiryJob?.cancel()
    expiryJob =
      scope.launch {
        delay(delayMs)
        synchronized(lock) {
          if (activeIdentity?.controlId == controlId) {
            revokeLocked(AndroidUseRevocation.Expired)
          }
        }
      }
  }

  private fun revokeLocked(reason: AndroidUseRevocation) {
    expiryJob?.cancel()
    expiryJob = null
    activeControl = null
    mutableState.value = AndroidUseControlState.Inactive(reason)
    val running = executionJob
    executionJob = null
    running?.cancel(AndroidUseControlRevoked())
  }

  private fun releaseLocked() {
    expiryJob?.cancel()
    expiryJob = null
    activeControl = null
    mutableState.value = AndroidUseControlState.Inactive()
    val running = executionJob
    executionJob = null
    running?.cancel(AndroidUseControlRevoked())
  }

  private fun rememberControlId(controlId: String) {
    seenControlIds += controlId
    while (seenControlIds.size > 128) {
      seenControlIds.remove(seenControlIds.first())
    }
  }

  private fun rejected(
    code: String,
    message: String,
  ) = AndroidUseLeaseDecision.Rejected(code, message)
}
