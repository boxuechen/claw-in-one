package ai.openclaw.app.node

import ai.openclaw.app.SecurePrefs
import ai.openclaw.app.androiduse.ANDROID_USE_NODE_COMMAND
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.protocol.OpenClawCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConnectionManagerTest {
  @Test
  fun nodeAdvertisementUsesOneAvailabilitySnapshotForCapabilitiesAndCommands() {
    var reads = 0
    val manager =
      newManager(androidUseAvailable = {
        reads++
        reads == 1
      })
    val first = manager.buildNodeConnectOptions()
    assertEquals(1, reads)
    assertEquals(listOf(ANDROID_USE_NODE_COMMAND), first.commands)
    assertEquals(listOf(OpenClawCapability.MobileUI.rawValue, "clawAndroidUseConsentV1"), first.caps)
    val second = manager.buildNodeConnectOptions()
    assertEquals(2, reads)
    assertTrue(second.commands.isEmpty())
    assertTrue(second.caps.isEmpty())
  }

  @Test
  fun localCleartextPairingNeedsNoTlsConfig() {
    val endpoint = localEndpoint(tls = false)

    assertNull(ConnectionManager.resolveTlsParamsForEndpoint(endpoint))
  }

  @Test
  fun localTlsPairingUsesSupervisorFingerprint() {
    val fingerprint = "ab".repeat(32)
    val endpoint = localEndpoint(tls = true, fingerprint = "SHA-256: ${fingerprint.uppercase()}")

    assertEquals(fingerprint, ConnectionManager.resolveTlsParamsForEndpoint(endpoint)?.expectedFingerprint)
  }

  @Test
  fun localTlsPairingFailsClosedWithoutValidFingerprint() {
    for (fingerprint in listOf(null, "", "not-a-fingerprint")) {
      assertThrows(IllegalArgumentException::class.java) {
        ConnectionManager.resolveTlsParamsForEndpoint(localEndpoint(tls = true, fingerprint = fingerprint))
      }
    }
  }

  @Test
  fun nonLoopbackPairingIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      ConnectionManager.resolveTlsParamsForEndpoint(
        GatewayEndpoint(
          stableId = "local-gateway",
          name = "Remote",
          host = "192.168.1.20",
          port = 18789,
        ),
      )
    }
  }

  @Test
  fun buildOperatorConnectOptions_requestsNativeClientOperatorScopes() {
    val options = newManager().buildOperatorConnectOptions()

    assertEquals(
      listOf(
        "operator.admin",
        "operator.approvals",
        "operator.pairing",
        "operator.questions",
        "operator.read",
        "operator.write",
      ),
      options.scopes,
    )
    assertEquals(
      listOf(
        ConnectionManager.AGENT_KIND_CLIENT_CAPABILITY,
        ConnectionManager.PLUGIN_APPROVALS_CLIENT_CAPABILITY,
        ConnectionManager.TOOL_EVENTS_CLIENT_CAPABILITY,
        ConnectionManager.USAGE_REFRESHING_CLIENT_CAPABILITY,
      ),
      options.caps,
    )
  }

  @Test
  fun operatorScopesForStoredDeviceToken_preservesRecordedScopes() {
    assertEquals(
      listOf("operator.read", "operator.write"),
      ConnectionManager.operatorScopesForStoredDeviceToken(
        listOf("operator.read", "operator.write", "operator.read", " "),
      ),
    )
  }

  @Test
  fun operatorScopesForStoredDeviceToken_failsClosedWhenMetadataIsEmpty() {
    assertEquals(
      emptyList<String>(),
      ConnectionManager.operatorScopesForStoredDeviceToken(emptyList()),
    )
  }

  @Test
  fun buildNodeConnectOptions_advertisesMobileUiOnlyWhileAvailable() {
    val unavailable = newManager(mobileUiAvailable = false).buildNodeConnectOptions()
    val available = newManager(mobileUiAvailable = true).buildNodeConnectOptions()

    assertFalse(unavailable.caps.contains(OpenClawCapability.MobileUI.rawValue))
    assertFalse(unavailable.commands.contains(ANDROID_USE_NODE_COMMAND))
    assertTrue(available.caps.contains(OpenClawCapability.MobileUI.rawValue))
    assertTrue(available.commands.contains(ANDROID_USE_NODE_COMMAND))
  }

  private fun localEndpoint(
    tls: Boolean,
    fingerprint: String? = null,
  ): GatewayEndpoint =
    GatewayEndpoint(
      stableId = "local-gateway",
      name = "Local Gateway",
      host = "127.0.0.1",
      port = 18789,
      tlsEnabled = tls,
      tlsFingerprintSha256 = fingerprint,
    )

  private fun newManager(
    mobileUiAvailable: Boolean = false,
    androidUseAvailable: (() -> Boolean)? = null,
  ): ConnectionManager {
    val context = RuntimeEnvironment.getApplication()
    context
      .getSharedPreferences("openclaw.node", android.content.Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    val prefs =
      SecurePrefs(
        context,
        securePrefsOverride = context.getSharedPreferences("connection-manager-test", android.content.Context.MODE_PRIVATE),
      )
    return ConnectionManager(
      prefs = prefs,
      androidUseAvailable = androidUseAvailable ?: { mobileUiAvailable },
    )
  }
}
