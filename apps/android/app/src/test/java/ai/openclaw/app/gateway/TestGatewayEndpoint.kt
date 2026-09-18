package ai.openclaw.app.gateway

/** Test-only endpoint factory for transport/session fixtures. */
internal fun testGatewayEndpoint(
  host: String,
  port: Int,
  tlsEnabled: Boolean = false,
  contextPath: String = "",
  tlsFingerprintSha256: String? = null,
): GatewayEndpoint {
  val normalizedContextPath = normalizeGatewayContextPath(contextPath)
  val stableIdPath = if (normalizedContextPath.isEmpty()) "" else "|$normalizedContextPath"
  return GatewayEndpoint(
    stableId = "test|${host.lowercase()}|$port$stableIdPath",
    name = "$host:$port",
    host = host,
    port = port,
    tlsEnabled = tlsEnabled,
    tlsFingerprintSha256 = tlsFingerprintSha256,
    contextPath = normalizedContextPath,
  )
}
