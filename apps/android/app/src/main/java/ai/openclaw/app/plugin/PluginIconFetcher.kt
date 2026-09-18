package ai.openclaw.app.plugin

import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.buildGatewayTlsConfig
import ai.openclaw.app.gateway.normalizeGatewayTlsFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class GatewayPluginIconAccess(
  val baseUrl: String,
  val authCandidates: List<String>,
  val tlsFingerprintSha256: String?,
)

internal data class GatewayPluginIconPayload(
  val bytes: ByteArray,
  val contentType: String,
)

/** Fetches the Gateway's authenticated, bounded icon proxy without exposing credentials to UI state. */
internal class PluginIconFetcher(
  private val clientFactory: (GatewayPluginIconAccess) -> OkHttpClient? = ::buildPluginIconClient,
) {
  suspend fun fetch(
    access: GatewayPluginIconAccess,
    pluginId: String,
  ): GatewayPluginIconPayload? =
    withContext(Dispatchers.IO) {
      val normalizedId = pluginId.trim().takeIf(String::isNotEmpty) ?: return@withContext null
      val route = pluginIconRoute(access.baseUrl, normalizedId) ?: return@withContext null
      val client = clientFactory(access) ?: return@withContext null
      val candidates =
        access.authCandidates
          .map(String::trim)
          .filter(::headerSafe)
          .distinct()
      val attempts = candidates.ifEmpty { listOf("") }
      for (candidate in attempts) {
        val builder =
          Request
            .Builder()
            .url(route)
            .header("Accept", "image/png,image/svg+xml,image/x-icon")
        if (candidate.isNotEmpty()) builder.header("Authorization", "Bearer $candidate")
        try {
          client.newCall(builder.get().build()).execute().use { response ->
            if (response.code == 401 || response.code == 403) return@use
            if (!response.isSuccessful) return@withContext null
            val body = response.body
            val type =
              body
                .contentType()
                ?.let { "${it.type}/${it.subtype}".lowercase(Locale.US) }
                ?.takeIf(ALLOWED_PLUGIN_ICON_TYPES::contains)
                ?: return@withContext null
            val bytes = readBoundedIcon(body.source()) ?: return@withContext null
            return@withContext GatewayPluginIconPayload(bytes, type)
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          return@withContext null
        }
      }
      null
    }
}

private fun pluginIconRoute(
  baseUrl: String,
  pluginId: String,
): HttpUrl? =
  baseUrl
    .toHttpUrlOrNull()
    ?.newBuilder()
    ?.addPathSegment("__openclaw__")
    ?.addPathSegment("plugin-icon")
    ?.addEncodedPathSegment(pluginId.encodePathSegment())
    ?.build()

private fun String.encodePathSegment(): String =
  buildString(length) {
    this@encodePathSegment.encodeToByteArray().forEach { byte ->
      val value = byte.toInt() and 0xff
      if (
        value in 'a'.code..'z'.code ||
        value in 'A'.code..'Z'.code ||
        value in '0'.code..'9'.code ||
        value == '-'.code ||
        value == '.'.code ||
        value == '_'.code ||
        value == '~'.code
      ) {
        append(value.toChar())
      } else {
        append('%')
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
      }
    }
  }

private fun buildPluginIconClient(access: GatewayPluginIconAccess): OkHttpClient? {
  val base = access.baseUrl.toHttpUrlOrNull() ?: return null
  val builder =
    OkHttpClient
      .Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .cookieJar(CookieJar.NO_COOKIES)
      .authenticator(Authenticator.NONE)
      .proxyAuthenticator(Authenticator.NONE)
      .proxy(Proxy.NO_PROXY)
      .cache(null)
      .callTimeout(PLUGIN_ICON_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  if (base.isHttps) {
    val fingerprint = access.tlsFingerprintSha256?.let(::normalizeGatewayTlsFingerprint).orEmpty()
    if (fingerprint.length != 64) return null
    val tls =
      buildGatewayTlsConfig(
        GatewayTlsParams(
          expectedFingerprint = fingerprint,
        ),
      ) ?: return null
    builder.sslSocketFactory(tls.sslSocketFactory, tls.trustManager).hostnameVerifier(tls.hostnameVerifier)
  }
  return builder.build()
}

private fun readBoundedIcon(source: okio.BufferedSource): ByteArray? {
  val buffer = Buffer()
  while (buffer.size <= PLUGIN_ICON_MAX_BYTES) {
    val remaining = PLUGIN_ICON_MAX_BYTES + 1L - buffer.size
    if (source.read(buffer, remaining) == -1L) break
  }
  if (buffer.size > PLUGIN_ICON_MAX_BYTES) return null
  return buffer.readByteArray().takeIf(ByteArray::isNotEmpty)
}

private fun headerSafe(value: String): Boolean = value.isNotEmpty() && '\r' !in value && '\n' !in value

private val ALLOWED_PLUGIN_ICON_TYPES = setOf("image/png", "image/svg+xml", "image/x-icon")
private const val HEX_DIGITS = "0123456789ABCDEF"
private const val PLUGIN_ICON_MAX_BYTES = 1024 * 1024L
private const val PLUGIN_ICON_TIMEOUT_SECONDS = 10L
