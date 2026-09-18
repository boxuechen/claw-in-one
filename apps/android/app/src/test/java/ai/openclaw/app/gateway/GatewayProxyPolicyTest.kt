package ai.openclaw.app.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayProxyPolicyTest {
  @Test
  fun privateAndLocalGatewayHostsBypassSystemProxy() {
    assertTrue(shouldBypassGatewayProxy("10.28.105.117"))
    assertTrue(shouldBypassGatewayProxy("192.168.31.100"))
    assertTrue(shouldBypassGatewayProxy("gateway.local"))
    assertTrue(shouldBypassGatewayProxy("localhost"))
  }

  @Test
  fun publicGatewayHostsRetainSystemProxy() {
    assertFalse(shouldBypassGatewayProxy("gateway.example.com"))
    assertFalse(shouldBypassGatewayProxy("8.8.8.8"))
  }
}
