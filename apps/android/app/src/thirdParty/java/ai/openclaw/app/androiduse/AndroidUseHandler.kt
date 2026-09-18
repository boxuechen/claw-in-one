package ai.openclaw.app.androiduse

import ai.openclaw.app.accessibility.OpenClawAccessibilityService
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.node.MobileUiExecutor
import ai.openclaw.app.node.MobileUiHandler
import ai.openclaw.app.node.MobileUiTarget
import ai.openclaw.app.vscreen.VScreenTarget
import ai.openclaw.app.vscreen.VScreenTargetReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val CONTROL_ID_PATTERN = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val OPAQUE_KEY_PATTERN = Regex("[0-9a-f]{64}")
private val ANDROID_PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
private const val VSCREEN_BIND_TIMEOUT_MS = 12_000L
private const val VSCREEN_TARGET_TIMEOUT_MS = 12_000L

internal data class AndroidUseEnvelope(
  val acquire: Boolean,
  val operation: String,
  val identity: AndroidUseLeaseIdentity,
  val request: JsonObject,
)

private sealed interface AndroidUseEnvelopeParseResult {
  data class Valid(
    val envelope: AndroidUseEnvelope,
  ) : AndroidUseEnvelopeParseResult

  data class Invalid(
    val reason: String,
  ) : AndroidUseEnvelopeParseResult
}

private sealed interface AndroidUseTargetResolution {
  data class Ready(
    val target: MobileUiTarget,
  ) : AndroidUseTargetResolution

  data class Rejected(
    val code: String,
    val message: String,
  ) : AndroidUseTargetResolution
}

