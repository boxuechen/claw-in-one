package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayMethod
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.i18n.nativeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

internal data class GatewayWizardLaunch(
  val ownerId: String,
  val sessionId: String,
  val method: GatewayMethod,
  val params: String,
) {
  init {
    require(ownerId.isNotBlank())
    require(sessionId.isNotBlank())
    require(method in WIZARD_START_METHODS)
  }
}

internal sealed interface GatewayWizardState {
  data object Idle : GatewayWizardState

  data class Starting(
    val ownerId: String,
    val sessionId: String,
  ) : GatewayWizardState

  data class Active(
    val ownerId: String,
    val sessionId: String,
    val step: GatewayWizardStep,
    val submitting: Boolean = false,
    val waitingForBrowser: Boolean = false,
  ) : GatewayWizardState

  data class WaitingForConnection(
    val ownerId: String,
    val sessionId: String,
  ) : GatewayWizardState

  data class Recovering(
    val ownerId: String,
    val sessionId: String,
    val checking: Boolean = false,
  ) : GatewayWizardState

  data class Cancelling(
    val ownerId: String,
    val sessionId: String,
    val submitting: Boolean = false,
  ) : GatewayWizardState

  data class Finished(
    val ownerId: String,
    val result: GatewayWizardResult,
  ) : GatewayWizardState

  data class Cancelled(
    val ownerId: String,
  ) : GatewayWizardState

  data class Failed(
    val ownerId: String,
    val message: String,
  ) : GatewayWizardState
}

