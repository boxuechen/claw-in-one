package ai.openclaw.app.chat

import ai.openclaw.app.gateway.SessionObserverDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

private val visibleChatMessageRoles = setOf("user", "assistant", "system", "custom")
internal const val CHAT_IMAGE_MAX_BASE64_CHARS = 300 * 1024

/** Keeps transcript rows limited to roles Android renders as user-visible chat. */
internal fun normalizeVisibleChatMessageRole(role: String?): String? =
  role
    ?.trim()
    ?.lowercase(Locale.US)
    ?.takeIf(visibleChatMessageRoles::contains)

/**
 * Chat transcript item as delivered by gateway chat history and live chat events.
 */
data class ChatMessage(
  val id: String,
  val role: String,
  val content: List<ChatMessageContent>,
  val timestampMs: Long?,
  val idempotencyKey: String? = null,
  /** Canonical transcript-tree identity supplied by chat.history. */
  val entryId: String? = null,
  val truncated: Boolean = false,
  val provenance: ChatMessageProvenance? = null,
  val transcriptMarker: ChatTranscriptMarker? = null,
  val senderLabel: String? = null,
) {
  internal val canReadFullMessage: Boolean
    get() = role == "assistant" && truncated && !entryId.isNullOrBlank()

  internal fun matchesFullRead(other: ChatMessage): Boolean = canReadFullMessage && other.canReadFullMessage && entryId == other.entryId && content == other.content
}

enum class ChatCommentaryPhase {
  Update,
  End,
}

/** Gateway 9.4 preamble/commentary. This is run activity, never a conversation message. */
data class ChatCommentarySegment(
  val runId: String?,
  val itemId: String,
  val text: String,
  val timestampMs: Long?,
  val sequence: Long? = null,
  val phase: ChatCommentaryPhase = ChatCommentaryPhase.End,
)

/** Ephemeral details for the one run represented by Chat's Working row. */
internal data class ChatRunActivity(
  val runId: String,
  val commentary: List<ChatCommentarySegment>,
) {
  val latestCommentary: String?
    get() = commentary.lastOrNull()?.text
}

/** Provisional final-answer text for one authoritative run; actions wait for canonical history. */
data class ChatAssistantAnswerDraft(
  val runId: String,
  val text: String,
)

internal sealed interface ChatFullMessageState {
  data object Loading : ChatFullMessageState

  data class Loaded(
    val content: List<ChatMessageContent>,
  ) : ChatFullMessageState

  data class Unavailable(
    val reason: ChatFullMessageUnavailable,
  ) : ChatFullMessageState

  data object Failed : ChatFullMessageState
}

internal enum class ChatFullMessageUnavailable {
  GatewayUpdate,
  Disconnected,
  NotFound,
  TooLarge,
}

data class ChatMessageProvenance(
  val kind: String,
  val sourceTool: String? = null,
)

data class ChatTranscriptMarker(
  val kind: String,
  val id: String? = null,
  val tokensBefore: Double? = null,
  val tokensAfter: Double? = null,
)

data class ChatTranscriptAnchorState(
  val sessionKey: String,
  val newestItemId: String?,
  val completedEndedAt: Long?,
  val completedNewestItemId: String?,
)

/** One inert content part rendered by Chat; images use bounded base64 or a managed Artifact id. */
data class ChatMessageContent(
  val type: String = "text",
  val text: String? = null,
  val mimeType: String? = null,
  val fileName: String? = null,
  val artifactId: String? = null,
  val base64: String? = null,
)

/**
 * Tool call placeholder shown while a gateway run is still streaming.
 */
data class ChatPendingToolCall(
  val toolCallId: String,
  val name: String,
  val args: kotlinx.serialization.json.JsonObject? = null,
  val startedAtMs: Long,
  val isError: Boolean? = null,
  val liveDiff: ChatDiffStat? = null,
)

data class ChatDiffStat(
  val added: Int,
  val removed: Int,
)

enum class ChatPlanStepStatus {
  Pending,
  InProgress,
  Completed,
}

data class ChatPlanStep(
  val step: String,
  val status: ChatPlanStepStatus,
)

data class ChatProgressCard(
  val revision: Int,
  val updatedAt: Long,
  val markdown: String?,
  val steps: List<ChatPlanStep>,
)

