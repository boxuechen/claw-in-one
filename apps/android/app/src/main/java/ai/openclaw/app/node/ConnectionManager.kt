package ai.openclaw.app.node

import ai.openclaw.app.BuildConfig
import ai.openclaw.app.SecurePrefs
import ai.openclaw.app.gateway.GatewayClientInfo
import ai.openclaw.app.gateway.GatewayConnectOptions
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.isLoopbackGatewayHost
import ai.openclaw.app.gateway.normalizeGatewayTlsFingerprintInput
import android.os.Build

/**
 * Builds gateway connect metadata from Android Use availability and device identity.
 */
class ConnectionManager internal constructor(
  private val prefs: SecurePrefs,
  private val androidUseAvailable: () -> Boolean,
) {
  companion object {
    internal val nativeClientOperatorScopes: List<String> =
      listOf(
        // admin matches iOS fresh token/password connects and is required for
        // sessions.patch (model switching); stored tokens keep their granted scopes.
        "operator.admin",
        "operator.approvals",
        "operator.pairing",
        "operator.questions",
        "operator.read",
        "operator.write",
      )

    internal const val AGENT_KIND_CLIENT_CAPABILITY = "agent-kind"
    internal const val PLUGIN_APPROVALS_CLIENT_CAPABILITY = "plugin-approvals"
    internal const val TOOL_EVENTS_CLIENT_CAPABILITY = "tool-events"
    internal const val USAGE_REFRESHING_CLIENT_CAPABILITY = "usage-refreshing"

    internal fun operatorScopesForStoredDeviceToken(storedScopes: List<String>): List<String> {
      val normalized =
        storedScopes
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .distinct()
      return normalized
    }

    /** Resolves the authenticated transport identity of the paired loopback endpoint. */
    internal fun resolveTlsParamsForEndpoint(endpoint: GatewayEndpoint): GatewayTlsParams? {
      require(isLoopbackGatewayHost(endpoint.host, allowEmulatorBridgeAlias = false)) {
        "Paired local Gateway must use a loopback host"
      }
      if (!endpoint.tlsEnabled) return null
      val fingerprint =
        endpoint.tlsFingerprintSha256
          ?.let(::normalizeGatewayTlsFingerprintInput)
          ?: throw IllegalArgumentException("Paired local Gateway TLS fingerprint is missing or invalid")
      return GatewayTlsParams(expectedFingerprint = fingerprint)
    }
  }

  /** Builds the gateway-advertised Node command list from current Android Use availability. */
  fun buildInvokeCommands(): List<String> = InvokeCommandRegistry.advertisedCommands(androidUseAvailable())

  /** Builds the gateway-advertised capability list from current Android Use availability. */
  fun buildCapabilities(): List<String> = InvokeCommandRegistry.advertisedCapabilities(androidUseAvailable())

  /**
   * Debug Android builds advertise a dev version so gateway logs do not look like release clients.
   */
  fun resolvedVersionName(): String {
    val versionName = BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }
    return if (BuildConfig.DEBUG && !versionName.contains("dev", ignoreCase = true)) {
      "$versionName-dev"
    } else {
      versionName
    }
  }

  /** Human-readable Android device model used in gateway client metadata. */
  fun resolveModelIdentifier(): String? =
    listOfNotNull(Build.MANUFACTURER, Build.MODEL)
      .joinToString(" ")
      .trim()
      .ifEmpty { null }

  /**
   * User-Agent used for gateway telemetry and troubleshooting.
   */
  fun buildUserAgent(): String {
    val version = resolvedVersionName()
    val release =
      Build.VERSION.RELEASE
        ?.trim()
        .orEmpty()
    val releaseLabel = if (release.isEmpty()) "unknown" else release
    return "OpenClawAndroid/$version (Android $releaseLabel; SDK ${Build.VERSION.SDK_INT})"
  }

  /** Client identity block shared by node and operator gateway sessions. */
  fun buildClientInfo(
    clientId: String,
    clientMode: String,
  ): GatewayClientInfo =
    GatewayClientInfo(
      id = clientId,
      displayName = prefs.displayName.value,
      version = resolvedVersionName(),
      platform = "android",
      mode = clientMode,
      instanceId = prefs.instanceId.value,
      deviceFamily = "Android",
      modelIdentifier = resolveModelIdentifier(),
    )

  /** Connect options for the Android node session that exposes Android Use when available. */
  fun buildNodeConnectOptions(): GatewayConnectOptions {
    val available = androidUseAvailable()
    return GatewayConnectOptions(
      role = "node",
      scopes = emptyList(),
      caps = InvokeCommandRegistry.advertisedCapabilities(available),
      commands = InvokeCommandRegistry.advertisedCommands(available),
      permissions = emptyMap(),
      client = buildClientInfo(clientId = "openclaw-android", clientMode = "node"),
      userAgent = buildUserAgent(),
    )
  }

  /** Connect options for the Android operator session that drives approvals and UI actions. */
  fun buildOperatorConnectOptions(
    scopes: List<String> = nativeClientOperatorScopes,
  ): GatewayConnectOptions =
    GatewayConnectOptions(
      role = "operator",
      scopes = scopes,
      caps =
        buildList {
          add(AGENT_KIND_CLIENT_CAPABILITY)
          add(PLUGIN_APPROVALS_CLIENT_CAPABILITY)
          add(TOOL_EVENTS_CLIENT_CAPABILITY)
          add(USAGE_REFRESHING_CLIENT_CAPABILITY)
        },
      commands = emptyList(),
      permissions = emptyMap(),
      client = buildClientInfo(clientId = "openclaw-android", clientMode = "ui"),
      userAgent = buildUserAgent(),
    )

  /** Resolves the Supervisor-authenticated TLS identity for the paired Gateway. */
  fun resolveTlsParams(endpoint: GatewayEndpoint): GatewayTlsParams? = resolveTlsParamsForEndpoint(endpoint)
}
