package ai.openclaw.app.androiddevice

import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID

internal data class AndroidDeviceGatewayConnection(
  val stableId: String,
  val generation: Long,
  val catalogRevision: Long,
  val methods: Set<String>,
  val lease: GatewaySession.RequestLease,
)

internal interface AndroidDeviceTransport {
  fun capture(): AndroidDeviceGatewayConnection?

  fun publish(
    connection: AndroidDeviceGatewayConnection,
    block: () -> Unit,
  ): Boolean

  suspend fun request(
    connection: AndroidDeviceGatewayConnection,
    method: String,
    params: String,
    timeoutMs: Long,
  ): String
}

internal fun interface AndroidDeviceEndpointDiscovery {
  suspend fun discoverLocalAdbTlsEndpoint(): String?
}

internal enum class AndroidDeviceAvailability {
  GatewayOffline,
  Unsupported,
  Available,
}

internal enum class AndroidDeviceOperation {
  Pairing,
  Connecting,
  VerifyingReconnect,
  Forgetting,
}

internal data class AndroidDeviceConnectionState(
  val availability: AndroidDeviceAvailability = AndroidDeviceAvailability.GatewayOffline,
  val snapshot: AndroidDeviceSnapshot? = null,
  val refreshing: Boolean = false,
  val operation: AndroidDeviceOperation? = null,
  val notice: String? = null,
)

