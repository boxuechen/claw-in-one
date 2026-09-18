package ai.openclaw.app.mcp

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal interface McpConfigTransport {
  fun captureEpoch(): McpGatewayEpoch?

  fun isCurrent(epoch: McpGatewayEpoch): Boolean

  fun isConnected(): Boolean

  fun hasAdminScope(): Boolean

  fun canReadConfig(): Boolean

  fun canPatchConfig(): Boolean

  suspend fun request(
    epoch: McpGatewayEpoch,
    method: String,
    paramsJson: String,
    timeoutMs: Long,
  ): String
}

/** Owns MCP config reads and writes. Plugin lifecycle RPCs never enter this boundary. */
internal class McpConfigController(
  private val scope: CoroutineScope,
  private val transport: McpConfigTransport,
  private val json: Json,
) {
  private data class Availability(
    val connected: Boolean = false,
    val adminScope: Boolean = false,
    val readAvailable: Boolean = false,
    val patchAvailable: Boolean = false,
  )

  private val stateLock = Any()
  private val mutationMutex = Mutex()
  private val configState = MutableStateFlow(GatewayMcpConfigState())
  private val availability = MutableStateFlow(Availability())

  @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
  private val featureState: StateFlow<ConnectionState> =
    object : StateFlow<ConnectionState> {
      override val value: ConnectionState
        get() = connectionState(configState.value, availability.value)

      override val replayCache: List<ConnectionState>
        get() = listOf(value)

      override suspend fun collect(collector: FlowCollector<ConnectionState>): Nothing {
        combine(configState, availability, ::connectionState).collect(collector)
        error("StateFlow collection completed")
      }
    }
  val feature =
    ConnectionFeature(
      state = featureState,
      actions =
        ConnectionActions(
          refresh = ::refresh,
          addConnector = ::addConnector,
          addHttpServer = ::addHttpServer,
          setEnabled = ::setEnabled,
          remove = ::remove,
          dismissMutation = ::dismissMutation,
          reconcileMutation = ::reconcileMutation,
        ),
    )

  init {
    onConnectionChanged()
  }

  fun onConnectionChanged() {
    val connected = transport.captureEpoch() != null && transport.isConnected()
    availability.value =
      Availability(
        connected = connected,
        adminScope = connected && transport.hasAdminScope(),
        readAvailable = connected && transport.canReadConfig(),
        patchAvailable = connected && transport.canPatchConfig(),
      )
    if (!connected) onDisconnected()
  }

  private fun connectionState(
    config: GatewayMcpConfigState,
    status: Availability,
  ): ConnectionState =
    ConnectionState(
      connected = status.connected,
      adminScope = status.adminScope,
      readAvailable = status.readAvailable,
      patchAvailable = status.patchAvailable,
      config = config,
    )

  fun refresh() {
    scope.launch { refreshCurrent() }
  }

  fun addConnector(template: McpServerTemplate) {
    val config = mcpHttpServerConfig(template.url, template.transport, template.auth) ?: return
    startMutation(GatewayMcpMutationIntent.Add(template.serverName, config, template.followUp))
  }

  fun addHttpServer(
    name: String,
    target: String,
    transport: McpServerTransport,
  ) {
    val config = mcpHttpServerConfig(target, transport)
    if (config == null) {
      publishFailure(null, "Enter a valid HTTP or HTTPS MCP endpoint.")
      return
    }
    startMutation(GatewayMcpMutationIntent.Add(name.trim(), config, McpConnectorFollowUp.Endpoint))
  }

  fun setEnabled(
    name: String,
    enabled: Boolean,
  ) {
    startMutation(GatewayMcpMutationIntent.SetEnabled(name.trim(), enabled))
  }

  fun remove(name: String) {
    startMutation(GatewayMcpMutationIntent.Remove(name.trim()))
  }

  fun dismissMutation() {
    synchronized(stateLock) {
      if (configState.value.mutation is GatewayMcpMutationState.Working) return
      configState.value = configState.value.copy(mutation = GatewayMcpMutationState.Idle)
    }
  }

  fun reconcileMutation() {
    val unknown = configState.value.mutation as? GatewayMcpMutationState.UnknownOutcome ?: return
    scope.launch { reconcile(unknown.intent) }
  }

  fun onDisconnected() {
    availability.value = Availability()
    synchronized(stateLock) {
      val mutation = configState.value.mutation
      configState.value =
        GatewayMcpConfigState(
          mutation =
            if (mutation is GatewayMcpMutationState.Working) {
              GatewayMcpMutationState.UnknownOutcome(
                mutation.intent,
                "The connection ended before this MCP configuration change could be confirmed. Reconnect and refresh.",
              )
            } else {
              mutation
            },
        )
    }
  }

  private suspend fun refreshCurrent() {
    val epoch = transport.captureEpoch()
    if (epoch == null || !transport.isConnected()) {
      configState.value = GatewayMcpConfigState(errorText = "Connect OpenClaw to load MCP servers.")
      return
    }
    if (!transport.canReadConfig()) {
      publish(epoch) { it.copy(errorText = "Update OpenClaw to load MCP configuration.") }
      return
    }
    publish(epoch) { it.copy(refreshing = true, errorText = null) }
    try {
      val snapshot = readSnapshot(epoch)
      publish(epoch) { it.copy(summary = snapshot.toSummary(), refreshing = false, errorText = null) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      publish(epoch) { it.copy(refreshing = false, errorText = error.message ?: "Could not load MCP servers.") }
    }
  }

  private fun startMutation(intent: GatewayMcpMutationIntent) {
    val epoch = transport.captureEpoch()
    synchronized(stateLock) {
      when (configState.value.mutation) {
        is GatewayMcpMutationState.Working,
        is GatewayMcpMutationState.UnknownOutcome,
        -> return
        else -> Unit
      }
      if (epoch == null) {
        configState.value =
          configState.value.copy(
            mutation = GatewayMcpMutationState.Failed(intent, "Reconnect before changing MCP configuration."),
          )
        return
      }
      configState.value = configState.value.copy(mutation = GatewayMcpMutationState.Working(intent))
    }
    val capturedEpoch = epoch ?: return
    scope.launch { runMutation(capturedEpoch, intent) }
  }

  private suspend fun runMutation(
    epoch: McpGatewayEpoch,
    intent: GatewayMcpMutationIntent,
  ) {
    mutationMutex.withLock {
      if (!transport.isCurrent(epoch) || !transport.isConnected()) {
        publishFailure(intent, "Reconnect before changing MCP configuration.", epoch)
        return@withLock
      }
      if (!transport.hasAdminScope()) {
        publishFailure(intent, "This gateway connection needs operator.admin to manage MCP servers.", epoch)
        return@withLock
      }
      if (!transport.canReadConfig() || !transport.canPatchConfig()) {
        publishFailure(intent, "Update OpenClaw to manage MCP configuration.", epoch)
        return@withLock
      }
      try {
        val before = readSnapshot(epoch)
        val patch = buildMcpPatchParams(before, intent)
        if (patch is McpPatchBuildResult.Error) {
          publishFailure(intent, patch.message, epoch)
          return@withLock
        }
        val params = (patch as McpPatchBuildResult.Ready).paramsJson
        val ack = transport.request(epoch, "config.patch", params, MCP_CONFIG_WRITE_TIMEOUT_MS)
        if (json.parseToJsonElement(ack) !is JsonObject) error("Malformed config.patch response")
        val after = readSnapshot(epoch)
        publish(epoch) { current ->
          current.copy(
            summary = after.toSummary(),
            errorText = null,
            mutation =
              if (after.confirms(intent)) {
                GatewayMcpMutationState.Succeeded(intent, intent.successMessage())
              } else {
                GatewayMcpMutationState.UnknownOutcome(
                  intent,
                  "The write returned, but the refreshed configuration did not confirm it. Refresh before retrying.",
                )
              },
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: GatewayRequestOutcomeUnknown) {
        publish(epoch) {
          it.copy(
            mutation =
              GatewayMcpMutationState.UnknownOutcome(
                intent,
                "The MCP configuration result is unknown. Reconnect and refresh before retrying.",
              ),
          )
        }
      } catch (rejected: GatewayRequestRejected) {
        publishFailure(intent, rejected.gatewayError.message, epoch)
      } catch (error: Throwable) {
        publishFailure(intent, error.message ?: "Could not change MCP configuration.", epoch)
      }
    }
  }

  private suspend fun reconcile(intent: GatewayMcpMutationIntent) {
    val epoch = transport.captureEpoch()
    if (epoch == null || !transport.isConnected() || !transport.canReadConfig()) {
      publishFailure(intent, "Reconnect before refreshing MCP configuration.")
      return
    }
    publish(epoch) { it.copy(mutation = GatewayMcpMutationState.Working(intent)) }
    try {
      val snapshot = readSnapshot(epoch)
      publish(epoch) { current ->
        current.copy(
          summary = snapshot.toSummary(),
          errorText = null,
          mutation =
            if (snapshot.confirms(intent)) {
              GatewayMcpMutationState.Succeeded(intent, intent.successMessage())
            } else {
              GatewayMcpMutationState.Failed(intent, "The refreshed configuration did not confirm this change. You can try again.")
            },
        )
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      publish(epoch) {
        it.copy(
          mutation =
            GatewayMcpMutationState.UnknownOutcome(
              intent,
              "Could not refresh MCP configuration. Reconnect and try again.",
            ),
        )
      }
    }
  }

  private suspend fun readSnapshot(epoch: McpGatewayEpoch): McpConfigSnapshot =
    parseMcpConfigSnapshot(
      transport.request(epoch, "config.get", "{}", MCP_CONFIG_READ_TIMEOUT_MS),
      json,
    ) ?: error("Gateway returned an invalid configuration snapshot.")

  private fun publish(
    epoch: McpGatewayEpoch,
    update: (GatewayMcpConfigState) -> GatewayMcpConfigState,
  ) {
    if (!transport.isCurrent(epoch)) return
    synchronized(stateLock) {
      if (transport.isCurrent(epoch)) configState.value = update(configState.value)
    }
  }

  private fun publishFailure(
    intent: GatewayMcpMutationIntent?,
    message: String,
    epoch: McpGatewayEpoch? = null,
  ) {
    val update: (GatewayMcpConfigState) -> GatewayMcpConfigState = {
      it.copy(mutation = GatewayMcpMutationState.Failed(intent, message))
    }
    if (epoch == null) {
      synchronized(stateLock) { configState.value = update(configState.value) }
    } else {
      publish(epoch, update)
    }
  }

  private fun GatewayMcpMutationIntent.successMessage(): String =
    when (this) {
      is GatewayMcpMutationIntent.Add ->
        when (followUp) {
          McpConnectorFollowUp.OAuth ->
            "Added $serverName to configuration. Authorization is not complete; run `openclaw mcp login $serverName` in Terminal."
          McpConnectorFollowUp.Endpoint ->
            "Added $serverName to configuration. Review its endpoint and credentials before use."
          McpConnectorFollowUp.None -> "Added $serverName to configuration."
        }
      is GatewayMcpMutationIntent.SetEnabled ->
        if (enabled) "$serverName is enabled in configuration." else "$serverName is disabled in configuration."
      is GatewayMcpMutationIntent.Remove -> "Removed $serverName from configuration."
    }

  private companion object {
    const val MCP_CONFIG_READ_TIMEOUT_MS = 30_000L
    const val MCP_CONFIG_WRITE_TIMEOUT_MS = 60_000L
  }
}
