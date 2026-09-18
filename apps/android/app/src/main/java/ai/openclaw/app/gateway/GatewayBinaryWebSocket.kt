package ai.openclaw.app.gateway

/** A binary side channel bound to one live Gateway connection generation. */
internal fun interface GatewayBinaryWebSocket {
  fun cancel()

  fun sendText(text: String): Boolean = false
}

/** Keeps feature-specific framing outside the shared Gateway transport. */
internal interface GatewayBinaryWebSocketListener {
  fun onOpen()

  fun onBytes(bytes: ByteArray)

  fun onClosed(message: String?)
}

internal fun buildGatewayRouteWebSocketUrl(
  endpoint: GatewayEndpoint,
  useTls: Boolean,
  routePath: String,
): String {
  require(routePath.startsWith('/') && '?' !in routePath && '#' !in routePath && '\\' !in routePath) {
    "Gateway route path must be absolute"
  }
  val scheme = if (useTls) "wss" else "ws"
  return "$scheme://${formatGatewayAuthority(endpoint.host, endpoint.port)}${endpoint.contextPath}$routePath"
}