internal class AndroidDeviceConnectionController(
  private val scope: CoroutineScope,
  private val transport: AndroidDeviceTransport,
  private val challengeStore: AndroidDeviceChallengeStore,
  private val endpointDiscovery: AndroidDeviceEndpointDiscovery,
  private val json: Json,
  private val createVerificationId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
  private val lock = Any()
  private var retainedConnection: AndroidDeviceGatewayConnection? = null
  private val mutationMutex = Mutex()
  private val mutableState = MutableStateFlow(AndroidDeviceConnectionState())
  val state = mutableState.asStateFlow()

  fun refresh() {
    val connection = transport.capture()
    if (connection == null) {
      resetIfOffline()
      return
    }
    if (!publish(connection) { mutableState.value = mutableState.value.copy(refreshing = true) }) return
    scope.launch { mutationMutex.withLock { refreshCurrent(connection) } }
  }

  fun pair(
    endpoint: String,
    pairingCode: String,
  ) {
    val normalizedEndpoint = endpoint.trim()
    if (normalizedEndpoint.isEmpty() || normalizedEndpoint.length > 300) {
      publishLocalFailure("Enter the address shown by Android.")
      return
    }
    if (!pairingCode.matches(Regex("[0-9]{6}"))) {
      publishLocalFailure("Enter the six-digit pairing code shown by Android.")
      return
    }
    var expectedVerificationId: String? = null
    startMutation(
      operation = AndroidDeviceOperation.Pairing,
      expectedVerificationId = { expectedVerificationId },
    ) { connection ->
      val challenge = challengeStore.create()
      expectedVerificationId = challenge.id
      try {
        transport.request(
          connection,
          ANDROID_DEVICE_PAIR_METHOD,
          androidDevicePairParams(normalizedEndpoint, pairingCode, challenge.id),
          35_000,
        )
      } finally {
        challengeStore.delete(challenge)
      }
    }
  }

  fun connect(endpoint: String?) {
    val normalized = endpoint?.trim()?.takeIf(String::isNotEmpty)
    if (normalized != null && normalized.length > 300) {
      publishLocalFailure("Enter the current address shown by Android.")
      return
    }
    startMutation(AndroidDeviceOperation.Connecting) { connection ->
      val endpointCandidate = normalized ?: endpointDiscovery.discoverLocalAdbTlsEndpoint()
      transport.request(
        connection,
        ANDROID_DEVICE_CONNECT_METHOD,
        androidDeviceConnectParams(endpointCandidate),
        25_000,
      )
    }
  }

  fun verifyReconnect(endpoint: String?) {
    val normalized = endpoint?.trim()?.takeIf(String::isNotEmpty)
    if (normalized != null && normalized.length > 300) {
      publishLocalFailure("Enter the current address shown by Android.")
      return
    }
    val verificationId = createVerificationId()
    check(verificationId.matches(Regex("[0-9a-f]{32}")))
    startMutation(
      operation = AndroidDeviceOperation.VerifyingReconnect,
      expectedVerificationId = { verificationId },
    ) { connection ->
      val endpointCandidate = normalized ?: endpointDiscovery.discoverLocalAdbTlsEndpoint()
      transport.request(
        connection,
        ANDROID_DEVICE_VERIFY_RECONNECT_METHOD,
        androidDeviceVerifyReconnectParams(verificationId, endpointCandidate),
        35_000,
      )
    }
  }

  fun forget() {
    startMutation(AndroidDeviceOperation.Forgetting) { connection ->
      transport.request(
        connection,
        ANDROID_DEVICE_FORGET_METHOD,
        androidDeviceForgetParams(),
        20_000,
      )
    }
  }

  fun dismissNotice() {
    synchronized(lock) { mutableState.value = mutableState.value.copy(notice = null) }
  }

  fun invalidateConnection(connection: GatewaySession.RequestLease) {
    synchronized(lock) {
      if (retainedConnection?.lease !== connection) return
      retainedConnection = null
      mutableState.value = AndroidDeviceConnectionState()
    }
  }

  private fun resetIfOffline() {
    synchronized(lock) {
      if (retainedConnection?.lease?.isCurrent() == true) return
      retainedConnection = null
      mutableState.value = AndroidDeviceConnectionState()
    }
  }

  private fun publish(
    connection: AndroidDeviceGatewayConnection,
    block: () -> Unit,
  ): Boolean =
    transport.publish(connection) {
      synchronized(lock) {
        if (retainedConnection != connection) mutableState.value = AndroidDeviceConnectionState()
        retainedConnection = connection
        block()
      }
    }

  private fun startMutation(
    operation: AndroidDeviceOperation,
    expectedVerificationId: () -> String? = { null },
    request: suspend (AndroidDeviceGatewayConnection) -> String,
  ) {
    val connection = transport.capture()
    if (connection == null) {
      resetIfOffline()
      return
    }
    if (!connection.methods.containsAll(androidDeviceMethods)) {
      publish(connection) {
        mutableState.value = AndroidDeviceConnectionState(availability = AndroidDeviceAvailability.Unsupported)
      }
      return
    }
    var admitted = false
    publish(connection) {
      if (mutableState.value.operation == null) {
        mutableState.value =
          mutableState.value.copy(
            availability = AndroidDeviceAvailability.Available,
            operation = operation,
            notice = null,
          )
        admitted = true
      }
    }
    if (!admitted) return
    scope.launch {
      mutationMutex.withLock {
        try {
          val raw = request(connection)
          val snapshot = parseAndroidDeviceSnapshot(raw, json) ?: error("OpenClaw returned an invalid development-connection status.")
          requireExpectedVerification(snapshot, expectedVerificationId())
          publish(connection) {
            mutableState.value =
              AndroidDeviceConnectionState(
                availability = AndroidDeviceAvailability.Available,
                snapshot = snapshot,
              )
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          reconcileAfterMutation(connection, error, expectedVerificationId())
        }
      }
    }
  }

  private suspend fun reconcileAfterMutation(
    connection: AndroidDeviceGatewayConnection,
    error: Throwable,
    expectedVerificationId: String?,
  ) {
    val reconciled =
      try {
        requestSnapshot(connection)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        null
      }
    publish(connection) {
      val verificationConfirmed = expectedVerificationId == null || reconciled?.verificationId == expectedVerificationId
      mutableState.value =
        AndroidDeviceConnectionState(
          availability = AndroidDeviceAvailability.Available,
          snapshot = reconciled,
          notice =
            when {
              reconciled?.status == AndroidDeviceStatus.Ready && verificationConfirmed -> null
              error is GatewayRequestRejected -> error.gatewayError.message
              else -> "The result could not be confirmed. Check the connection before trying again."
            },
        )
    }
  }

  private fun requireExpectedVerification(
    snapshot: AndroidDeviceSnapshot,
    expectedVerificationId: String?,
  ) {
    if (expectedVerificationId != null && snapshot.verificationId != expectedVerificationId) {
      error("OpenClaw did not confirm the requested phone reconnect verification.")
    }
  }

  private suspend fun refreshCurrent(connection: AndroidDeviceGatewayConnection) {
    if (!connection.methods.containsAll(androidDeviceMethods)) {
      publish(connection) {
        mutableState.value = AndroidDeviceConnectionState(availability = AndroidDeviceAvailability.Unsupported)
      }
      return
    }
    if (!publish(connection) {
        mutableState.value =
          mutableState.value.copy(
            availability = AndroidDeviceAvailability.Available,
            refreshing = true,
            notice = null,
          )
      }
    ) {
      return
    }
    try {
      val snapshot = requestSnapshot(connection)
      publish(connection) {
        mutableState.value =
          mutableState.value.copy(
            availability = AndroidDeviceAvailability.Available,
            snapshot = snapshot,
            refreshing = false,
          )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      publish(connection) {
        mutableState.value =
          mutableState.value.copy(
            availability = AndroidDeviceAvailability.Available,
            refreshing = false,
            notice =
              if (error is GatewayRequestRejected) {
                error.gatewayError.message
              } else {
                "Could not check the Android development connection."
              },
          )
      }
    }
  }

  private suspend fun requestSnapshot(connection: AndroidDeviceGatewayConnection): AndroidDeviceSnapshot =
    parseAndroidDeviceSnapshot(
      transport.request(
        connection,
        ANDROID_DEVICE_STATUS_METHOD,
        androidDeviceStatusParams(),
        10_000,
      ),
      json,
    ) ?: error("OpenClaw returned an invalid development-connection status.")

  private fun publishLocalFailure(message: String) {
    synchronized(lock) { mutableState.value = mutableState.value.copy(notice = message) }
  }
}
