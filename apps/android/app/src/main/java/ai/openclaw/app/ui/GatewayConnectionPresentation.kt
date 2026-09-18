package ai.openclaw.app.ui

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.GatewayConnectionProblem
import ai.openclaw.app.gatewayConnectionStatusForDisplay
import ai.openclaw.app.i18n.nativeString

/** Normalizes blank Gateway status text for retained connection surfaces. */
internal fun gatewayStatusForDisplay(statusText: String): String = gatewayConnectionStatusForDisplay(statusText)

/** Converts raw Gateway connection state into a stable compact label for status surfaces. */
internal fun gatewayStatusLabel(
  statusText: String,
  isConnected: Boolean,
  gatewayConnectionProblem: GatewayConnectionProblem? = null,
): String {
  val status = statusText.trim().lowercase()
  return when {
    status == "connected (node offline)" -> nativeString("Connected (node offline)")
    status == "connected (operator offline)" -> nativeString("Connected (operator offline)")
    isConnected -> nativeString("Ready")
    status.contains("connecting") || status.contains("reconnecting") -> nativeString("Connecting...")
    status.contains("pair") -> nativeString("Pairing needed")
    status.contains("auth") || status.contains("device identity") -> gatewayAuthRecoveryLabel(gatewayConnectionProblem) ?: nativeString("Authentication needed")
    status.contains("fingerprint verification timed out") -> nativeString("TLS timed out")
    status.contains("no tls endpoint") -> nativeString("No TLS endpoint")
    status.contains("certificate") || status.contains("tls") -> nativeString("Certificate review needed")
    status.contains("failed") || status.contains("error") || status.contains("offline") || status.contains("not connected") -> nativeString("Cannot reach gateway")
    status.isBlank() -> nativeString("Not connected")
    else -> nativeString("Not connected")
  }
}

internal fun gatewayStatusLabel(display: GatewayConnectionDisplay): String = gatewayStatusLabel(display.statusText, display.isConnected, display.problem)

/** Maps structured Gateway auth failures to compact labels used by recovery surfaces. */
internal fun gatewayAuthRecoveryLabel(problem: GatewayConnectionProblem?): String? {
  val kind =
    when (problem?.code) {
      "AUTH_BOOTSTRAP_TOKEN_INVALID" -> GatewayAuthRecoveryLabelKind.SETUP_CODE_EXPIRED
      "AUTH_TOKEN_MISSING" -> GatewayAuthRecoveryLabelKind.TOKEN_NEEDED
      "AUTH_TOKEN_NOT_CONFIGURED" -> GatewayAuthRecoveryLabelKind.TOKEN_NOT_CONFIGURED
      "AUTH_PASSWORD_MISSING" -> GatewayAuthRecoveryLabelKind.PASSWORD_NEEDED
      "AUTH_PASSWORD_MISMATCH" -> GatewayAuthRecoveryLabelKind.PASSWORD_INVALID
      "AUTH_PASSWORD_NOT_CONFIGURED" -> GatewayAuthRecoveryLabelKind.PASSWORD_NOT_CONFIGURED
      "AUTH_SCOPE_MISMATCH" -> GatewayAuthRecoveryLabelKind.ACCESS_NEEDS_REVIEW
      "AUTH_TOKEN_MISMATCH",
      "AUTH_DEVICE_TOKEN_MISMATCH",
      -> GatewayAuthRecoveryLabelKind.SAVED_AUTH_INVALID
      "CONTROL_UI_DEVICE_IDENTITY_REQUIRED",
      "DEVICE_IDENTITY_REQUIRED",
      -> GatewayAuthRecoveryLabelKind.DEVICE_IDENTITY_REQUIRED
      else -> return null
    }
  return gatewayAuthRecoveryLabel(kind)
}

private enum class GatewayAuthRecoveryLabelKind {
  SETUP_CODE_EXPIRED,
  TOKEN_NEEDED,
  TOKEN_NOT_CONFIGURED,
  PASSWORD_NEEDED,
  PASSWORD_INVALID,
  PASSWORD_NOT_CONFIGURED,
  ACCESS_NEEDS_REVIEW,
  SAVED_AUTH_INVALID,
  DEVICE_IDENTITY_REQUIRED,
}

private fun gatewayAuthRecoveryLabel(kind: GatewayAuthRecoveryLabelKind): String =
  when (kind) {
    GatewayAuthRecoveryLabelKind.SETUP_CODE_EXPIRED -> nativeString("Setup code expired")
    GatewayAuthRecoveryLabelKind.TOKEN_NEEDED -> nativeString("Gateway token needed")
    GatewayAuthRecoveryLabelKind.TOKEN_NOT_CONFIGURED -> nativeString("Gateway token not configured")
    GatewayAuthRecoveryLabelKind.PASSWORD_NEEDED -> nativeString("Gateway password needed")
    GatewayAuthRecoveryLabelKind.PASSWORD_INVALID -> nativeString("Gateway password invalid")
    GatewayAuthRecoveryLabelKind.PASSWORD_NOT_CONFIGURED -> nativeString("Gateway password not configured")
    GatewayAuthRecoveryLabelKind.ACCESS_NEEDS_REVIEW -> nativeString("Gateway access needs review")
    GatewayAuthRecoveryLabelKind.SAVED_AUTH_INVALID -> nativeString("Saved auth invalid")
    GatewayAuthRecoveryLabelKind.DEVICE_IDENTITY_REQUIRED -> nativeString("Device identity required")
  }
