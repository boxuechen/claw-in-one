package ai.openclaw.app.gateway

import android.annotation.SuppressLint
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** The Supervisor-authenticated TLS identity for the paired local Gateway. */
data class GatewayTlsParams(
  val expectedFingerprint: String,
)

/** SSL primitives installed into OkHttp for the paired local Gateway. */
class GatewayTlsConfig internal constructor(
  val sslSocketFactory: SSLSocketFactory,
  val trustManager: X509TrustManager,
  val hostnameVerifier: HostnameVerifier,
)

/** Builds a fail-closed TLS config from the fingerprint supplied by Supervisor setup. */
fun buildGatewayTlsConfig(params: GatewayTlsParams?): GatewayTlsConfig? {
  if (params == null) return null
  return buildGatewayTlsConfig(params, defaultTrustManager())
}

internal fun buildGatewayTlsConfig(
  params: GatewayTlsParams,
  defaultTrust: X509TrustManager,
): GatewayTlsConfig {
  val expected =
    normalizeGatewayTlsFingerprintInput(params.expectedFingerprint)
      ?: throw IllegalArgumentException("Invalid paired Gateway TLS fingerprint")

  @SuppressLint("CustomX509TrustManager")
  val trustManager =
    object : X509ExtendedTrustManager() {
      override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
      ) {
        defaultTrust.checkClientTrusted(chain, authType)
      }

      override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket,
      ) {
        if (defaultTrust is X509ExtendedTrustManager) {
          defaultTrust.checkClientTrusted(chain, authType, socket)
        } else {
          checkClientTrusted(chain, authType)
        }
      }

      override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine,
      ) {
        if (defaultTrust is X509ExtendedTrustManager) {
          defaultTrust.checkClientTrusted(chain, authType, engine)
        } else {
          checkClientTrusted(chain, authType)
        }
      }

      override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
      ) {
        val certificate = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val observed = sha256Hex(certificate.encoded)
        if (observed != expected) throw CertificateException("gateway TLS fingerprint mismatch")
      }

      override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket,
      ) = checkServerTrusted(chain, authType)

      override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine,
      ) = checkServerTrusted(chain, authType)

      override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrust.acceptedIssuers
    }

  val context = SSLContext.getInstance("TLS")
  context.init(null, arrayOf(trustManager), SecureRandom())
  return GatewayTlsConfig(
    sslSocketFactory = context.socketFactory,
    trustManager = trustManager,
    // The authenticated certificate pin is authoritative. The socket host is
    // intentionally loopback even when the certificate names the Debian host.
    hostnameVerifier = HostnameVerifier { _, _ -> true },
  )
}

private fun defaultTrustManager(): X509TrustManager {
  val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
  factory.init(null as java.security.KeyStore?)
  val trust = factory.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
  return trust ?: throw IllegalStateException("No default X509TrustManager found")
}

private fun sha256Hex(data: ByteArray): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(data)
  val out = StringBuilder(digest.size * 2)
  for (byte in digest) out.append(String.format(Locale.US, "%02x", byte))
  return out.toString()
}

/** Normalizes accepted fingerprint text to lowercase bare SHA-256 hex. */
fun normalizeGatewayTlsFingerprintInput(raw: String): String? {
  val stripped =
    raw
      .trim()
      .replace(Regex("^sha-?256\\s*:\\s*", RegexOption.IGNORE_CASE), "")
  val compact =
    stripped
      .filterNot { it == ':' || it.isWhitespace() }
      .lowercase(Locale.US)
  return compact.takeIf { value ->
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
  }
}

/** Normalizes internal fingerprint text; invalid values become empty. */
fun normalizeGatewayTlsFingerprint(raw: String): String = normalizeGatewayTlsFingerprintInput(raw).orEmpty()
