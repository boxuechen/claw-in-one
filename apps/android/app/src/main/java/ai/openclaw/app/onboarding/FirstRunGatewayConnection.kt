package ai.openclaw.app.onboarding

import ai.openclaw.app.GatewayCredentials
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.LOCAL_GATEWAY_STABLE_ID
import ai.openclaw.app.gateway.LocalGatewayPairing
import ai.openclaw.app.gateway.normalizeGatewayContextPath
import ai.openclaw.app.gateway.normalizeGatewayTlsFingerprintInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Locale

private const val TERMINAL_GATEWAY_LOOPBACK_HOST = "127.0.0.1"
private const val TERMINAL_GATEWAY_CONNECT_TIMEOUT_MS = 250
private const val TERMINAL_GATEWAY_PROBE_ATTEMPTS = 10
private const val TERMINAL_GATEWAY_PROBE_DELAY_MS = 250L

internal sealed interface FirstRunGatewayPreparation {
  data class Ready(
    val pairing: LocalGatewayPairing,
  ) : FirstRunGatewayPreparation

  data object InvalidSetup : FirstRunGatewayPreparation

  data object PortForwardingRequired : FirstRunGatewayPreparation
}

internal enum class FirstRunGatewayConnectResult {
  Started,
  InvalidSetup,
  PortForwardingRequired,
}

/**
 * Converts an authenticated Gateway setup code into the transport owned by Android Terminal.
 *
 * The setup code remains authoritative for the port, TLS pin, context path, and bootstrap
 * credential. Its Debian guest address is not an Android client transport: Terminal exposes each
 * user-approved guest port on host loopback using the same port number.
 */
internal fun resolveFirstRunGatewayPairing(setupCode: String): LocalGatewayPairing? {
  val setup = decodeSupervisorGatewaySetupCode(setupCode) ?: return null
  val uri = runCatching { URI(setup.url) }.getOrNull() ?: return null
  if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
  val advertisedHost =
    uri.host
      ?.trim()
      ?.trim('[', ']')
      .orEmpty()
  if (advertisedHost.isEmpty() || advertisedHost.contains('%')) return null
  val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
  if (scheme !in setOf("ws", "wss", "http", "https")) return null
  val tls = scheme == "wss" || scheme == "https"
  val port =
    if (uri.port == -1) {
      if (tls) {
        443
      } else {
        18789
      }
    } else {
      uri.port
    }
  if (port !in 1..65535) return null
  val fingerprint =
    setup.tlsFingerprintSha256?.let(::normalizeGatewayTlsFingerprintInput)
      ?: if (setup.tlsFingerprintSha256 == null) null else return null
  if (tls && fingerprint == null) return null
  val credentials =
    GatewayCredentials(
      bootstrapToken = setup.bootstrapToken,
      token = setup.token,
      password = setup.password,
    ).normalized()
  if (listOf(credentials.bootstrapToken, credentials.token, credentials.password).count { it != null } != 1) {
    return null
  }
  val endpoint =
    GatewayEndpoint(
      stableId = LOCAL_GATEWAY_STABLE_ID,
      name = "Local Gateway",
      host = TERMINAL_GATEWAY_LOOPBACK_HOST,
      port = port,
      tlsEnabled = tls,
      contextPath = normalizeGatewayContextPath(uri.rawPath),
      tlsFingerprintSha256 = fingerprint,
    )
  return LocalGatewayPairing.from(
    endpoint = endpoint,
    credentials = credentials,
  )
}

/** Waits briefly for Terminal's same-port loopback forwarder before starting Gateway TLS. */
internal suspend fun prepareFirstRunGatewayConnection(
  setupCode: String,
  attempts: Int = TERMINAL_GATEWAY_PROBE_ATTEMPTS,
  retryDelayMs: Long = TERMINAL_GATEWAY_PROBE_DELAY_MS,
  portReachable: suspend (String, Int) -> Boolean = ::terminalGatewayPortReachable,
): FirstRunGatewayPreparation {
  val pairing = resolveFirstRunGatewayPairing(setupCode) ?: return FirstRunGatewayPreparation.InvalidSetup
  repeat(attempts.coerceAtLeast(1)) { attempt ->
    if (portReachable(pairing.host, pairing.port)) {
      return FirstRunGatewayPreparation.Ready(pairing)
    }
    if (attempt + 1 < attempts && retryDelayMs > 0) delay(retryDelayMs)
  }
  return FirstRunGatewayPreparation.PortForwardingRequired
}

internal data class SupervisorGatewaySetupCode(
  val url: String,
  val bootstrapToken: String?,
  val token: String?,
  val password: String?,
  val tlsFingerprintSha256: String?,
)

private val setupCodeJson = Json { ignoreUnknownKeys = true }

internal fun decodeSupervisorGatewaySetupCode(rawInput: String): SupervisorGatewaySetupCode? {
  val raw =
    rawInput
      .trim()
      .let { value -> if (value.startsWith("oc-pair://", ignoreCase = true)) value.drop(10) else value }
      .trim()
  if (raw.isEmpty()) return null
  val padded =
    raw
      .replace('-', '+')
      .replace('_', '/')
      .let { if (it.length % 4 == 0) it else it + "=".repeat(4 - (it.length % 4)) }
  val payload =
    runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }
      .getOrNull()
      ?: return null
  val fields =
    runCatching { setupCodeJson.parseToJsonElement(payload).jsonObject }
      .getOrNull()
      ?: return null
  val url = fields.string("url") ?: return null
  return SupervisorGatewaySetupCode(
    url = url,
    bootstrapToken = fields.string("bootstrapToken"),
    token = fields.string("token"),
    password = fields.string("password"),
    tlsFingerprintSha256 = fields.string("tlsFingerprint"),
  )
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)
    ?.contentOrNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private suspend fun terminalGatewayPortReachable(
  host: String,
  port: Int,
): Boolean =
  withContext(Dispatchers.IO) {
    runCatching {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), TERMINAL_GATEWAY_CONNECT_TIMEOUT_MS)
      }
    }.isSuccess
  }
