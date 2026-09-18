package ai.openclaw.app.onboarding

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class FirstRunGatewayConnectionTest {
  @Test
  fun `uses Terminal host loopback while preserving authenticated setup fields`() {
    val fingerprint = "ab".repeat(32)
    val setupCode =
      encodeSetupCode(
        """{"url":"wss://192.168.0.2:18789/openclaw","bootstrapToken":"bootstrap-1","tlsFingerprint":"$fingerprint"}""",
      )

    val pairing = resolveFirstRunGatewayPairing(setupCode)

    assertEquals("127.0.0.1", pairing?.host)
    assertEquals(18789, pairing?.port)
    assertEquals(true, pairing?.tls)
    assertEquals("/openclaw", pairing?.contextPath)
    assertEquals(fingerprint, pairing?.tlsFingerprintSha256)
    assertEquals("bootstrap-1", pairing?.credentials?.bootstrapToken)
  }

  @Test
  fun `rejects invalid authenticated setup before mapping transport`() {
    val setupCode =
      encodeSetupCode(
        """{"url":"wss://192.168.0.2:18789","bootstrapToken":"bootstrap-1","tlsFingerprint":"not-a-fingerprint"}""",
      )

    assertNull(resolveFirstRunGatewayPairing(setupCode))
  }

  @Test
  fun `waits for Terminal forwarding and returns the reachable loopback plan`() =
    runBlocking {
      var probes = 0
      val result =
        prepareFirstRunGatewayConnection(
          setupCode = validSetupCode(),
          attempts = 3,
          retryDelayMs = 0,
          portReachable = { host, port ->
            probes += 1
            assertEquals("127.0.0.1", host)
            assertEquals(18789, port)
            probes == 3
          },
        )

      assertEquals(3, probes)
      assertEquals("127.0.0.1", (result as FirstRunGatewayPreparation.Ready).pairing.host)
    }

  @Test
  fun `reports the Terminal consent boundary when loopback stays unavailable`() =
    runBlocking {
      val result =
        prepareFirstRunGatewayConnection(
          setupCode = validSetupCode(),
          attempts = 2,
          retryDelayMs = 0,
          portReachable = { _, _ -> false },
        )

      assertEquals(FirstRunGatewayPreparation.PortForwardingRequired, result)
    }

  @Test
  fun `rejects tls setup without an authenticated fingerprint`() {
    val setupCode = encodeSetupCode("""{"url":"wss://192.168.0.2:18789","bootstrapToken":"bootstrap-1"}""")

    assertNull(resolveFirstRunGatewayPairing(setupCode))
  }

  @Test
  fun `rejects ambiguous or missing setup authentication`() {
    val ambiguous =
      encodeSetupCode(
        """{"url":"ws://192.168.0.2:18789","bootstrapToken":"bootstrap-1","token":"shared"}""",
      )
    val missing = encodeSetupCode("""{"url":"ws://192.168.0.2:18789"}""")

    assertNull(resolveFirstRunGatewayPairing(ambiguous))
    assertNull(resolveFirstRunGatewayPairing(missing))
  }

  private fun validSetupCode(): String {
    val fingerprint = "ab".repeat(32)
    return encodeSetupCode(
      """{"url":"wss://192.168.0.2:18789","bootstrapToken":"bootstrap-1","tlsFingerprint":"$fingerprint"}""",
    )
  }

  private fun encodeSetupCode(payloadJson: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
}
