package ai.openclaw.app.gateway

import ai.openclaw.app.GatewayCredentials
import ai.openclaw.app.SecurePrefs
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceAuthStoreTest {
  @Test
  fun saveTokenPersistsNormalizedScopesMetadata() {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.secure.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = securePrefs)
    prefs.localGatewayPairing.replace(pairing("gateway-a"))
    val store = DeviceAuthStore(prefs)

    assertTrue(
      store.saveToken(
        gatewayId = "gateway-a",
        deviceId = " Device-1 ",
        role = " Operator ",
        token = " operator-token ",
        scopes = listOf("operator.write", "operator.read", "operator.write", " "),
      ),
    )

    val entry = store.loadEntry("gateway-a", "device-1", "operator")
    assertNotNull(entry)
    assertEquals("operator-token", entry?.token)
    assertEquals("operator", entry?.role)
    assertEquals(listOf("operator.read", "operator.write"), entry?.scopes)
    assertTrue((entry?.updatedAtMs ?: 0L) > 0L)
  }

  @Test
  fun staleGatewayCannotReadOrMutateTheCurrentPairingSlot() {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.secure.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = securePrefs)
    prefs.localGatewayPairing.replace(pairing("gateway-a"))
    val store = DeviceAuthStore(prefs)
    store.saveToken("gateway-a", "device-1", "operator", "token-a")
    store.saveToken("gateway-b", "device-1", "operator", "token-b")

    assertEquals("token-a", store.loadToken("gateway-a", "device-1", "operator"))
    assertEquals(null, store.loadToken("gateway-b", "device-1", "operator"))

    prefs.localGatewayPairing.replace(pairing("gateway-b"))
    store.saveToken("gateway-b", "device-1", "operator", "token-b")
    store.clearToken("gateway-a", "device-1", "operator")

    assertEquals(null, store.loadToken("gateway-a", "device-1", "operator"))
    assertEquals("token-b", store.loadToken("gateway-b", "device-1", "operator"))
  }

  @Test
  fun tokenWithoutCurrentMetadataIsRejected() {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.secure.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = securePrefs)
    prefs.localGatewayPairing.replace(pairing("gateway-a"))
    prefs.putString("gateway.local.deviceToken.gateway-a.device-1.operator", "old-token")

    assertEquals(null, DeviceAuthStore(prefs).loadEntry("gateway-a", "device-1", "operator"))
  }

  @Test
  fun staleRotationCannotReplaceOrClearANewerToken() {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.secure.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = securePrefs)
    prefs.localGatewayPairing.replace(pairing("gateway-a"))
    val store = DeviceAuthStore(prefs)
    assertTrue(store.saveToken("gateway-a", "device-1", "operator", "token-a"))
    assertTrue(
      store.saveToken(
        "gateway-a",
        "device-1",
        "operator",
        "token-b",
        replacesStoredToken = "token-a",
      ),
    )

    assertEquals(
      false,
      store.saveToken(
        "gateway-a",
        "device-1",
        "operator",
        "stale-token",
        replacesStoredToken = "token-a",
      ),
    )
    store.clearToken("gateway-a", "device-1", "operator", onlyIfToken = "token-a")
    assertEquals("token-b", store.loadToken("gateway-a", "device-1", "operator"))
  }

  private fun pairing(stableId: String): LocalGatewayPairing =
    LocalGatewayPairing(
      stableId = stableId,
      host = "127.0.0.1",
      port = 18789,
      tls = false,
      credentials = GatewayCredentials(),
    )
}
