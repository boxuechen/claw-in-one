package ai.openclaw.app.androiddevice

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceConnectionControllerTest {
  @Test
  fun lateRetirementAndReadbackCannotClearReplacementSnapshot() =
    runTest {
      val transport = FakeTransport()
      val old = requireNotNull(transport.connection)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      transport.beforeRequest = { connection, _ ->
        if (connection === old) {
          entered.complete(Unit)
          release.await()
        }
      }
      val controller = controller(this, transport, FakeChallengeStore())
      controller.refresh()
      runCurrent()
      assertTrue(entered.isCompleted)
      controller.invalidateConnection(old.lease)
      assertEquals(AndroidDeviceAvailability.GatewayOffline, controller.state.value.availability)
      transport.connection = connection()
      transport.statusResult = readySnapshot()
      controller.refresh()
      release.complete(Unit)
      advanceUntilIdle()
      assertEquals(
        AndroidDeviceStatus.Ready,
        controller.state.value.snapshot
          ?.status,
      )
      controller.invalidateConnection(old.lease)
      assertEquals(
        AndroidDeviceStatus.Ready,
        controller.state.value.snapshot
          ?.status,
      )
    }

  @Test
  fun queuedMutationNeverDispatchesThroughReplacementConnection() =
    runTest {
      val transport = FakeTransport()
      val old = requireNotNull(transport.connection)
      val challenges = FakeChallengeStore()
      val controller = controller(this, transport, challenges)
      controller.pair("10.0.0.2:41001", "654321")
      transport.connection = connection()
      controller.invalidateConnection(old.lease)
      advanceUntilIdle()
      assertTrue(transport.calls.isEmpty())
      assertEquals(listOf("create", "delete"), challenges.calls)
      assertEquals(AndroidDeviceAvailability.GatewayOffline, controller.state.value.availability)
      controller.refresh()
      advanceUntilIdle()
      assertEquals(listOf(ANDROID_DEVICE_STATUS_METHOD), transport.calls.map { it.method })
    }

  @Test
  fun refreshDistinguishesOfflineUnsupportedAndSetupRequired() =
    runTest {
      val transport = FakeTransport().apply { connection = null }
      val controller = controller(this, transport, FakeChallengeStore())

      controller.refresh()
      advanceUntilIdle()
      assertEquals(AndroidDeviceAvailability.GatewayOffline, controller.state.value.availability)

      transport.connection = connection(methods = setOf(ANDROID_DEVICE_STATUS_METHOD))
      controller.refresh()
      advanceUntilIdle()
      assertEquals(AndroidDeviceAvailability.Unsupported, controller.state.value.availability)

      transport.connection = connection()
      controller.refresh()
      advanceUntilIdle()
      assertEquals(
        AndroidDeviceStatus.SetupRequired,
        controller.state.value.snapshot
          ?.status,
      )
    }

  @Test
  fun pairPublishesAndDeletesOneChallengeWithoutRetainingSecrets() =
    runTest {
      val transport = FakeTransport()
      val challenges = FakeChallengeStore()
      val controller = controller(this, transport, challenges)
      transport.pairResult = readySnapshot("c".repeat(32))

      controller.pair("10.0.0.2:41001", "654321")
      assertEquals(AndroidDeviceOperation.Pairing, controller.state.value.operation)
      advanceUntilIdle()

      assertEquals(listOf("create", "delete"), challenges.calls)
      assertEquals(listOf(ANDROID_DEVICE_PAIR_METHOD), transport.calls.map { it.method })
      assertTrue(
        transport.calls
          .single()
          .params
          .contains("\"pairingCode\":\"654321\""),
      )
      assertTrue(
        transport.calls
          .single()
          .params
          .contains("\"challengeId\":\"${"c".repeat(32)}\""),
      )
      assertEquals(
        AndroidDeviceStatus.Ready,
        controller.state.value.snapshot
          ?.status,
      )
      assertNull(controller.state.value.operation)
      assertNull(controller.state.value.notice)
      assertFalse(
        controller.state.value
          .toString()
          .contains("654321"),
      )
    }

  @Test
  fun lostPairResponseUsesReadbackAndNeverRepeatsMutation() =
    runTest {
      val transport =
        FakeTransport().apply {
          losePairResponse = true
          statusResult = readySnapshot("c".repeat(32))
        }
      val challenges = FakeChallengeStore()
      val controller = controller(this, transport, challenges)

      controller.pair("10.0.0.2:41001", "654321")
      advanceUntilIdle()

      assertEquals(1, transport.calls.count { it.method == ANDROID_DEVICE_PAIR_METHOD })
      assertEquals(1, transport.calls.count { it.method == ANDROID_DEVICE_STATUS_METHOD })
      assertEquals(
        AndroidDeviceStatus.Ready,
        controller.state.value.snapshot
          ?.status,
      )
      assertEquals(listOf("create", "delete"), challenges.calls)
    }

  @Test
  fun invalidInputDoesNotCreateAChallengeOrReachGateway() =
    runTest {
      val transport = FakeTransport()
      val challenges = FakeChallengeStore()
      val controller = controller(this, transport, challenges)

      controller.pair("", "123")
      advanceUntilIdle()

      assertTrue(
        controller.state.value.notice
          ?.isNotBlank() == true,
      )
      assertTrue(transport.calls.isEmpty())
      assertTrue(challenges.calls.isEmpty())
    }

  @Test
  fun forgetAndDisconnectPublishTruthWithoutKeepingAStaleTarget() =
    runTest {
      val transport = FakeTransport().apply { forgetResult = revokedSnapshot() }
      val controller = controller(this, transport, FakeChallengeStore())

      controller.forget()
      advanceUntilIdle()
      assertEquals(
        AndroidDeviceStatus.Revoked,
        controller.state.value.snapshot
          ?.status,
      )

      controller.invalidateConnection(requireNotNull(transport.connection).lease)
      assertEquals(AndroidDeviceAvailability.GatewayOffline, controller.state.value.availability)
      assertNull(controller.state.value.snapshot)
    }

  @Test
  fun reconnectVerificationRequiresMatchingOwnerEvidence() =
    runTest {
      val verificationId = "e".repeat(32)
      val transport = FakeTransport()
      val controller =
        AndroidDeviceConnectionController(
          scope = this,
          transport = transport,
          challengeStore = FakeChallengeStore(),
          endpointDiscovery = FakeEndpointDiscovery(),
          json = Json { ignoreUnknownKeys = true },
          createVerificationId = { verificationId },
        )

      controller.verifyReconnect(null)
      assertEquals(AndroidDeviceOperation.VerifyingReconnect, controller.state.value.operation)
      advanceUntilIdle()

      assertEquals(listOf(ANDROID_DEVICE_VERIFY_RECONNECT_METHOD), transport.calls.map { it.method })
      assertEquals(
        verificationId,
        controller.state.value.snapshot
          ?.verificationId,
      )
      assertNull(controller.state.value.notice)
    }

  @Test
  fun lostReconnectResponseAcceptsOnlyMatchingStatusEvidence() =
    runTest {
      val verificationId = "e".repeat(32)
      val transport = FakeTransport().apply { loseVerifyResponse = true }
      val controller =
        AndroidDeviceConnectionController(
          scope = this,
          transport = transport,
          challengeStore = FakeChallengeStore(),
          endpointDiscovery = FakeEndpointDiscovery(),
          json = Json { ignoreUnknownKeys = true },
          createVerificationId = { verificationId },
        )

      controller.verifyReconnect(null)
      advanceUntilIdle()

      assertEquals(1, transport.calls.count { it.method == ANDROID_DEVICE_VERIFY_RECONNECT_METHOD })
      assertEquals(1, transport.calls.count { it.method == ANDROID_DEVICE_STATUS_METHOD })
      assertEquals(
        verificationId,
        controller.state.value.snapshot
          ?.verificationId,
      )
      assertNull(controller.state.value.notice)
    }

  @Test
  fun staleReadyStatusCannotConfirmALostReconnectResponse() =
    runTest {
      val transport =
        FakeTransport().apply {
          loseVerifyResponse = true
          publishVerifyEvidence = false
        }
      val controller =
        AndroidDeviceConnectionController(
          scope = this,
          transport = transport,
          challengeStore = FakeChallengeStore(),
          endpointDiscovery = FakeEndpointDiscovery(),
          json = Json { ignoreUnknownKeys = true },
          createVerificationId = { "e".repeat(32) },
        )

      controller.verifyReconnect(null)
      advanceUntilIdle()

      assertNull(
        controller.state.value.snapshot
          ?.verificationId,
      )
      assertTrue(
        controller.state.value.notice
          ?.isNotBlank() == true,
      )
      assertEquals(1, transport.calls.count { it.method == ANDROID_DEVICE_VERIFY_RECONNECT_METHOD })
    }

  @Test
  fun reconnectDiscoversTheCurrentLocalEndpointBeforeOwnerVerification() =
    runTest {
      val endpointDiscovery = FakeEndpointDiscovery("192.168.31.12:35101")
      val transport = FakeTransport()
      val controller = controller(this, transport, FakeChallengeStore(), endpointDiscovery)

      controller.verifyReconnect(null)
      advanceUntilIdle()

      assertEquals(1, endpointDiscovery.calls)
      assertTrue(
        transport.calls
          .single { it.method == ANDROID_DEVICE_VERIFY_RECONNECT_METHOD }
          .params
          .contains("\"endpoint\":\"192.168.31.12:35101\""),
      )
      assertEquals(
        AndroidDeviceStatus.Ready,
        controller.state.value.snapshot
          ?.status,
      )
    }

  @Test
  fun explicitEndpointBypassesDiscovery() =
    runTest {
      val endpointDiscovery = FakeEndpointDiscovery("192.168.31.12:35101")
      val transport = FakeTransport()
      val controller = controller(this, transport, FakeChallengeStore(), endpointDiscovery)

      controller.connect("10.0.0.2:41001")
      advanceUntilIdle()

      assertEquals(0, endpointDiscovery.calls)
      assertTrue(
        transport.calls
          .single { it.method == ANDROID_DEVICE_CONNECT_METHOD }
          .params
          .contains("\"endpoint\":\"10.0.0.2:41001\""),
      )
    }

  private fun controller(
    scope: CoroutineScope,
    transport: FakeTransport,
    challenges: FakeChallengeStore,
    endpointDiscovery: FakeEndpointDiscovery = FakeEndpointDiscovery(),
  ): AndroidDeviceConnectionController =
    AndroidDeviceConnectionController(
      scope = scope,
      transport = transport,
      challengeStore = challenges,
      endpointDiscovery = endpointDiscovery,
      json = Json { ignoreUnknownKeys = true },
    )

  private data class Call(
    val method: String,
    val params: String,
  )

  private class FakeTransport : AndroidDeviceTransport {
    var connection: AndroidDeviceGatewayConnection? = connection()
    var statusResult = setupRequiredSnapshot()
    var pairResult = readySnapshot("c".repeat(32))
    var forgetResult = revokedSnapshot()
    var losePairResponse = false
    var loseVerifyResponse = false
    var publishVerifyEvidence = true
    var beforeRequest: suspend (AndroidDeviceGatewayConnection, String) -> Unit = { _, _ -> }
    val calls = mutableListOf<Call>()

    override fun capture(): AndroidDeviceGatewayConnection? = connection

    override fun publish(
      connection: AndroidDeviceGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (connection != this.connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: AndroidDeviceGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      if (connection != this.connection) throw GatewayRequestNotEnqueued("connection changed")
      calls += Call(method, params)
      beforeRequest(connection, method)
      return when (method) {
        ANDROID_DEVICE_STATUS_METHOD -> statusResult
        ANDROID_DEVICE_PAIR_METHOD -> {
          if (losePairResponse) throw IllegalStateException("response lost")
          pairResult
        }
        ANDROID_DEVICE_CONNECT_METHOD -> readySnapshot()
        ANDROID_DEVICE_VERIFY_RECONNECT_METHOD -> {
          val verificationId = Regex("\"verificationId\":\"([0-9a-f]{32})\"").find(params)?.groupValues?.get(1)
          statusResult = readySnapshot(verificationId.takeIf { publishVerifyEvidence })
          if (loseVerifyResponse) throw IllegalStateException("response lost")
          statusResult
        }
        ANDROID_DEVICE_FORGET_METHOD -> forgetResult
        else -> error("Unexpected method $method")
      }
    }
  }

  private class FakeChallengeStore : AndroidDeviceChallengeStore {
    val calls = mutableListOf<String>()

    override suspend fun create(): AndroidDeviceChallenge {
      calls += "create"
      return AndroidDeviceChallenge("c".repeat(32), "fixture-challenge")
    }

    override suspend fun delete(challenge: AndroidDeviceChallenge) {
      calls += "delete"
    }
  }

  private class FakeEndpointDiscovery(
    private val endpoint: String? = null,
  ) : AndroidDeviceEndpointDiscovery {
    var calls = 0

    override suspend fun discoverLocalAdbTlsEndpoint(): String? {
      calls += 1
      return endpoint
    }
  }

  companion object {
    private fun connection(methods: Set<String> = androidDeviceMethods) = AndroidDeviceGatewayConnection("gateway", 1, 1, methods, GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("No RPC expected") })

    private fun setupRequiredSnapshot() = """{"protocolVersion":1,"status":"setup_required","paired":false,"connected":false,"target":null}"""

    private fun revokedSnapshot() = """{"protocolVersion":1,"status":"revoked","paired":false,"connected":false,"target":null}"""

    private fun readySnapshot(verificationId: String? = null) = """{"protocolVersion":1,"status":"ready","paired":true,"connected":true${verificationId?.let { ",\"verificationId\":\"$it\"" }.orEmpty()},"target":{"id":"${"d".repeat(64)}","product":"shiba","model":"Pixel 8","androidApi":"37"}}"""
  }
}
