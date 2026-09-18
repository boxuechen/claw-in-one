package ai.openclaw.app.gateway

import ai.openclaw.app.GatewayCredentials
import ai.openclaw.app.SecurePrefs
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class LocalGatewayPairingStoreTest {
  @Test
  fun roundTripPersistsOneCompletePairing() {
    val (prefs, securePrefs) = freshPrefs()
    val pairing = pairing(credentials = GatewayCredentials(bootstrapToken = "bootstrap-token"))

    assertTrue(prefs.localGatewayPairing.replace(pairing))

    val restored = LocalGatewayPairingStore(SecurePrefs(RuntimeEnvironment.getApplication(), securePrefs))
    assertEquals(pairing, restored.pairing.value)
    assertEquals(pairing.stableId, restored.stableId.value)
    assertEquals(pairing.endpoint(), restored.pairing.value?.endpoint())
  }

  @Test
  fun replacementHasNoRegistryOrActiveSelection() {
    val (prefs, _) = freshPrefs()
    val first = pairing(host = "127.0.0.1", port = 18789)
    val second = pairing(host = "127.0.0.1", port = 28789)

    assertEquals(LocalGatewayPairingSnapshot(revision = 0L, pairing = null), prefs.localGatewayPairing.snapshot())
    assertTrue(prefs.localGatewayPairing.replace(first))
    assertEquals(LocalGatewayPairingSnapshot(revision = 1L, pairing = first), prefs.localGatewayPairing.snapshot())
    assertTrue(prefs.localGatewayPairing.replace(second))

    assertEquals(second, prefs.localGatewayPairing.pairing.value)
    assertEquals(LocalGatewayPairingSnapshot(revision = 2L, pairing = second), prefs.localGatewayPairing.snapshot())
    assertFalse(prefs.localGatewayPairing.clear(first.stableId))
    assertEquals(2L, prefs.localGatewayPairing.snapshot().revision)
    assertEquals(second, prefs.localGatewayPairing.pairing.value)
    assertTrue(prefs.localGatewayPairing.clear(second.stableId))
    assertEquals(LocalGatewayPairingSnapshot(revision = 3L, pairing = null), prefs.localGatewayPairing.snapshot())
    assertNull(prefs.localGatewayPairing.pairing.value)
  }

  @Test
  fun failedCommitDoesNotPublishReplacement() {
    val (_, securePrefs) = freshPrefs()
    val failingCommitPrefs =
      object : SharedPreferences by securePrefs {
        override fun edit(): SharedPreferences.Editor {
          val editor = securePrefs.edit()
          return object : SharedPreferences.Editor by editor {
            override fun putString(
              key: String?,
              value: String?,
            ): SharedPreferences.Editor {
              editor.putString(key, value)
              return this
            }

            override fun commit(): Boolean = false
          }
        }
      }
    val store = LocalGatewayPairingStore(SecurePrefs(RuntimeEnvironment.getApplication(), failingCommitPrefs))

    assertFalse(store.replace(pairing()))
    assertNull(store.pairing.value)
    assertNull(store.stableId.value)
  }

  @Test
  fun malformedOrUnsupportedStorageIsIgnoredWithoutMigration() {
    val (_, securePrefs) = freshPrefs()
    securePrefs.edit().putString(LocalGatewayPairingStore.STORAGE_KEY, "{not-json").commit()
    assertNull(LocalGatewayPairingStore(SecurePrefs(RuntimeEnvironment.getApplication(), securePrefs)).pairing.value)

    securePrefs
      .edit()
      .putString(
        LocalGatewayPairingStore.STORAGE_KEY,
        """{"version":2,"pairing":{"stableId":"future","host":"127.0.0.1","port":18789,"tls":false}}""",
      ).commit()
    assertNull(LocalGatewayPairingStore(SecurePrefs(RuntimeEnvironment.getApplication(), securePrefs)).pairing.value)
  }

  @Test
  fun tlsPairingWithoutAuthenticatedFingerprintIsRejected() {
    val (prefs, securePrefs) = freshPrefs()
    assertThrows(IllegalArgumentException::class.java) {
      prefs.localGatewayPairing.replace(
        LocalGatewayPairing(
          stableId = "local-gateway",
          host = "127.0.0.1",
          port = 18789,
          tls = true,
          tlsFingerprintSha256 = null,
        ),
      )
    }

    securePrefs
      .edit()
      .putString(
        LocalGatewayPairingStore.STORAGE_KEY,
        """{"version":1,"pairing":{"stableId":"local-gateway","host":"127.0.0.1","port":18789,"tls":true}}""",
      ).commit()

    assertNull(LocalGatewayPairingStore(SecurePrefs(RuntimeEnvironment.getApplication(), securePrefs)).pairing.value)
  }

  @Test
  fun nonLoopbackPairingIsRejectedBeforePublication() {
    val (prefs, _) = freshPrefs()

    assertThrows(IllegalArgumentException::class.java) {
      prefs.localGatewayPairing.replace(pairing(host = "192.168.1.20"))
    }
    assertNull(prefs.localGatewayPairing.pairing.value)
  }

  private fun freshPrefs(): Pair<SecurePrefs, SharedPreferences> {
    val context = RuntimeEnvironment.getApplication()
    context
      .getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    val securePrefs =
      context.getSharedPreferences(
        "local-gateway-pairing-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    securePrefs.edit().clear().commit()
    return SecurePrefs(context, securePrefs) to securePrefs
  }

  private fun pairing(
    host: String = "127.0.0.1",
    port: Int = 18789,
    credentials: GatewayCredentials = GatewayCredentials(),
  ): LocalGatewayPairing =
    LocalGatewayPairing.from(
      endpoint =
        testGatewayEndpoint(
          host = host,
          port = port,
          tlsEnabled = true,
          contextPath = "/openclaw",
          tlsFingerprintSha256 = "ab".repeat(32),
        ),
      credentials = credentials,
    )
}
