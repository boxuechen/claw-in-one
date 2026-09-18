package ai.openclaw.app.gateway

import ai.openclaw.app.GatewayCredentials
import ai.openclaw.app.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val LOCAL_GATEWAY_STABLE_ID = "local-gateway"

internal data class LocalGatewayPairingSnapshot(
  val revision: Long,
  val pairing: LocalGatewayPairing?,
)

/** The one Supervisor-provided Gateway target and its setup authentication. */
@Serializable
internal data class LocalGatewayPairing(
  val stableId: String,
  val host: String,
  val port: Int,
  val tls: Boolean,
  val contextPath: String = "",
  val tlsFingerprintSha256: String? = null,
  val credentials: GatewayCredentials = GatewayCredentials(),
) {
  fun endpoint(): GatewayEndpoint =
    GatewayEndpoint(
      stableId = stableId,
      name = "$host:$port",
      host = host,
      port = port,
      tlsEnabled = tls,
      tlsFingerprintSha256 = tlsFingerprintSha256,
      contextPath = contextPath,
    )

  companion object {
    fun from(
      endpoint: GatewayEndpoint,
      credentials: GatewayCredentials,
    ): LocalGatewayPairing =
      LocalGatewayPairing(
        stableId = endpoint.stableId,
        host = endpoint.host,
        port = endpoint.port,
        tls = endpoint.tlsEnabled,
        contextPath = normalizeGatewayContextPath(endpoint.contextPath),
        tlsFingerprintSha256 = endpoint.tlsFingerprintSha256?.trim()?.takeIf(String::isNotEmpty),
        credentials = credentials.normalized(),
      ).normalized()
  }

  fun normalized(): LocalGatewayPairing {
    val normalizedStableId = stableId.trim()
    val normalizedHost = host.trim()
    require(normalizedStableId.isNotEmpty()) { "Local Gateway stable id cannot be empty" }
    require(normalizedHost.isNotEmpty()) { "Local Gateway host cannot be empty" }
    require(isLoopbackGatewayHost(normalizedHost, allowEmulatorBridgeAlias = false)) {
      "Local Gateway host must be loopback"
    }
    require(port in 1..65535) { "Local Gateway port is invalid" }
    val normalizedFingerprint =
      if (tls) {
        tlsFingerprintSha256
          ?.let(::normalizeGatewayTlsFingerprintInput)
          ?: throw IllegalArgumentException("Local Gateway TLS fingerprint is missing or invalid")
      } else {
        null
      }
    return copy(
      stableId = normalizedStableId,
      host = normalizedHost,
      contextPath = normalizeGatewayContextPath(contextPath),
      tlsFingerprintSha256 = normalizedFingerprint,
      credentials = credentials.normalized(),
    )
  }
}

@Serializable
private data class PersistedLocalGatewayPairing(
  val version: Int = 1,
  val pairing: LocalGatewayPairing,
)

/** Encrypted single-slot store. It deliberately has no registry, active selection, or migration. */
internal class LocalGatewayPairingStore(
  private val prefs: SecurePrefs,
) {
  companion object {
    internal const val STORAGE_KEY = "gateway.localPairing.v1"
  }

  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }
  private val mutationLock = Any()
  private val initial = decode(prefs.getString(STORAGE_KEY))
  private val _pairing = MutableStateFlow(initial)
  val pairing: StateFlow<LocalGatewayPairing?> = _pairing.asStateFlow()
  private val _stableId = MutableStateFlow(initial?.stableId)
  val stableId: StateFlow<String?> = _stableId.asStateFlow()
  private val revisionCounter = MutableStateFlow(if (initial == null) 0L else 1L)

  /** Reads the pairing and its generation under the same mutation boundary. */
  fun snapshot(): LocalGatewayPairingSnapshot =
    synchronized(mutationLock) {
      LocalGatewayPairingSnapshot(
        revision = revisionCounter.value,
        pairing = _pairing.value,
      )
    }

  fun replace(value: LocalGatewayPairing): Boolean =
    synchronized(mutationLock) {
      val normalized = value.normalized()
      if (!prefs.putStringSynchronously(STORAGE_KEY, encode(normalized))) return@synchronized false
      _pairing.value = normalized
      _stableId.value = normalized.stableId
      revisionCounter.value += 1
      true
    }

  fun updateCredentials(
    expectedStableId: String,
    credentials: GatewayCredentials,
  ): Boolean = update(expectedStableId) { it.copy(credentials = credentials.normalized()) }

  fun clear(expectedStableId: String): Boolean =
    synchronized(mutationLock) {
      val current = _pairing.value ?: return@synchronized false
      if (current.stableId != expectedStableId.trim()) return@synchronized false
      if (!prefs.removeSynchronously(STORAGE_KEY)) return@synchronized false
      _pairing.value = null
      _stableId.value = null
      revisionCounter.value += 1
      true
    }

  private fun update(
    expectedStableId: String,
    transform: (LocalGatewayPairing) -> LocalGatewayPairing,
  ): Boolean =
    synchronized(mutationLock) {
      val current = _pairing.value ?: return@synchronized false
      if (current.stableId != expectedStableId.trim()) return@synchronized false
      val updated = transform(current).normalized()
      if (!prefs.putStringSynchronously(STORAGE_KEY, encode(updated))) return@synchronized false
      _pairing.value = updated
      _stableId.value = updated.stableId
      revisionCounter.value += 1
      true
    }

  private fun encode(value: LocalGatewayPairing): String = json.encodeToString(PersistedLocalGatewayPairing(pairing = value))

  private fun decode(raw: String?): LocalGatewayPairing? {
    val persisted =
      raw
        ?.let { runCatching { json.decodeFromString<PersistedLocalGatewayPairing>(it) }.getOrNull() }
        ?: return null
    if (persisted.version != 1) return null
    return runCatching { persisted.pairing.normalized() }.getOrNull()
  }
}