/** Product authorization boundary in front of the retained OpenClaw accessibility executor. */
class AndroidUseHandler internal constructor(
  private val leaseController: AndroidUseLeaseController,
  private val vscreenTargets: VScreenTargetReader,
  private val executor: MobileUiExecutor = MobileUiHandler(),
  private val targetController: AndroidUseTargetController = AndroidUseTargetAppController(),
  private val currentOperatorConnection: () -> GatewaySession.RequestLease? = { null },
  private val vscreenTargetPackage: (VScreenTarget) -> String? = { null },
  private val foregroundPackage: () -> String? = {
    OpenClawAccessibilityService.instance?.foregroundPackageName()
  },
) {
  private val invokeMutex = Mutex()

  val isConnected: StateFlow<Boolean> = executor.isConnected

  suspend fun handle(
    node: GatewaySession.RequestLease,
    paramsJson: String?,
  ): GatewaySession.InvokeResult {
    val envelope =
      when (val parsed = parseAndroidUseEnvelopeResult(paramsJson)) {
        is AndroidUseEnvelopeParseResult.Valid -> parsed.envelope
        is AndroidUseEnvelopeParseResult.Invalid -> return error(
          "INVALID_REQUEST",
          "Expected a valid ClawInOne Android Use envelope (${parsed.reason})",
        )
      }
    // Host lifecycle cleanup must not queue behind an action or depend on visible VScreen presentation.
    if (envelope.operation in setOf("release", "revoke")) {
      if (envelope.acquire || envelope.request.isNotEmpty()) return error("INVALID_REQUEST", "Invalid lifecycle-only request")
      val released =
        if (envelope.operation == "release") {
          leaseController.releaseControl(envelope.identity)
        } else {
          leaseController.revokeControl(envelope.identity, AndroidUseRevocation.HostRevoked)
        }
      return GatewaySession.InvokeResult.ok(
        buildJsonObject {
          put("status", if (envelope.operation == "release") "released" else "stopped")
          put(if (envelope.operation == "release") "released" else "revoked", released)
        }.toString(),
      )
    }
    val operator = currentOperatorConnection()?.takeIf { it.endpointStableId == node.endpointStableId }
    return handleOperation(node, operator, envelope)
  }

  private suspend fun handleOperation(
    node: GatewaySession.RequestLease,
    operator: GatewaySession.RequestLease?,
    envelope: AndroidUseEnvelope,
  ): GatewaySession.InvokeResult =
    invokeMutex.withLock {
      leaseController.checkConsent()?.let { return@withLock error(it.code, it.message) }
      if (envelope.acquire && envelope.operation != "observe") {
        return@withLock error(
          "INVALID_LEASE_TRANSITION",
          "Android Use acquire is valid only for the first observation",
        )
      }
      val target =
        when (val resolution = resolveTarget(envelope.identity)) {
          is AndroidUseTargetResolution.Ready -> resolution.target
          is AndroidUseTargetResolution.Rejected -> return@withLock error(resolution.code, resolution.message)
        }
      val identity = envelope.identity.copy(target = target)
      if (envelope.acquire) {
        leaseController.checkAcquisition(node, identity)?.let { return@withLock error(it.code, it.message) }
        if (target is MobileUiTarget.MainDisplay) {
          when (val result = targetController.bringToForeground(identity.targetPackage)) {
            AndroidUseTargetResult.Ready -> Unit
            is AndroidUseTargetResult.Failed -> return@withLock error(result.code, result.message)
          }
        } else if (target is MobileUiTarget.VScreenDisplay && !awaitVScreenTarget(target)) {
          return@withLock if (isTargetBound(target)) {
            error("VSCREEN_NOT_READY", "The Android workload was not ready for VScreen control")
          } else {
            error("TARGET_CHANGED", "The approved Android target changed before control started")
          }
        }
      }
      val decision =
        if (envelope.acquire) {
          leaseController.acquire(node, operator, identity, foregroundPackage())
        } else {
          leaseController.authorize(node, identity)
        }
      if (decision is AndroidUseLeaseDecision.Rejected) {
        return@withLock error(decision.code, decision.message)
      }
      if (envelope.operation == "stop") {
        leaseController.revoke(AndroidUseRevocation.UserStop)
        return@withLock GatewaySession.InvokeResult.ok(
          buildJsonObject { put("status", "stopped") }.toString(),
        )
      }
      if (!isTargetBound(target)) {
        leaseController.revoke(AndroidUseRevocation.TargetChanged)
        return@withLock error("TARGET_CHANGED", "The approved Android target changed; Android Use stopped")
      }

      try {
        val result =
          coroutineScope {
            val job = coroutineContext.job
            if (!leaseController.attachExecution(node, identity, job)) {
              return@coroutineScope error("CONTROL_INACTIVE", "Android Use control was revoked before execution")
            }
            try {
              if (envelope.operation == "observe") {
                executor.handleObserve(target, null)
              } else {
                executor.handleAct(target, envelope.request.toString())
              }
            } finally {
              leaseController.detachExecution(job)
            }
          }
        if (!result.ok) {
          leaseController.revoke(AndroidUseRevocation.ExecutionFailed)
          return@withLock result
        }
        if (envelope.operation == "observe") {
          val observedPackage = snapshotPackage(result.payloadJson)
          if (
            observedPackage != identity.targetPackage ||
            !isTargetBound(target) ||
            !executor.isTargetAvailable(target)
          ) {
            leaseController.revoke(AndroidUseRevocation.TargetChanged)
            return@withLock error("TARGET_CHANGED", "The observed app does not match the approved target")
          }
        } else if (!isTargetBound(target) || !executor.isTargetAvailable(target)) {
          leaseController.revoke(AndroidUseRevocation.TargetChanged)
          return@withLock error("TARGET_CHANGED", "The action left the approved Android target; Android Use stopped")
        }
        result
      } catch (cancelled: CancellationException) {
        // Local revocation cancels the child action. The still-live Node request must return
        // that result promptly instead of leaving Gateway to time out. Parent cancellation
        // and unrelated executor cancellation retain their normal cancellation semantics.
        currentCoroutineContext().ensureActive()
        if (cancelled !is AndroidUseControlRevoked) throw cancelled
        error("CONTROL_REVOKED", "Android Use control was revoked; this action was interrupted and must not be replayed")
      } catch (failure: Throwable) {
        leaseController.revoke(AndroidUseRevocation.ExecutionFailed)
        error("ANDROID_USE_FAILED", failure.message ?: "Android Use failed")
      }
    }

  private suspend fun resolveTarget(identity: AndroidUseLeaseIdentity): AndroidUseTargetResolution {
    if (identity.display == AndroidUseDisplay.Main) {
      return AndroidUseTargetResolution.Ready(MobileUiTarget.MainDisplay(identity.targetPackage))
    }
    val assignmentId =
      identity.assignmentId
        ?: return AndroidUseTargetResolution.Rejected(
          "VSCREEN_ASSIGNMENT_MISSING",
          "Android Use requires an exact VScreen app assignment",
        )
    val target =
      vscreenTargets.current()?.takeIf { it.workloadRequestId == assignmentId }
        ?: vscreenTargets.awaitAssignment(assignmentId, VSCREEN_BIND_TIMEOUT_MS)
        ?: return AndroidUseTargetResolution.Rejected(
          "VSCREEN_NOT_READY",
          "VScreen did not become ready",
        )
    val resolvedPackage =
      vscreenTargetPackage(target)
        ?: return AndroidUseTargetResolution.Rejected(
          "VSCREEN_WORKLOAD_UNAVAILABLE",
          "Android Use cannot resolve the current VScreen workload",
        )
    if (resolvedPackage != identity.targetPackage) {
      return AndroidUseTargetResolution.Rejected(
        "VSCREEN_TARGET_MISMATCH",
        "Android Use must target the workload shown in VScreen",
      )
    }
    return AndroidUseTargetResolution.Ready(target.toMobileUiTarget(resolvedPackage))
  }

  private fun isTargetBound(target: MobileUiTarget): Boolean =
    when (target) {
      is MobileUiTarget.MainDisplay -> foregroundPackage() == target.packageName
      is MobileUiTarget.VScreenDisplay ->
        vscreenTargets.current()?.let { current ->
          vscreenTargetPackage(current)?.let { current.toMobileUiTarget(it) }
        } == target
    }

  private suspend fun awaitVScreenTarget(target: MobileUiTarget.VScreenDisplay): Boolean =
    withTimeoutOrNull(VSCREEN_TARGET_TIMEOUT_MS) {
      while (isTargetBound(target) && !executor.isTargetAvailable(target)) delay(100)
      isTargetBound(target) && executor.isTargetAvailable(target)
    } ?: false

  private fun error(
    code: String,
    message: String,
  ): GatewaySession.InvokeResult = GatewaySession.InvokeResult.error(code, "$code: $message")
}

