package ai.openclaw.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

class GatewayTlsTest {
  @Test
  fun normalizeGatewayTlsFingerprintInput_acceptsPrefixColonsWhitespaceAndCase() {
    val expected = "ab".repeat(32)
    val colonSeparated = expected.uppercase().chunked(2).joinToString(":")

    assertEquals(expected, normalizeGatewayTlsFingerprintInput("  SHA256:  $colonSeparated\n"))
    assertEquals(expected, normalizeGatewayTlsFingerprintInput("sha-256:\t${expected.uppercase()}"))
    assertEquals(expected, normalizeGatewayTlsFingerprintInput(expected))
  }

  @Test
  fun normalizeGatewayTlsFingerprintInput_rejectsWrongLengthAndGarbage() {
    assertNull(normalizeGatewayTlsFingerprintInput("ab".repeat(31)))
    assertNull(normalizeGatewayTlsFingerprintInput("ab".repeat(32) + "00"))
    assertNull(normalizeGatewayTlsFingerprintInput("sha256: ${"ab".repeat(31)}:gg"))
    assertNull(normalizeGatewayTlsFingerprintInput("not-a-fingerprint"))
  }

  @Test
  fun buildGatewayTlsConfig_acceptsOnlyAnAuthenticatedSha256Pin() {
    val config =
      buildGatewayTlsConfig(
        params = GatewayTlsParams(expectedFingerprint = "SHA-256: ${"ab".repeat(32)}"),
        defaultTrust = RecordingExtendedTrustManager(),
      )

    assertNotNull(config.sslSocketFactory)
    assertTrue(config.hostnameVerifier.verify("127.0.0.1", null))
  }

  @Test
  fun buildGatewayTlsConfig_rejectsInvalidFingerprintBeforeConnecting() {
    assertThrows(IllegalArgumentException::class.java) {
      buildGatewayTlsConfig(
        params = GatewayTlsParams(expectedFingerprint = "not-a-sha256-fingerprint"),
        defaultTrust = RecordingExtendedTrustManager(),
      )
    }
  }

  private class RecordingExtendedTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) = Unit

    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      socket: Socket,
    ) = Unit

    override fun checkClientTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      engine: SSLEngine,
    ) = Unit

    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
    ) = Unit

    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      socket: Socket,
    ) = Unit

    override fun checkServerTrusted(
      chain: Array<X509Certificate>,
      authType: String,
      engine: SSLEngine,
    ) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }
}
