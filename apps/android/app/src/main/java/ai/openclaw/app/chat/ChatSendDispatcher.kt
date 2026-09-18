package ai.openclaw.app.chat

import ai.openclaw.app.gateway.ChatSendAck
import ai.openclaw.app.gateway.GatewayRequestDefinitiveFailure
import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.gateway.parseChatSendAck
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Immutable dispatch input. Selection, permissions, settings, and durable claims precede it. */
internal class ChatSendRequest(
  val owner: ChatComposerOwner,
  // The wire key may be a durably pinned alias; Stop still uses the original admission owner.
  val sessionKey: String,
  val text: String,
  val thinking: String,
  val idempotencyKey: String,
  attachments: List<OutgoingAttachment>,
) {
  val attachments = attachments.toList()
}

/** Transport evidence only: a response, including an error, is not proof of durable delivery. */
internal sealed interface ChatSendResult {
  data class Response(
    val ack: ChatSendAck,
  ) : ChatSendResult

  /** The captured socket provably did not enqueue this frame. */
  data class NotEnqueued(
    val message: String?,
  ) : ChatSendResult

  /** A Gateway error may follow dispatch; never interpret it as permission to resend. */
  data class Rejected(
    val message: String?,
  ) : ChatSendResult

  /** The transport cannot tell whether this frame was accepted. */
  data object OutcomeUnknown : ChatSendResult

  /** An unclassified failure also cannot establish non-delivery. */
  data class Failed(
    val message: String?,
  ) : ChatSendResult
}

/** The single Chat wire path. No scope recapture, persistence, UI publication, or retry. */
internal class ChatSendDispatcher(
  private val json: Json,
  private val enqueueWithStop: (ChatComposerOwner, Long, () -> Unit) -> Boolean,
) {
  suspend fun send(
    request: ChatSendRequest,
    lease: GatewaySession.RequestLease?,
    stopRevision: Long,
  ): ChatSendResult =
    try {
      val connection = lease ?: throw GatewayRequestNotEnqueued("Chat connection unavailable")
      val response =
        connection.request("chat.send", encode(request), withEnqueue = { enqueue ->
          if (!enqueueWithStop(request.owner, stopRevision, enqueue)) {
            throw GatewayRequestNotEnqueued(OUTBOX_CHAT_STOPPED_ERROR)
          }
        })
      ChatSendResult.Response(parseChatSendAck(json, response))
    } catch (error: CancellationException) {
      // The delivery owner retains its durable claim; teardown is not rejection evidence.
      throw error
    } catch (error: GatewayRequestNotEnqueued) {
      ChatSendResult.NotEnqueued(error.message)
    } catch (error: GatewayRequestDefinitiveFailure) {
      ChatSendResult.Rejected(error.message)
    } catch (_: GatewayRequestOutcomeUnknown) {
      ChatSendResult.OutcomeUnknown
    } catch (error: Throwable) {
      ChatSendResult.Failed(error.message)
    }

  private fun encode(request: ChatSendRequest): String =
    buildJsonObject {
      put("sessionKey", JsonPrimitive(request.sessionKey))
      put("agentId", JsonPrimitive(request.owner.agentId))
      put("message", JsonPrimitive(request.text))
      put("thinking", JsonPrimitive(request.thinking))
      // This is not the socket timeout: a payload timeoutMs overrides the server-side run
      // expiry. Leave it absent so long Agent turns use the Gateway's configured default.
      put("idempotencyKey", JsonPrimitive(request.idempotencyKey))
      if (request.attachments.isNotEmpty()) {
        put(
          "attachments",
          JsonArray(
            request.attachments.map { attachment ->
              buildJsonObject {
                put("type", JsonPrimitive(attachment.type))
                put("mimeType", JsonPrimitive(attachment.mimeType))
                put("fileName", JsonPrimitive(attachment.fileName))
                put("content", JsonPrimitive(attachment.base64))
              }
            },
          ),
        )
      }
    }.toString()
}
