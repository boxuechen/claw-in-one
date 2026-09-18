package ai.openclaw.app.node

import ai.openclaw.app.GatewayConnectionProblem
import ai.openclaw.app.gateway.GatewayConnectOptions
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gatewayConnectionProblem
import ai.openclaw.app.gatewayProblemAfterDisconnect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation of the local Node transport, never an execution grant or a server-list projection. */
internal data class NodeConnectionState(
  val connected: Boolean = false,
  val statusText: String = "Offline",
  val problem: GatewayConnectionProblem? = null,
)

internal interface NodeConnectionEvents {
  fun connected()

  fun disconnected(message: String)

  fun connectFailed(
    error: GatewaySession.ErrorShape,
    pauseReconnect: Boolean,
  )
}

/**
 * Owns the local Node session and its connection presentation. GatewaySession remains the sole
 * transport/retry owner; composition retains the shared paired endpoint and authentication.
 * The session is available to composition for its existing event and captured-request interfaces.
 */
internal class NodeConnection(
  private val publicationLock: Any,
  private val onStateChanged: () -> Unit,
  private val onConnected: () -> Unit,
  private val onDisconnected: () -> Unit,
  private val onApprovalRequired: () -> Unit,
  initialPresentation: NodeConnectionState = NodeConnectionState(),
  createSession: (NodeConnectionEvents) -> GatewaySession,
) {
  private var presentation = initialPresentation
  private val mutableConnected = MutableStateFlow(initialPresentation.connected)
  val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

  val state: NodeConnectionState
    get() = synchronized(publicationLock) { presentation }

  val session: GatewaySession =
    createSession(
      object : NodeConnectionEvents {
        override fun connected() {
          publish { NodeConnectionState(connected = true, statusText = "Connected") }
          onConnected()
        }

        override fun disconnected(message: String) {
          onDisconnected()
          publish { NodeConnectionState(statusText = message, problem = gatewayProblemAfterDisconnect(it.problem, message)) }
        }

        override fun connectFailed(
          error: GatewaySession.ErrorShape,
          pauseReconnect: Boolean,
        ) {
          publish { it.copy(problem = gatewayConnectionProblem(error, pauseReconnect)) }
          if (nodeConnectFailureNeedsApprovalRefresh(error)) onApprovalRequired()
        }
      },
    )

  fun beginConnecting() {
    publish { it.copy(statusText = "Connecting…", problem = null) }
  }

  fun prepareDisconnect() {
    publish { NodeConnectionState() }
  }

  fun connect(
    endpoint: GatewayEndpoint,
    token: String?,
    bootstrapToken: String?,
    password: String?,
    options: GatewayConnectOptions,
    tls: GatewayTlsParams?,
  ) {
    // Transport methods stay outside the presentation monitor: their callbacks may publish state.
    session.connect(endpoint, token, bootstrapToken, password, options, tls)
  }

  private fun publish(update: (NodeConnectionState) -> NodeConnectionState) {
    synchronized(publicationLock) {
      presentation = update(presentation)
      mutableConnected.value = presentation.connected
      onStateChanged()
    }
  }
}

internal fun nodeConnectFailureNeedsApprovalRefresh(error: GatewaySession.ErrorShape): Boolean = error.details?.code == "PAIRING_REQUIRED"
