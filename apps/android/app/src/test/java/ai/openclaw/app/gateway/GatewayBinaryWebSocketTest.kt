package ai.openclaw.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayBinaryWebSocketTest {
  @Test
  fun routeUrlUsesTheActiveGatewayOriginAndContextPath() {
    val endpoint = testGatewayEndpoint("2001:db8::1", 18_789, contextPath = "/openclaw")

    assertEquals(
      "ws://[2001:db8::1]:18789/openclaw/claw-in-one/preview",
      buildGatewayRouteWebSocketUrl(
        endpoint = endpoint,
        useTls = false,
        routePath = "/claw-in-one/preview",
      ),
    )
    assertEquals(
      "wss://[2001:db8::1]:18789/openclaw/claw-in-one/preview",
      buildGatewayRouteWebSocketUrl(
        endpoint = endpoint,
        useTls = true,
        routePath = "/claw-in-one/preview",
      ),
    )
  }

  @Test
  fun routeUrlRejectsAnythingExceptAnAbsolutePath() {
    val endpoint = testGatewayEndpoint("127.0.0.1", 18_789)

    listOf("preview", "/preview?token=secret", "/preview#fragment", "/preview\\escape").forEach { path ->
      assertThrows(IllegalArgumentException::class.java) {
        buildGatewayRouteWebSocketUrl(endpoint, useTls = false, routePath = path)
      }
    }
  }
}