private fun VScreenTarget.toMobileUiTarget(packageName: String): MobileUiTarget.VScreenDisplay =
  MobileUiTarget.VScreenDisplay(
    packageName = packageName,
    displayId = displayId,
    attachmentId = attachmentId,
    targetGeneration = targetGeneration,
    bindingRevision = revision,
  )

internal fun parseAndroidUseEnvelope(paramsJson: String?): AndroidUseEnvelope? =
  when (val parsed = parseAndroidUseEnvelopeResult(paramsJson)) {
    is AndroidUseEnvelopeParseResult.Valid -> parsed.envelope
    is AndroidUseEnvelopeParseResult.Invalid -> null
  }

private fun parseAndroidUseEnvelopeResult(paramsJson: String?): AndroidUseEnvelopeParseResult {
  val params =
    paramsJson
      ?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
      ?: return AndroidUseEnvelopeParseResult.Invalid("paramsJSON must be a JSON object")
  val fields = setOf("protocolVersion", "acquire", "operation", "controlId", "ownerKey", "targetPackage", "display", "assignmentId", "request", "executionKey", "sessionId", "runId", "authorizationNonce")
  if (params.keys != fields) return AndroidUseEnvelopeParseResult.Invalid("unexpected or missing envelope fields")
  if (params.int("protocolVersion") != ANDROID_USE_PROTOCOL_VERSION) {
    return AndroidUseEnvelopeParseResult.Invalid("protocolVersion must be $ANDROID_USE_PROTOCOL_VERSION")
  }
  val acquire =
    params.boolean("acquire")
      ?: return AndroidUseEnvelopeParseResult.Invalid("acquire must be a boolean")
  val operation =
    params.string("operation")
      ?: return AndroidUseEnvelopeParseResult.Invalid("operation must be a non-empty string")
  if (operation !in setOf("observe", "activate", "set_text", "scroll", "tap", "swipe", "back", "wait", "stop", "release", "revoke")) {
    return AndroidUseEnvelopeParseResult.Invalid("operation is unsupported")
  }
  val controlId =
    params
      .string("controlId")
      ?.takeIf { it.matches(CONTROL_ID_PATTERN) }
      ?: return AndroidUseEnvelopeParseResult.Invalid("controlId must be a UUID")
  val ownerKey =
    params
      .string("ownerKey")
      ?.takeIf { it.matches(OPAQUE_KEY_PATTERN) }
      ?: return AndroidUseEnvelopeParseResult.Invalid("ownerKey must be a lowercase SHA-256 digest")
  val targetPackage =
    params
      .string("targetPackage")
      ?.takeIf { it.length <= 255 && it.matches(ANDROID_PACKAGE_PATTERN) }
      ?: return AndroidUseEnvelopeParseResult.Invalid("targetPackage must be an exact Android package")
  val display =
    when (params.string("display")) {
      "main" -> AndroidUseDisplay.Main
      "vscreen" -> AndroidUseDisplay.VScreen
      else -> return AndroidUseEnvelopeParseResult.Invalid("display must be main or vscreen")
    }
  val assignmentId = params.string("assignmentId")
  if (
    (display == AndroidUseDisplay.Main && assignmentId != null) ||
    (display == AndroidUseDisplay.VScreen && assignmentId?.matches(CONTROL_ID_PATTERN) != true)
  ) {
    return AndroidUseEnvelopeParseResult.Invalid("assignmentId must be null for main or a UUID for vscreen")
  }
  val executionKey =
    params.string("executionKey")?.takeIf { it.matches(CONTROL_ID_PATTERN) }
      ?: return AndroidUseEnvelopeParseResult.Invalid("executionKey must be a UUID")
  val sessionId = params.string("sessionId") ?: return AndroidUseEnvelopeParseResult.Invalid("sessionId is required")
  val runId = params.string("runId") ?: return AndroidUseEnvelopeParseResult.Invalid("runId is required")
  if (params.string("authorizationNonce")?.matches(CONTROL_ID_PATTERN) != true) {
    return AndroidUseEnvelopeParseResult.Invalid("authorizationNonce must be a UUID")
  }
  val request =
    params["request"] as? JsonObject
      ?: return AndroidUseEnvelopeParseResult.Invalid("request must be a JSON object")
  return AndroidUseEnvelopeParseResult.Valid(
    AndroidUseEnvelope(
      acquire = acquire,
      operation = operation,
      identity =
        AndroidUseLeaseIdentity(
          controlId = controlId,
          ownerKey = ownerKey,
          targetPackage = targetPackage,
          execution = AndroidUseExecutionIdentity(sessionId, runId, executionKey),
          display = display,
          assignmentId = assignmentId,
        ),
      request = request,
    ),
  )
}

private fun snapshotPackage(payloadJson: String?): String? {
  val payload = runCatching { Json.parseToJsonElement(payloadJson ?: return null).jsonObject }.getOrNull()
  return payload?.string("package")
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.contentOrNull
    ?.takeIf(String::isNotBlank)

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()

private fun JsonObject.boolean(key: String): Boolean? =
  when ((this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull) {
    "true" -> true
    "false" -> false
    else -> null
  }
