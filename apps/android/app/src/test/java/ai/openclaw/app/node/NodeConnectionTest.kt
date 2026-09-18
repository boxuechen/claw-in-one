package ai.openclaw.app.node

import ai.openclaw.app.SecurePrefs
import ai.openclaw.app.gateway.DeviceAuthStore
import ai.openclaw.app.gateway.DeviceIdentityStore
import ai.openclaw.app.gateway.GatewayClientInfo
import ai.openclaw.app.gateway.GatewayConnectOptions
import ai.openclaw.app.gateway.GatewayErrorDetails
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.testGatewayEndpoint
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NodeConnectionTest {
  private class Fixture {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = SecurePrefs(app, securePrefsOverride = app.getSharedPreferences("node-connection-test", Context.MODE_PRIVATE))

    // The real transport records connect intent, but this cancelled scope cannot open sockets.
    private val scope = CoroutineScope(SupervisorJob().apply { cancel() })
    lateinit var session: GatewaySession
    val published = mutableListOf<NodeConnectionState>()
    val effects = mutableListOf<String>()
    lateinit var events: NodeConnectionEvents
    val owner: NodeConnection =
      NodeConnection(
        publicationLock = Any(),
        onStateChanged = { published += snapshot() },
        onConnected = { effects += "connected" },
        onDisconnected = { effects += "disconnected" },
        onApprovalRequired = { effects += "approval" },
        createSession = {
          events = it
          session =
            GatewaySession(
              scope = scope,
              identityStore = DeviceIdentityStore.withPrefs(app, prefs),
              deviceAuthStore = DeviceAuthStore(prefs),
              onConnected = { events.connected() },
              onDisconnected = events::disconnected,
              onConnectFailure = events::connectFailed,
              onEvent = { _, _ -> },
            )
          session
        },
      )

    private fun snapshot() = owner.state
  }

  @Test
  fun onlyReadyCallbackMakesTheLocalNodeConnected() {
    val fixture = Fixture()
    assertFalse(fixture.owner.connected.value)
    fixture.owner.beginConnecting()
    assertFalse(fixture.owner.connected.value)
    assertEquals("Connecting…", fixture.owner.state.statusText)
    fixture.events.connected()
    assertTrue(fixture.owner.connected.value)
    assertEquals(NodeConnectionState(true, "Connected"), fixture.owner.state)
    assertEquals(listOf("connected"), fixture.effects)
    assertEquals(fixture.owner.state, fixture.published.last())
    assertNull(desired(fixture.session))
  }

  @Test
  fun disconnectPublishesOfflineAndKeepsTheExistingCleanupCallback() {
    val fixture = Fixture()
    fixture.events.connected()
    fixture.events.disconnected("Gateway closed")
    assertFalse(fixture.owner.connected.value)
    assertEquals("Gateway closed", fixture.owner.state.statusText)
    assertEquals(listOf("connected", "disconnected"), fixture.effects)
    fixture.events.disconnected("Reconnecting…")
    assertFalse(fixture.owner.connected.value)
    assertEquals("Reconnecting…", fixture.owner.state.statusText)
  }

  @Test
  fun pairingGuidanceSurvivesOnlyItsAutomaticRetry() {
    val fixture = Fixture()
    fixture.events.connectFailed(pairingError(), false)
    assertEquals(listOf("approval"), fixture.effects)
    assertFalse(fixture.owner.connected.value)
    fixture.events.disconnected("Reconnecting…")
    assertEquals(
      "request-1",
      fixture.owner.state.problem
        ?.requestId,
    )
    assertTrue(
      fixture.owner.state.problem
        ?.canAutoRetry == true,
    )
    fixture.events.disconnected("Gateway error: timeout")
    assertNull(fixture.owner.state.problem)
  }

  @Test
  fun readyClearsPreviousFailureWithoutChangingTheTransport() {
    val fixture = Fixture()
    fixture.events.connectFailed(pairingError(), true)
    fixture.events.connected()
    assertNull(fixture.owner.state.problem)
    assertTrue(fixture.owner.connected.value)
    assertSame(fixture.session, fixture.owner.session)
  }

  @Test
  fun authFailureDoesNotRequestPairingRefreshAndKeepsProtocolDiagnostics() {
    val fixture = Fixture()
    fixture.events.connectFailed(
      GatewaySession.ErrorShape(
        code = "INVALID_REQUEST",
        message = "Protocol mismatch",
        details = GatewayErrorDetails(code = "PROTOCOL_MISMATCH", canRetryWithDeviceToken = false, recommendedNextStep = null, clientMinProtocol = 2, clientMaxProtocol = 3, expectedProtocol = 4),
      ),
      true,
    )
    assertTrue(fixture.effects.isEmpty())
    val problem = requireNotNull(fixture.owner.state.problem)
    assertEquals("PROTOCOL_MISMATCH", problem.code)
    assertEquals(4, problem.expectedProtocol)
    assertTrue(problem.pauseReconnect)
    fixture.events.disconnected("Reconnecting…")
    assertNull(fixture.owner.state.problem)
  }

  @Test
  fun explicitPreparationClearsStateWithoutOpeningOrClosingATransport() {
    val fixture = Fixture()
    fixture.events.connected()
    fixture.owner.prepareDisconnect()
    assertEquals(NodeConnectionState(), fixture.owner.state)
    assertFalse(fixture.owner.connected.value)
    assertNull(desired(fixture.session))
    fixture.owner.beginConnecting()
    assertFalse(fixture.owner.connected.value)
  }

  @Test
  fun connectRecordsTheExactTransportIntentWithoutClaimingReadiness() {
    val fixture = Fixture()
    val endpoint = testGatewayEndpoint("127.0.0.1", 18789)
    val options =
      GatewayConnectOptions(
        role = "node",
        scopes = emptyList(),
        caps = emptyList(),
        commands = emptyList(),
        permissions = emptyMap(),
        client = GatewayClientInfo(id = "openclaw-android", displayName = "Test", version = "test", platform = "android", mode = "node", instanceId = "test-node", deviceFamily = "Android", modelIdentifier = "fixture"),
      )
    fixture.owner.connect(endpoint, "token", "bootstrap", "password", options, null)
    val intent = requireNotNull(desired(fixture.session))
    assertSame(options, field(intent, "options"))
    assertEquals(endpoint, field(intent, "endpoint"))
    assertEquals("token", field(intent, "token"))
    assertEquals("bootstrap", field(intent, "bootstrapToken"))
    assertEquals("password", field(intent, "password"))
    assertNull(field(intent, "tls"))
    assertFalse(fixture.owner.connected.value)
    assertFalse(fixture.session.isReady())
  }

  private fun desired(session: GatewaySession): Any? = field(session, "desired")

  private fun field(
    target: Any,
    name: String,
  ): Any? =
    target.javaClass
      .getDeclaredField(name)
      .apply { isAccessible = true }
      .get(target)

  private fun pairingError() =
    GatewaySession.ErrorShape(
      code = "NOT_PAIRED",
      message = "Pairing required",
      details = GatewayErrorDetails(code = "PAIRING_REQUIRED", canRetryWithDeviceToken = false, requestId = "request-1", recommendedNextStep = "wait_then_retry", retryable = true),
    )
}
