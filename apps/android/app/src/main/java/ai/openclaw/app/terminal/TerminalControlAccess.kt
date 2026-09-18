package ai.openclaw.app.terminal

import ai.openclaw.app.onboarding.decodeSupervisorGatewaySetupCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ephemeral browser handoff, separate from the Android device-bound operator credential. */
internal class TerminalControlAccess(
  val page: GatewayControlPage,
  val bootstrapToken: String? = null,
)

internal fun parseTerminalControlAccess(
  response: String,
  page: GatewayControlPage,
): TerminalControlAccess {
  val setupCode =
    Json
      .parseToJsonElement(response)
      .jsonObject["setupCode"]
      ?.jsonPrimitive
      ?.content ?: error("Missing terminal setup code")
  val setup = decodeSupervisorGatewaySetupCode(setupCode) ?: error("Invalid terminal setup code")
  require(setup.bootstrapToken != null || setup.token != null || setup.password != null)
  // Keep the already trusted, same-device route and certificate; never navigate to a setup URL.
  return TerminalControlAccess(page.copy(token = setup.token, password = setup.password), setup.bootstrapToken)
}
