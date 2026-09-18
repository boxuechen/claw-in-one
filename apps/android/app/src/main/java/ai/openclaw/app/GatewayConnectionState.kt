package ai.openclaw.app

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.i18n.nativeString

data class GatewayConnectionProblem(
  val code: String?,
  val message: String,
  val reason: String?,
  val requestId: String?,
  val recommendedNextStep: String?,
  val pauseReconnect: Boolean,
  val retryable: Boolean,
  val clientMinProtocol: Int? = null,
  val clientMaxProtocol: Int? = null,
  val expectedProtocol: Int? = null,
  val minimumProbeProtocol: Int? = null,
) {
  val isPairingRequired: Boolean = code == "PAIRING_REQUIRED"
  val canAutoRetry: Boolean =
    isPairingRequired &&
      (
        retryable ||
          !pauseReconnect ||
          recommendedNextStep == "wait_then_retry"
      )
}

data class GatewayConnectionDisplay(
  val isConnected: Boolean,
  val statusText: String,
  val problem: GatewayConnectionProblem?,
)

internal const val GATEWAY_STATUS_OFFLINE = "Offline"
private const val GATEWAY_STATUS_CONNECTED = "Connected"
private const val GATEWAY_STATUS_NODE_OFFLINE = "Connected (node offline)"
private const val GATEWAY_STATUS_OPERATOR_OFFLINE = "Connected (operator offline)"

private fun gatewayOperatorConnectionState(operator: String): String = "Connected (operator: $operator)"

internal fun gatewayConnectionStatusForDisplay(statusText: String): String {
  val status = statusText.trim()
  return when {
    status.isEmpty() || status == GATEWAY_STATUS_OFFLINE -> nativeString("Offline")
    status == "Gateway offline" -> nativeString("Gateway offline")
    status == GATEWAY_STATUS_CONNECTED -> nativeString("Connected")
    status == GATEWAY_STATUS_NODE_OFFLINE -> nativeString("Connected (node offline)")
    status == GATEWAY_STATUS_OPERATOR_OFFLINE -> nativeString("Connected (operator offline)")
    status == "Connecting…" -> nativeString("Connecting…")
    status == "Reconnecting…" -> nativeString("Reconnecting…")
    status.startsWith("Connected (operator: ") && status.endsWith(")") ->
      nativeString(
        "Connected (operator: \$operator)",
        status.removePrefix("Connected (operator: ").dropLast(1),
      )
    else -> status
  }
}

internal fun gatewayProblemAfterDisconnect(
  problem: GatewayConnectionProblem?,
  statusText: String,
): GatewayConnectionProblem? =
  // Automatic bootstrap pairing retries need their approval guidance until success or a different failure.
  problem?.takeIf { statusText == "Reconnecting…" && it.canAutoRetry }

internal fun gatewayConnectionDisplay(
  operatorConnected: Boolean,
  nodeConnected: Boolean,
  operatorStatusText: String,
  nodeStatusText: String,
  operatorProblem: GatewayConnectionProblem?,
  nodeProblem: GatewayConnectionProblem?,
): GatewayConnectionDisplay {
  val operator = operatorStatusText.trim()
  val node = nodeStatusText.trim()
  return when {
    operatorConnected && nodeConnected -> GatewayConnectionDisplay(true, GATEWAY_STATUS_CONNECTED, null)
    operatorConnected -> GatewayConnectionDisplay(true, GATEWAY_STATUS_NODE_OFFLINE, nodeProblem)
    nodeConnected ->
      GatewayConnectionDisplay(
        isConnected = false,
        statusText =
          if (operator.isNotEmpty() && operator != "Offline") {
            gatewayOperatorConnectionState(operator)
          } else {
            GATEWAY_STATUS_OPERATOR_OFFLINE
          },
        problem = operatorProblem,
      )
    operator.isNotBlank() && operator != "Offline" -> GatewayConnectionDisplay(false, operator, operatorProblem)
    else -> GatewayConnectionDisplay(false, node, nodeProblem)
  }
}

internal fun gatewayConnectionProblem(
  error: GatewaySession.ErrorShape,
  pauseReconnect: Boolean,
): GatewayConnectionProblem {
  val details = error.details
  return GatewayConnectionProblem(
    code = details?.code ?: error.code,
    message = error.message,
    reason = details?.reason,
    requestId = details?.requestId,
    recommendedNextStep = details?.recommendedNextStep,
    pauseReconnect = pauseReconnect || details?.pauseReconnect == true,
    retryable = details?.retryable == true,
    clientMinProtocol = details?.clientMinProtocol,
    clientMaxProtocol = details?.clientMaxProtocol,
    expectedProtocol = details?.expectedProtocol,
    minimumProbeProtocol = details?.minimumProbeProtocol,
  )
}
