package ai.openclaw.app.node

import ai.openclaw.app.androiduse.AndroidUseHandler
import ai.openclaw.app.gateway.GatewaySession

/** Routes the single retained Android Node command through its live availability boundary. */
class InvokeDispatcher(
  private val androidUseHandler: AndroidUseHandler,
  private val androidUseAvailable: () -> Boolean,
) {
  suspend fun handleInvoke(
    command: String,
    paramsJson: String?,
    connection: GatewaySession.RequestLease? = null,
  ): GatewaySession.InvokeResult {
    if (!InvokeCommandRegistry.contains(command)) {
      return GatewaySession.InvokeResult.error(
        code = "INVALID_REQUEST",
        message = "INVALID_REQUEST: unknown command",
      )
    }
    if (!androidUseAvailable()) {
      return GatewaySession.InvokeResult.error(
        code = "MOBILE_UI_UNAVAILABLE",
        message = "MOBILE_UI_UNAVAILABLE: enable Android Use and check its accessibility connection",
      )
    }
    if (connection == null) {
      return GatewaySession.InvokeResult.error(
        code = "GATEWAY_DISCONNECTED",
        message = "Android Use requires the originating Node connection",
      )
    }
    return androidUseHandler.handle(connection, paramsJson)
  }
}