/** Exact OpenClaw 9.4 wizard-session driver. Gateway owns prompts, effects and credentials. */
internal class GatewayWizardController(
  private val scope: CoroutineScope,
  private val transport: AiGatewayTransport,
  private val json: Json,
  private val actionsEnabled: Boolean = true,
) {
  private val mutableState = MutableStateFlow<GatewayWizardState>(GatewayWizardState.Idle)
  val state = mutableState.asStateFlow()
  private val operationSequence = AtomicLong()
  private var pendingLaunch: GatewayWizardLaunch? = null
  private var browserSessionId: String? = null

  fun start(launch: GatewayWizardLaunch) {
    if (!actionsEnabled || mutableState.value.blocksNewWizard()) return
    browserSessionId = null
    pendingLaunch = launch
    val connection = transport.capture()
    if (connection == null) {
      mutableState.value = GatewayWizardState.WaitingForConnection(launch.ownerId, launch.sessionId)
      return
    }
    startOnConnection(launch, connection)
  }

  /** Adopts an opaque Gateway-owned session after Android process recreation. */
  fun restore(
    ownerId: String,
    sessionId: String,
  ) {
    if (!actionsEnabled || mutableState.value.blocksNewWizard()) return
    require(ownerId.isNotBlank())
    require(sessionId.isNotBlank())
    pendingLaunch = null
    mutableState.value =
      if (transport.capture() == null) {
        GatewayWizardState.WaitingForConnection(ownerId, sessionId)
      } else {
        GatewayWizardState.Recovering(ownerId, sessionId)
      }
  }

  fun answer(
    stepId: String,
    value: JsonElement?,
  ) {
    if (!actionsEnabled) return
    val active = mutableState.value as? GatewayWizardState.Active ?: return
    if (active.submitting || active.step.id != stepId) return
    val connection = transport.capture()
    if (connection == null) {
      mutableState.value = GatewayWizardState.WaitingForConnection(active.ownerId, active.sessionId)
      return
    }
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = active.copy(submitting = true)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val raw =
          transport.request(
            connection,
            GatewayMethod.WizardNext.rawValue,
            wizardNextParams(active.sessionId, stepId, value),
            WIZARD_STEP_TIMEOUT_MS,
          )
        val result = parseGatewayWizardNextResult(raw, json) ?: error("Malformed wizard result")
        publish(connection, sequence) { applyResult(active.ownerId, active.sessionId, result) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: GatewayRequestOutcomeUnknown) {
        publish(connection, sequence) { GatewayWizardState.Recovering(active.ownerId, active.sessionId) }
      } catch (rejected: GatewayRequestRejected) {
        publish(connection, sequence) {
          GatewayWizardState.Failed(active.ownerId, rejected.toWizardMessage(nativeString("Could not continue this setup step.")))
        }
      } catch (_: Throwable) {
        publish(connection, sequence) { GatewayWizardState.Failed(active.ownerId, nativeString("Could not continue this setup step.")) }
      }
    }
  }

  fun browserOpened(stepId: String) {
    if (!actionsEnabled) return
    val active = mutableState.value as? GatewayWizardState.Active ?: return
    if (active.step.id != stepId || active.step.externalUrl == null) return
    browserSessionId = active.sessionId
    mutableState.value = active.copy(waitingForBrowser = true)
  }

  /** Pulls the current prompt or terminal result without replaying an answer. */
  fun reconcile() {
    if (!actionsEnabled) return
    when (val current = mutableState.value) {
      is GatewayWizardState.WaitingForConnection -> {
        val connection = transport.capture() ?: return
        val launch = pendingLaunch
        if (launch != null) {
          startOnConnection(launch, connection)
        } else {
          pullNext(WizardSession(current.ownerId, current.sessionId), connection)
        }
      }
      is GatewayWizardState.Recovering -> {
        if (current.checking) return
        val connection = transport.capture()
        if (connection == null) {
          mutableState.value = GatewayWizardState.WaitingForConnection(current.ownerId, current.sessionId)
        } else {
          pullNext(WizardSession(current.ownerId, current.sessionId), connection)
        }
      }
      is GatewayWizardState.Active -> {
        if (
          (current.step.type != GatewayWizardStepType.Progress && !current.waitingForBrowser) ||
          current.submitting
        ) {
          return
        }
        val connection = transport.capture()
        if (connection == null) {
          mutableState.value = GatewayWizardState.WaitingForConnection(current.ownerId, current.sessionId)
        } else {
          pullNext(WizardSession(current.ownerId, current.sessionId), connection)
        }
      }
      is GatewayWizardState.Cancelling -> {
        if (!current.submitting) cancelOnConnection(current)
      }
      GatewayWizardState.Idle,
      is GatewayWizardState.Starting,
      is GatewayWizardState.Finished,
      is GatewayWizardState.Cancelled,
      is GatewayWizardState.Failed,
      -> Unit
    }
  }

  fun cancel() {
    if (!actionsEnabled) return
    val session = mutableState.value.session() ?: return
    browserSessionId = null
    operationSequence.incrementAndGet()
    if (pendingLaunch != null && mutableState.value is GatewayWizardState.WaitingForConnection) {
      pendingLaunch = null
      mutableState.value = GatewayWizardState.Cancelled(session.ownerId)
      return
    }
    val cancelling = GatewayWizardState.Cancelling(session.ownerId, session.sessionId)
    mutableState.value = cancelling
    cancelOnConnection(cancelling)
  }

  fun clear(ownerId: String? = null) {
    if (ownerId != null && mutableState.value.ownerId() != ownerId) return
    operationSequence.incrementAndGet()
    browserSessionId = null
    pendingLaunch = null
    mutableState.value = GatewayWizardState.Idle
  }

  fun onConnectionChanged() {
    val current = mutableState.value
    val session = current.session() ?: return
    operationSequence.incrementAndGet()
    val connection = transport.capture()
    if (connection == null) {
      mutableState.value =
        if (current is GatewayWizardState.Cancelling) {
          current.copy(submitting = false)
        } else {
          GatewayWizardState.WaitingForConnection(session.ownerId, session.sessionId)
        }
      return
    }
    when {
      current is GatewayWizardState.Cancelling -> {
        val cancelling = current.copy(submitting = false)
        mutableState.value = cancelling
        cancelOnConnection(cancelling)
      }
      pendingLaunch != null -> startOnConnection(requireNotNull(pendingLaunch), connection)
      else -> {
        mutableState.value = GatewayWizardState.Recovering(session.ownerId, session.sessionId)
        reconcile()
      }
    }
  }

  private fun startOnConnection(
    launch: GatewayWizardLaunch,
    connection: AiGatewayConnection,
  ) {
    browserSessionId = null
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = GatewayWizardState.Starting(launch.ownerId, launch.sessionId)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      var ownershipAccepted = false
      try {
        val raw = transport.request(connection, launch.method.rawValue, launch.params, WIZARD_STEP_TIMEOUT_MS)
        val result = parseGatewayWizardStartResult(raw, json) ?: error("Malformed wizard start result")
        require(result.sessionId == launch.sessionId) { "Wizard session mismatch" }
        require(!result.done && result.status == GatewayWizardRunStatus.Running && result.step == null) {
          "Unsupported wizard start result"
        }
        ownershipAccepted = true
        if (!publish(connection, sequence) {
            pendingLaunch = null
            mutableState.value
          }
        ) {
          return@launch
        }
        val nextRaw =
          transport.request(
            connection,
            GatewayMethod.WizardNext.rawValue,
            wizardNextParams(launch.sessionId),
            WIZARD_STEP_TIMEOUT_MS,
          )
        val next = parseGatewayWizardNextResult(nextRaw, json) ?: error("Malformed initial wizard step")
        publish(connection, sequence) { applyResult(launch.ownerId, launch.sessionId, next) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: GatewayRequestOutcomeUnknown) {
        publish(connection, sequence) {
          pendingLaunch = null
          GatewayWizardState.Recovering(launch.ownerId, launch.sessionId)
        }
      } catch (rejected: GatewayRequestRejected) {
        publish(connection, sequence) {
          pendingLaunch = null
          GatewayWizardState.Failed(
            launch.ownerId,
            rejected.toWizardMessage(
              nativeString(if (ownershipAccepted) "Could not load this setup step." else "Could not start this setup step."),
            ),
          )
        }
      } catch (_: Throwable) {
        publish(connection, sequence) {
          pendingLaunch = null
          GatewayWizardState.Failed(
            launch.ownerId,
            nativeString(if (ownershipAccepted) "Could not load this setup step." else "Could not start this setup step."),
          )
        }
      }
    }
  }

  private fun pullNext(
    session: WizardSession,
    connection: AiGatewayConnection,
  ) {
    val previous = mutableState.value
    val sequence = operationSequence.incrementAndGet()
    mutableState.value =
      when (previous) {
        is GatewayWizardState.Active -> previous.copy(submitting = true)
        else -> GatewayWizardState.Recovering(session.ownerId, session.sessionId, checking = true)
      }
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val raw =
          transport.request(
            connection,
            GatewayMethod.WizardNext.rawValue,
            wizardNextParams(session.sessionId),
            WIZARD_LONG_POLL_TIMEOUT_MS,
          )
        val result = parseGatewayWizardNextResult(raw, json) ?: error("Malformed wizard result")
        publish(connection, sequence) { applyResult(session.ownerId, session.sessionId, result) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: GatewayRequestOutcomeUnknown) {
        publish(connection, sequence) {
          (previous as? GatewayWizardState.Active)?.copy(submitting = false)
            ?: GatewayWizardState.Recovering(session.ownerId, session.sessionId)
        }
      } catch (rejected: GatewayRequestRejected) {
        publish(connection, sequence) {
          GatewayWizardState.Failed(session.ownerId, rejected.toWizardMessage(nativeString("This setup session is no longer available.")))
        }
      } catch (_: Throwable) {
        publish(connection, sequence) { GatewayWizardState.Failed(session.ownerId, nativeString("This setup session is no longer available.")) }
      }
    }
  }

  private fun cancelOnConnection(cancelling: GatewayWizardState.Cancelling) {
    val connection = transport.capture() ?: return
    if (cancelling.submitting) return
    val sequence = operationSequence.incrementAndGet()
    mutableState.value = cancelling.copy(submitting = true)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        val raw =
          transport.request(
            connection,
            GatewayMethod.WizardCancel.rawValue,
            wizardSessionParams(cancelling.sessionId),
            WIZARD_STEP_TIMEOUT_MS,
          )
        val status = parseGatewayWizardStatus(raw, json) ?: error("Malformed wizard cancellation")
        publish(connection, sequence) {
          when (status.status) {
            GatewayWizardRunStatus.Cancelled -> GatewayWizardState.Cancelled(cancelling.ownerId)
            GatewayWizardRunStatus.Done ->
              GatewayWizardState.Finished(
                cancelling.ownerId,
                GatewayWizardResult(null, true, GatewayWizardRunStatus.Done, null, status.error, null, null),
              )
            GatewayWizardRunStatus.Error -> GatewayWizardState.Failed(cancelling.ownerId, status.error ?: nativeString("Setup could not be completed."))
            GatewayWizardRunStatus.Running -> GatewayWizardState.Recovering(cancelling.ownerId, cancelling.sessionId)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: GatewayRequestOutcomeUnknown) {
        publish(connection, sequence) { cancelling.copy(submitting = false) }
      } catch (rejected: GatewayRequestRejected) {
        publish(connection, sequence) {
          if (rejected.isWizardNotFound()) {
            GatewayWizardState.Cancelled(cancelling.ownerId)
          } else {
            GatewayWizardState.Failed(cancelling.ownerId, rejected.toWizardMessage(nativeString("Could not cancel this setup session.")))
          }
        }
      } catch (_: Throwable) {
        publish(connection, sequence) { GatewayWizardState.Failed(cancelling.ownerId, nativeString("Could not cancel this setup session.")) }
      }
    }
  }

  private fun applyResult(
    ownerId: String,
    sessionId: String,
    result: GatewayWizardResult,
  ): GatewayWizardState {
    val next =
      when {
        result.done || result.status == GatewayWizardRunStatus.Done -> GatewayWizardState.Finished(ownerId, result)
        result.status == GatewayWizardRunStatus.Cancelled -> GatewayWizardState.Cancelled(ownerId)
        result.status == GatewayWizardRunStatus.Error -> GatewayWizardState.Failed(ownerId, result.error ?: nativeString("Setup could not be completed."))
        result.step != null ->
          GatewayWizardState.Active(
            ownerId,
            sessionId,
            result.step,
            waitingForBrowser = browserSessionId == sessionId,
          )
        else -> GatewayWizardState.Recovering(ownerId, sessionId)
      }
    if (next is GatewayWizardState.Finished || next is GatewayWizardState.Cancelled || next is GatewayWizardState.Failed) {
      browserSessionId = null
    }
    return next
  }

  private fun publish(
    connection: AiGatewayConnection,
    sequence: Long,
    state: () -> GatewayWizardState,
  ): Boolean {
    if (operationSequence.get() != sequence) return false
    var published = false
    transport.publish(connection) {
      if (operationSequence.get() == sequence) {
        mutableState.value = state()
        published = true
      }
    }
    return published
  }

  private companion object {
    const val WIZARD_STEP_TIMEOUT_MS = 60_000L
    const val WIZARD_LONG_POLL_TIMEOUT_MS = 30_000L
  }
}

