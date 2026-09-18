package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewayClientInfo
import ai.openclaw.app.gateway.GatewayMethod
import ai.openclaw.app.gateway.GatewayNodeEventParams
import ai.openclaw.app.gateway.GatewaySession
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Best-effort Node liveness, scoped to a captured socket. Never owns connection or retry policy. */
internal class NodePresence(
  private val scope: CoroutineScope,
  private val captureConnection: () -> GatewaySession.RequestLease?,
  private val clientInfo: () -> GatewayClientInfo,
  private val platform: () -> String = NodePresenceAliveBeacon::androidPlatformMetadata,
  private val nowMs: () -> Long = System::currentTimeMillis,
  private val onUnhandled: (String) -> Unit = { reason ->
    Log.d("OpenClawNode", "node.presence.alive not handled: $reason")
  },
) {
  private data class Success(
    val connection: GatewaySession.RequestLease,
    val sentAtMs: Long,
  )

  private val sendMutex = Mutex()
  private var lastSuccess: Success? = null

  fun publish(
    trigger: NodePresenceAliveBeacon.Trigger,
    throttleRecentSuccess: Boolean = false,
  ) {
    val connection = captureConnection() ?: return
    val client = clientInfo()
    val platformLabel = platform()
    scope.launch {
      sendMutex.withLock {
        if (!connection.isCurrent()) return@withLock
        val sentAtMs = nowMs()
        val recent = lastSuccess?.takeIf { it.connection === connection }
        if (throttleRecentSuccess && NodePresenceAliveBeacon.shouldSkipRecentSuccess(sentAtMs, recent?.sentAtMs)) {
          return@withLock
        }
        val payload =
          NodePresenceAliveBeacon.makePayloadJson(
            trigger = trigger,
            sentAtMs = sentAtMs,
            displayName = client.displayName?.trim()?.takeIf(String::isNotEmpty) ?: "Android",
            version = client.version,
            platform = platformLabel,
            deviceFamily = client.deviceFamily,
            modelIdentifier = client.modelIdentifier,
          )
        val raw =
          try {
            connection.request(
              GatewayMethod.NodeEvent.rawValue,
              Json.encodeToString(GatewayNodeEventParams.serializer(), GatewayNodeEventParams(event = NodePresenceAliveBeacon.EVENT_NAME, payloadJson = payload)),
              timeoutMs = 8_000,
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            return@withLock
          }
        // Same send lane owns the success baseline; publication cannot adopt a replacement socket.
        connection.commitIfCurrent {
          val response = NodePresenceAliveBeacon.decodeResponse(raw)
          if (response?.handled == true) {
            lastSuccess = Success(connection, sentAtMs)
          } else {
            onUnhandled(NodePresenceAliveBeacon.sanitizeReasonForLog(response?.reason))
          }
        }
      }
    }
  }
}