internal data class ChatProgressCardGetResult(
  val sessionKey: String?,
  val card: ChatProgressCard?,
)

/** Parses a complete gateway plan snapshot, including legacy string-only steps. */
internal fun parseChatPlanSteps(element: JsonElement?): List<ChatPlanStep> {
  val entries = element as? JsonArray ?: return emptyList()
  var hasInProgressStep = false
  return entries.mapNotNull { entry ->
    val parsed =
      when (entry) {
        is JsonObject -> {
          val step =
            (entry["step"] as? JsonPrimitive)
              ?.takeIf { it.isString }
              ?.content
              ?.trim()
              ?.takeIf { it.isNotEmpty() }
              ?: return@mapNotNull null
          val status =
            when ((entry["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
              "pending" -> ChatPlanStepStatus.Pending
              "in_progress" -> ChatPlanStepStatus.InProgress
              "completed" -> ChatPlanStepStatus.Completed
              else -> return@mapNotNull null
            }
          ChatPlanStep(step = step, status = status)
        }
        is JsonPrimitive -> {
          val step =
            entry
              .takeIf { it.isString }
              ?.content
              ?.trim()
              ?.takeIf { it.isNotEmpty() }
              ?: return@mapNotNull null
          ChatPlanStep(step = step, status = ChatPlanStepStatus.Pending)
        }
        else -> return@mapNotNull null
      }
    if (parsed.status == ChatPlanStepStatus.InProgress) {
      if (hasInProgressStep) return@mapNotNull null
      hasInProgressStep = true
    }
    parsed
  }
}

internal fun parseChatProgressCardGetResult(element: JsonElement): ChatProgressCardGetResult {
  val result = element as? JsonObject ?: error("Invalid progressCard.get response")
  if (!result.containsKey("card")) error("Invalid progressCard.get response")
  val rawCard = result["card"]
  if (rawCard == null || rawCard is JsonNull) {
    return ChatProgressCardGetResult(sessionKey = null, card = null)
  }
  val card = rawCard as? JsonObject ?: error("Invalid progressCard.get response")
  val sessionKey =
    (card["sessionKey"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: error("Invalid progress card session key")
  val revision =
    (card["revision"] as? JsonPrimitive)
      ?.takeUnless { it.isString }
      ?.content
      ?.toLongOrNull()
      ?.takeIf { it in 1..Int.MAX_VALUE }
      ?.toInt()
      ?: error("Invalid progress card revision")
  val updatedAt =
    (card["updatedAt"] as? JsonPrimitive)
      ?.takeUnless { it.isString }
      ?.content
      ?.toLongOrNull()
      ?: error("Invalid progress card update time")
  val markdown =
    if (card.containsKey("markdown")) {
      (card["markdown"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: error("Invalid progress card markdown")
    } else {
      null
    }?.takeIf { it.isNotBlank() }
  val steps = parseChatPlanSteps(card["steps"])
  val parsedCard =
    if (markdown == null && steps.isEmpty()) {
      null
    } else {
      ChatProgressCard(
        revision = revision,
        updatedAt = updatedAt,
        markdown = markdown,
        steps = steps,
      )
    }
  return ChatProgressCardGetResult(sessionKey = sessionKey, card = parsedCard)
}

/** Gateway-advertised thinking choice for the active provider/model pair. */
data class ChatThinkingLevelOption(
  val id: String,
  val label: String,
)

/** Thinking choices currently shown by chat, including whether the Gateway supplied them. */
data class ChatThinkingLevelSelection(
  val options: List<ChatThinkingLevelOption>,
  val isGatewayProvided: Boolean,
)

internal val defaultChatThinkingLevelSelection =
  ChatThinkingLevelSelection(
    options =
      listOf(
        ChatThinkingLevelOption(id = "off", label = "Off"),
        ChatThinkingLevelOption(id = "low", label = "Low"),
        ChatThinkingLevelOption(id = "medium", label = "Medium"),
        ChatThinkingLevelOption(id = "high", label = "High"),
      ),
    isGatewayProvided = false,
  )

internal data class ChatActiveRunPresentation(
  val count: Int = 0,
  val runId: String? = null,
  val clockKey: String? = null,
  val outputTokens: Long? = null,
)

/**
 * Stable session selector row; [key] is the gateway session key used in chat requests.
 */
data class ChatSessionEntry(
  val key: String,
  val updatedAtMs: Long?,
  val projectId: String? = null,
  val sessionId: String? = null,
  /** Gateway display fields, not an Android permission grant. The permission owner confirms writes. */
  val permissionMode: String? = null,
  val permissionModePending: Boolean = false,
  val ownerAgentId: String? = null,
  val classification: String? = null,
  val accountId: String? = null,
  val peerKind: String? = null,
  val isMain: Boolean? = null,
  val isBackground: Boolean? = null,
  val hasClassificationMetadata: Boolean =
    classification != null || accountId != null || peerKind != null || isMain != null || isBackground != null,
  val displayName: String? = null,
  val derivedTitle: String? = null,
  val label: String? = null,
  val color: String? = null,
  val hasColorMetadata: Boolean = color != null,
  val pinned: Boolean? = null,
  val archived: Boolean? = null,
  val unread: Boolean? = null,
  val lastReadAt: Long? = null,
  val markedUnreadAt: Long? = null,
  val hasMarkedUnreadMetadata: Boolean = markedUnreadAt != null,
  val agentStatus: ChatSessionAgentStatus? = null,
  val hasAgentStatusMetadata: Boolean = agentStatus != null,
  val observerDigest: SessionObserverDigest? = null,
  val hasObserverDigestMetadata: Boolean = observerDigest != null,
  val lastActivityAt: Long? = null,
  val totalTokens: Long? = null,
  val totalTokensFresh: Boolean? = null,
  val modelProvider: String? = null,
  val model: String? = null,
  val thinkingLevel: String? = null,
  val thinkingLevels: List<ChatThinkingLevelOption>? = null,
  val thinkingDefault: String? = null,
  val contextTokens: Long? = null,
  val hasContextUsageMetadata: Boolean = totalTokens != null || totalTokensFresh != null || contextTokens != null,
  val hasActiveRun: Boolean? = null,
  val activeRunIds: List<String>? = null,
  val hasActiveRunMetadata: Boolean = hasActiveRun != null || activeRunIds != null,
  val hasActiveRunIdsMetadata: Boolean = activeRunIds != null,
  val status: String? = null,
  val lastRunError: String? = null,
  val startedAt: Long? = null,
  val endedAt: Long? = null,
  val runtimeMs: Long? = null,
  val outputTokens: Long? = null,
  val hasRunMetadata: Boolean =
    status != null || startedAt != null || endedAt != null || runtimeMs != null || outputTokens != null,
)

data class ChatSessionAgentStatus(
  val note: String,
  val expiresAt: Long,
  val attention: String? = null,
)

/** Local fallback for server-side `sessions.list` search over cached entries. */
fun filterSessionEntries(
  sessions: List<ChatSessionEntry>,
  search: String,
): List<ChatSessionEntry> {
  val query = search.trim().lowercase()
  if (query.isEmpty()) return sessions
  return sessions.filter { session ->
    listOfNotNull(session.displayName, session.label, session.key)
      .any { it.lowercase().contains(query) }
  }
}

/**
 * Slash command metadata exposed by the gateway for text-surface chat clients.
 */
data class ChatCommandEntry(
  val name: String,
  val description: String,
  val category: String? = null,
  val textAliases: List<String> = emptyList(),
  val acceptsArgs: Boolean = false,
)

/**
 * Run still streaming on the gateway when a chat.history snapshot was captured;
 * [text] is the assistant text buffered so far (may be empty for runs without deltas).
 */
data class ChatInFlightRun(
  val runId: String,
  val text: String,
)

/**
 * Snapshot of one chat session, including optional thinking level selected on the gateway.
 */
data class ChatHistory(
  val sessionKey: String,
  val sessionId: String?,
  val thinkingLevel: String?,
  val messages: List<ChatMessage>,
  val commentary: List<ChatCommentarySegment> = emptyList(),
  val sessionInfo: ChatSessionEntry? = null,
  val inFlightRun: ChatInFlightRun? = null,
)

/**
 * User-selected attachment payload sent to the gateway as inline base64.
 */
data class OutgoingAttachment(
  val type: String,
  val mimeType: String,
  val fileName: String,
  val base64: String,
)