private data class WizardSession(
  val ownerId: String,
  val sessionId: String,
)

private fun GatewayWizardState.session(): WizardSession? =
  when (this) {
    is GatewayWizardState.Starting -> WizardSession(ownerId, sessionId)
    is GatewayWizardState.Active -> WizardSession(ownerId, sessionId)
    is GatewayWizardState.WaitingForConnection -> WizardSession(ownerId, sessionId)
    is GatewayWizardState.Recovering -> WizardSession(ownerId, sessionId)
    is GatewayWizardState.Cancelling -> WizardSession(ownerId, sessionId)
    is GatewayWizardState.Idle,
    is GatewayWizardState.Finished,
    is GatewayWizardState.Cancelled,
    is GatewayWizardState.Failed,
    -> null
  }

private fun GatewayWizardState.ownerId(): String? =
  when (this) {
    GatewayWizardState.Idle -> null
    is GatewayWizardState.Starting -> ownerId
    is GatewayWizardState.Active -> ownerId
    is GatewayWizardState.WaitingForConnection -> ownerId
    is GatewayWizardState.Recovering -> ownerId
    is GatewayWizardState.Cancelling -> ownerId
    is GatewayWizardState.Finished -> ownerId
    is GatewayWizardState.Cancelled -> ownerId
    is GatewayWizardState.Failed -> ownerId
  }

private fun GatewayWizardState.blocksNewWizard(): Boolean =
  this is GatewayWizardState.Starting ||
    this is GatewayWizardState.Active ||
    this is GatewayWizardState.WaitingForConnection ||
    this is GatewayWizardState.Recovering ||
    this is GatewayWizardState.Cancelling

private fun GatewayRequestRejected.isWizardNotFound(): Boolean = gatewayError.details?.code == "WIZARD_NOT_FOUND"

private fun GatewayRequestRejected.toWizardMessage(fallback: String): String =
  when (gatewayError.details?.code) {
    "SETUP_ADMISSION_BUSY" -> nativeString("Another OpenClaw setup is already in progress. Finish or cancel it, then try again.")
    "WIZARD_NOT_FOUND" -> nativeString("This setup session expired. Start again.")
    else -> gatewayError.message.trim().takeIf(String::isNotEmpty) ?: fallback
  }

private val WIZARD_START_METHODS =
  setOf(
    GatewayMethod.OpenclawSetupActivateStart,
    GatewayMethod.OpenclawSetupAuthStart,
    GatewayMethod.OpenclawSetupPrepareStart,
  )
