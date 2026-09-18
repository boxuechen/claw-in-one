package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val FULL_MESSAGE_TEXT_MAX_CHARS = 1_000_000

/** Captures and fences full-message reads against the existing selection and transcript owners. */
internal class ChatFullMessageReader(
  private val publicationLock: Any,
  private val sessionSelection: ChatSessionSelection,
  private val messages: StateFlow<List<ChatMessage>>,
  private val currentGatewayCatalogRevision: () -> Long,
  private val captureRequestLease: (ChatCacheScope?) -> GatewaySession.RequestLease?,
  private val gatewayAdvertisesMethod: (String) -> Boolean?,
  private val json: Json,
  private val historyCodec: ChatHistoryCodec,
) {
  val feature = ChatFullMessageSource(::prepare)

  fun prepare(
    owner: ChatComposerOwner,
    selectionGeneration: Long,
    catalogRevision: Long,
    message: ChatMessage,
  ): ChatFullMessageRead? {
    val snapshot =
      synchronized(publicationLock) {
        sessionSelection.captureAction(owner.sessionKey)?.takeIf {
          it.selectionGeneration == selectionGeneration &&
            isCurrentFullMessage(it, owner, catalogRevision, message)
        }
      } ?: return null
    // Capture outside the logical lock: hello publishes physical -> logical -> catalog.
    val lease = captureRequestLease(snapshot.gatewayScope)
    return synchronized(publicationLock) {
      if (!isCurrentFullMessage(snapshot, owner, catalogRevision, message)) return@synchronized null
      val unavailable =
        when {
          lease == null -> ChatFullMessageUnavailable.Disconnected
          gatewayAdvertisesMethod("chat.message.get") != true -> ChatFullMessageUnavailable.GatewayUpdate
          else -> null
        }
      Read(snapshot, owner, catalogRevision, message, lease, unavailable)
    }
  }

  private fun isCurrentFullMessage(
    snapshot: ChatSessionActionSnapshot,
    owner: ChatComposerOwner,
    catalogRevision: Long,
    message: ChatMessage,
  ): Boolean =
    sessionSelection.isCurrent(snapshot) &&
      sessionSelection.isCurrentComposerOwner(owner) &&
      catalogRevision == currentGatewayCatalogRevision() &&
      messages.value.any(message::matchesFullRead)

  /** The admitted read owns its outcome; nothing is published through a retained UI callback. */
  private inner class Read(
    private val snapshot: ChatSessionActionSnapshot,
    private val owner: ChatComposerOwner,
    private val catalogRevision: Long,
    private val message: ChatMessage,
    private val lease: GatewaySession.RequestLease?,
    unavailable: ChatFullMessageUnavailable?,
  ) : ChatFullMessageRead {
    private val result = MutableStateFlow<ChatFullMessageState>(unavailable?.let(ChatFullMessageState::Unavailable) ?: ChatFullMessageState.Loading)
    override val state: StateFlow<ChatFullMessageState> = result.asStateFlow()

    override suspend fun execute() {
      val capturedLease = lease ?: return
      if (result.value != ChatFullMessageState.Loading) return
      val caller = currentCoroutineContext()
      caller.ensureActive()
      val params =
        buildJsonObject {
          put("sessionKey", JsonPrimitive(snapshot.sessionKey))
          put("agentId", JsonPrimitive(snapshot.ownerAgentId))
          put("messageId", JsonPrimitive(message.entryId))
          put("maxChars", JsonPrimitive(FULL_MESSAGE_TEXT_MAX_CHARS))
        }.toString()
      val next =
        try {
          val response =
            capturedLease.request("chat.message.get", params) { enqueue ->
              // Selection retirement and dispatch are one decision, after any transport wait.
              synchronized(publicationLock) {
                if (!isCurrentFullMessage(snapshot, owner, catalogRevision, message)) {
                  throw GatewayRequestNotEnqueued("full message read retired")
                }
                caller.ensureActive()
                enqueue()
              }
            }
          parseFullMessage(response, message.entryId)
        } catch (err: CancellationException) {
          throw err
        } catch (_: Throwable) {
          ChatFullMessageState.Failed
        }
      capturedLease.commitIfCurrent {
        synchronized(publicationLock) {
          if (caller.isActive && isCurrentFullMessage(snapshot, owner, catalogRevision, message)) {
            result.value = next
          }
        }
      }
    }
  }

  private fun parseFullMessage(
    payload: String,
    entryId: String?,
  ): ChatFullMessageState {
    val root =
      runCatching { json.parseToJsonElement(payload).asObjectOrNull() }.getOrNull()
        ?: return ChatFullMessageState.Failed
    if (root["ok"] == JsonPrimitive(false)) {
      // Only protocol-defined reasons are terminal; malformed replies remain retryable.
      val reason =
        when (root["unavailableReason"].asJsonStringOrNull()) {
          "not_found", "not_visible" -> ChatFullMessageUnavailable.NotFound
          "oversized" -> ChatFullMessageUnavailable.TooLarge
          else -> return ChatFullMessageState.Failed
        }
      return ChatFullMessageState.Unavailable(reason)
    }
    val obj = root["message"].asObjectOrNull()
    if (root["ok"] != JsonPrimitive(true) ||
      obj == null ||
      obj["role"].asJsonStringOrNull() != "assistant" ||
      obj["__openclaw"].asObjectOrNull()?.get("id").asJsonStringOrNull() != entryId
    ) {
      return ChatFullMessageState.Failed
    }
    val parsed = historyCodec.parseMessage(obj, FULL_MESSAGE_TEXT_MAX_CHARS) ?: return ChatFullMessageState.Failed
    // The canonical get projection is bounded too; ok:true does not promise complete text.
    if (parsed.truncated) return ChatFullMessageState.Unavailable(ChatFullMessageUnavailable.TooLarge)
    if (parsed.content.none { it.type == "text" && !it.text.isNullOrBlank() }) {
      return ChatFullMessageState.Failed
    }
    return ChatFullMessageState.Loaded(parsed.content)
  }
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asJsonStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
