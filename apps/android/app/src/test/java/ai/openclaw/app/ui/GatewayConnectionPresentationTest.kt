package ai.openclaw.app.ui

import ai.openclaw.app.GatewayConnectionProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayConnectionPresentationTest {
  @Test
  fun authRecoveryLabelsComeFromStructuredProblemCodes() {
    val labels =
      mapOf(
        "AUTH_BOOTSTRAP_TOKEN_INVALID" to "Setup code expired",
        "AUTH_TOKEN_MISSING" to "Gateway token needed",
        "AUTH_TOKEN_NOT_CONFIGURED" to "Gateway token not configured",
        "AUTH_PASSWORD_MISSING" to "Gateway password needed",
        "AUTH_PASSWORD_MISMATCH" to "Gateway password invalid",
        "AUTH_PASSWORD_NOT_CONFIGURED" to "Gateway password not configured",
        "AUTH_SCOPE_MISMATCH" to "Gateway access needs review",
        "AUTH_TOKEN_MISMATCH" to "Saved auth invalid",
        "AUTH_DEVICE_TOKEN_MISMATCH" to "Saved auth invalid",
        "CONTROL_UI_DEVICE_IDENTITY_REQUIRED" to "Device identity required",
        "DEVICE_IDENTITY_REQUIRED" to "Device identity required",
      )

    labels.forEach { (code, label) ->
      assertEquals(label, gatewayAuthRecoveryLabel(authProblem(code)))
    }
    assertNull(gatewayAuthRecoveryLabel(authProblem("SOME_UNMAPPED_CODE")))
    assertNull(gatewayAuthRecoveryLabel(null))
  }

  private fun authProblem(code: String) =
    GatewayConnectionProblem(
      code = code,
      message = "Authentication failed.",
      reason = null,
      requestId = null,
      recommendedNextStep = null,
      pauseReconnect = false,
      retryable = false,
    )
}
