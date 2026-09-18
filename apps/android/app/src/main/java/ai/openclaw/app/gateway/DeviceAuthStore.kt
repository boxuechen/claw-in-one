package ai.openclaw.app.gateway

import ai.openclaw.app.SecurePrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stored local-Gateway device-token material scoped by device id and role. */
data class DeviceAuthEntry(
  val token: String,
  val role: String,
  val scopes: List<String>,
  val updatedAtMs: Long,
)

@Serializable
private data class PersistedDeviceAuthMetadata(
  val scopes: List<String>,
  val updatedAtMs: Long,
)

/** Persistence interface used by gateway pairing/session code for role tokens. */
interface DeviceAuthTokenStore {
  /** Loads the stored token plus metadata for one device/role pair. */
  fun loadEntry(
    gatewayId: String,
    deviceId: String,
    role: String,
  ): DeviceAuthEntry?

  /** Loads only the bearer token when callers do not need scope metadata. */
  fun loadToken(
    gatewayId: String,
    deviceId: String,
    role: String,
  ): String? = loadEntry(gatewayId, deviceId, role)?.token

  /** Persists a role token and deterministic scope metadata under normalized keys. */
  fun saveToken(
    gatewayId: String,
    deviceId: String,
    role: String,
    token: String,
    scopes: List<String> = emptyList(),
    replacesStoredToken: String? = null,
  ): Boolean

  /** Removes both values, optionally only while the slot still contains the expected token. */
  fun clearToken(
    gatewayId: String,
    deviceId: String,
    role: String,
    onlyIfToken: String? = null,
  )
}

/** SecurePrefs-backed implementation of Android local-Gateway device-token storage. */
class DeviceAuthStore(
  private val prefs: SecurePrefs,
) : DeviceAuthTokenStore {
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()

  override fun loadEntry(
    gatewayId: String,
    deviceId: String,
    role: String,
  ): DeviceAuthEntry? {
    if (!ownsPairing(gatewayId)) return null
    val key = tokenKey(gatewayId, deviceId, role)
    val token = prefs.getString(key)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalizedRole = normalizeRole(role)
    val metadata =
      prefs
        .getString(metadataKey(gatewayId, deviceId, role))
        ?.let { raw ->
          runCatching { json.decodeFromString<PersistedDeviceAuthMetadata>(raw) }.getOrNull()
        }?.takeIf { it.updatedAtMs > 0L }
        ?: return null
    return DeviceAuthEntry(
      token = token,
      role = normalizedRole,
      scopes = normalizeScopes(metadata.scopes),
      updatedAtMs = metadata.updatedAtMs,
    )
  }

  override fun saveToken(
    gatewayId: String,
    deviceId: String,
    role: String,
    token: String,
    scopes: List<String>,
    replacesStoredToken: String?,
  ): Boolean =
    synchronized(lock) {
      if (!ownsPairing(gatewayId)) return@synchronized false
      if (
        replacesStoredToken != null &&
        loadEntry(gatewayId, deviceId, role)?.token != replacesStoredToken.trim()
      ) {
        return@synchronized false
      }
      val normalizedScopes = normalizeScopes(scopes)
      val key = tokenKey(gatewayId, deviceId, role)
      prefs.commitSecureStrings(
        mapOf(
          key to token.trim(),
          metadataKey(gatewayId, deviceId, role) to
            json.encodeToString(
              PersistedDeviceAuthMetadata(
                scopes = normalizedScopes,
                updatedAtMs = System.currentTimeMillis(),
              ),
            ),
        ),
      )
    }

  override fun clearToken(
    gatewayId: String,
    deviceId: String,
    role: String,
    onlyIfToken: String?,
  ) = synchronized(lock) {
    if (!ownsPairing(gatewayId)) return@synchronized
    if (onlyIfToken != null && loadEntry(gatewayId, deviceId, role)?.token != onlyIfToken.trim()) {
      return@synchronized
    }
    val key = tokenKey(gatewayId, deviceId, role)
    prefs.remove(key)
    prefs.remove(metadataKey(gatewayId, deviceId, role))
  }

  private fun tokenKey(
    gatewayId: String,
    deviceId: String,
    role: String,
  ): String {
    val normalizedGateway = gatewayId.trim()
    val normalizedDevice = normalizeDeviceId(deviceId)
    val normalizedRole = normalizeRole(role)
    // Keep key normalization shared with metadata keys so token and metadata
    // are added/removed as one logical auth entry.
    return "gateway.local.deviceToken.$normalizedGateway.$normalizedDevice.$normalizedRole"
  }

  private fun metadataKey(
    gatewayId: String,
    deviceId: String,
    role: String,
  ): String {
    val normalizedGateway = gatewayId.trim()
    val normalizedDevice = normalizeDeviceId(deviceId)
    val normalizedRole = normalizeRole(role)
    return "gateway.local.deviceTokenMeta.$normalizedGateway.$normalizedDevice.$normalizedRole"
  }

  private fun ownsPairing(gatewayId: String): Boolean = prefs.localGatewayPairing.stableId.value == gatewayId.trim()

  /** Normalizes device ids before they become encrypted preference key segments. */
  private fun normalizeDeviceId(deviceId: String): String = deviceId.trim().lowercase()

  /** Normalizes role names so node/operator token slots are stable across callers. */
  private fun normalizeRole(role: String): String = role.trim().lowercase()

  /** Stores scopes in deterministic order for display and restart comparisons. */
  private fun normalizeScopes(scopes: List<String>): List<String> =
    scopes
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      // Persist deterministic scope lists because they are displayed and may be
      // compared across process restarts.
      .distinct()
      .sorted()
}
