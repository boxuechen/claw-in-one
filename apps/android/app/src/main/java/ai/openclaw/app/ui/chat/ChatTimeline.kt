package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatAssistantAnswerDraft
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatOutboxItem
import ai.openclaw.app.chat.ChatOutboxStatus
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.chat.ChatQuestionPrompt
import ai.openclaw.app.chat.OUTBOX_OWNER_CHANGED_ERROR
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.resolveAgentIdFromMainSessionKey
import ai.openclaw.app.ui.approval.ApprovalReview

internal sealed class ChatTimelineItem {
  data class Approval(
    val review: ApprovalReview,
  ) : ChatTimelineItem()

  sealed class TranscriptMessage : ChatTimelineItem() {
    abstract val message: ChatMessage
  }

  data class ConversationMessage(
    override val message: ChatMessage,
  ) : TranscriptMessage()

  data class AssistantAnswer(
    override val message: ChatMessage,
  ) : TranscriptMessage()

  /** Durable queued/failed offline command shown below the transcript until acked or deleted. */
  data class OutboxCommand(
    val item: ChatOutboxItem,
  ) : ChatTimelineItem()

  /** Gateway-level recovery row that cannot be placed in the visible owner/session. */
  data class RecoveryOutboxCommand(
    val item: ChatOutboxItem,
  ) : ChatTimelineItem()

  data class OutboxRecoveryHeader(
    val count: Int,
  ) : ChatTimelineItem()

  data class AssistantAnswerDraft(
    val draft: ChatAssistantAnswerDraft,
  ) : ChatTimelineItem()

  data class QuestionPrompt(
    val prompt: ChatQuestionPrompt,
  ) : ChatTimelineItem()

  data class Attention(
    val state: ChatAttentionState,
  ) : ChatTimelineItem()

  data class CurrentWork(
    val state: ChatCurrentWorkUiState,
  ) : ChatTimelineItem()

  data class Result(
    val state: ChatResultState,
  ) : ChatTimelineItem()

  data class TurnRecapSummary(
    val recap: TurnRecap,
  ) : ChatTimelineItem()

  data class SystemNotice(
    val key: String,
    val label: String,
    val body: String,
  ) : ChatTimelineItem()

  data class SystemDivider(
    val key: String,
    val kind: SystemDividerKind,
    val label: String,
    val metric: String? = null,
    val secondary: String? = null,
  ) : ChatTimelineItem()
}

internal enum class SystemDividerKind {
  Compaction,
  Reset,
}

internal data class ChatTimeline(
  val items: List<ChatTimelineItem>,
  val readAnchorIndex: Int?,
  val latestContentIndex: Int?,
  val latestUserMessageId: String?,
  val latestUserMessageVersion: String?,
  val latestContentVersion: String,
)

internal fun buildChatTimeline(
  messages: List<ChatMessage>,
  pendingRunCount: Int,
  pendingToolCalls: List<ChatPendingToolCall>,
  answerDraft: ChatAssistantAnswerDraft?,
  outboxItems: List<ChatOutboxItem> = emptyList(),
  recoveryOutboxItems: List<ChatOutboxItem> = emptyList(),
  questions: List<ChatQuestionPrompt> = emptyList(),
  approvals: List<ApprovalReview> = emptyList(),
  supplement: ChatTimelineSupplement = ChatTimelineSupplement(),
): ChatTimeline {
  val draft = answerDraft?.takeIf { it.text.isNotBlank() }
  val items =
    buildList {
      // reverseLayout: index 0 renders bottom-most; queued commands are the newest user input.
      questions.asReversed().forEach { prompt -> add(ChatTimelineItem.QuestionPrompt(prompt)) }
      supplement.attentions.asReversed().forEach { attention -> add(ChatTimelineItem.Attention(attention)) }
      outboxItems.asReversed().forEach { item -> add(ChatTimelineItem.OutboxCommand(item)) }
      recoveryOutboxItems.asReversed().forEach { item -> add(ChatTimelineItem.RecoveryOutboxCommand(item)) }
      if (recoveryOutboxItems.isNotEmpty()) add(ChatTimelineItem.OutboxRecoveryHeader(recoveryOutboxItems.size))
      supplement.results.asReversed().forEach { result -> add(ChatTimelineItem.Result(result)) }
      if (draft != null) add(ChatTimelineItem.AssistantAnswerDraft(draft))
      // Canonical snapshots have no tool-call anchor. Use the owning Chat's current-work area,
      // visually before waiting tools, never attach by a guessed tool name or message text.
      approvals.asReversed().forEach { add(ChatTimelineItem.Approval(it)) }
      supplement.currentWork?.let { add(ChatTimelineItem.CurrentWork(it)) }
      for (index in messages.indices.reversed()) {
        classifyTranscriptMessage(messages[index], index)?.let(::add)
      }
    }
  if (items.isEmpty()) {
    return ChatTimeline(
      items = items,
      readAnchorIndex = null,
      latestContentIndex = null,
      latestUserMessageId = null,
      latestUserMessageVersion = null,
      latestContentVersion = "",
    )
  }

  val latestUserMessage =
    items.firstNotNullOfOrNull { item ->
      val message = (item as? ChatTimelineItem.TranscriptMessage)?.message ?: return@firstNotNullOfOrNull null
      message.takeIf { it.role.trim().equals("user", ignoreCase = true) }
    }
  val latestUserIndex =
    items.indexOfFirst { item ->
      item is ChatTimelineItem.TranscriptMessage &&
        item.message.id == latestUserMessage?.id
    }
  val latestContentIndex = 0
  // In reverseLayout, index 0 is bottom-most. Keep the latest prompt as a stable
  // reader anchor even after streaming rows collapse into a finished reply.
  val readAnchorIndex = latestUserIndex.takeIf { it >= 0 } ?: latestContentIndex

  return ChatTimeline(
    items = items,
    readAnchorIndex = readAnchorIndex,
    latestContentIndex = latestContentIndex,
    latestUserMessageId = latestUserMessage?.id,
    latestUserMessageVersion = latestUserMessage?.let(::stableMessageVersion),
    latestContentVersion =
      latestContentVersion(
        messages,
        pendingRunCount,
        pendingToolCalls,
        draft?.text,
        outboxItems + recoveryOutboxItems,
        questions,
      ) +
        approvals.joinToString(prefix = ":approvals=") { "${it.id}:${it.hashCode()}" } +
        ":supplement=${supplement.hashCode()}",
  )
}

/**
 * Outbox rows for the visible session owner. Rows enqueued under the "main" alias still belong to the
 * canonical main session once the gateway hello rewrites the current key. Rows whose user turn
 * is already visible as a message (optimistic while a live run owns it, or the canonical history
 * copy right before the row retires) are hidden so one send never renders as two bubbles. Migrated
 * ownerless and unreachable legacy-main rows are excluded here and rendered only in the
 * gateway-level recovery section.
 */
internal fun outboxItemsForSession(
  items: List<ChatOutboxItem>,
  sessionKey: String,
  mainSessionKey: String,
  ownerAgentId: String,
  messages: List<ChatMessage> = emptyList(),
): List<ChatOutboxItem> {
  val mainKey = mainSessionKey.trim().ifEmpty { "main" }
  val current = sessionKey.trim().let { if (it == "main") mainKey else it }
  val visibleUserKeys =
    messages
      .mapNotNull { message -> message.idempotencyKey?.trim()?.takeIf { it.isNotEmpty() } }
      .toSet()
  return items.filter { item ->
    val itemKey = item.sessionKey.let { if (it == "main") mainKey else it }
    val ownerMatches = item.ownerAgentId == ownerAgentId
    ownerMatches &&
      itemKey == current &&
      "${item.id}:user" !in visibleUserKeys &&
      !isRecoveryOutboxItem(item)
  }
}

/** Rows with missing or internally contradictory ownership still need neutral controls. */
internal fun outboxItemsForRecovery(items: List<ChatOutboxItem>): List<ChatOutboxItem> = items.filter(::isRecoveryOutboxItem)

private fun isRecoveryOutboxItem(item: ChatOutboxItem): Boolean {
  val keyOwner = resolveAgentIdFromMainSessionKey(item.sessionKey)
  val parkedMainAlias =
    item.sessionKey.trim() == "main" &&
      item.status == ChatOutboxStatus.Failed &&
      item.lastError == OUTBOX_OWNER_CHANGED_ERROR
  return item.ownerAgentId == null ||
    (keyOwner != null && keyOwner != item.ownerAgentId) ||
    parkedMainAlias
}

private fun stableMessageVersion(message: ChatMessage): String {
  val role = message.role.trim().lowercase()
  val idempotencyKey = message.idempotencyKey?.trim().orEmpty()
  if (idempotencyKey.isNotEmpty()) return "$role:idempotency:$idempotencyKey"

  return buildString {
    append(role)
    append(':')
    append(message.timestampMs ?: "")
    message.content.forEach { content ->
      append(':')
      append(content.type)
      append('=')
      append(content.text?.hashCode() ?: 0)
      append(',')
      append(content.mimeType.orEmpty())
      append(',')
      append(content.fileName.orEmpty())
      append(',')
      append(content.base64?.length ?: 0)
    }
  }
}

internal fun ChatTimeline.containsUserMessageVersion(version: String): Boolean =
  items.any { item ->
    val message = (item as? ChatTimelineItem.TranscriptMessage)?.message ?: return@any false
    message.role.trim().equals("user", ignoreCase = true) && stableMessageVersion(message) == version
  }

internal fun ChatTimeline.withTurnRecap(recap: TurnRecap?): ChatTimeline {
  if (recap == null) return this
  // reverseLayout makes index 0 the newest visual edge. The recap replaces the terminal
  // thinking slot there, while shifting the saved user-message anchor to the same row.
  return copy(
    items = listOf(ChatTimelineItem.TurnRecapSummary(recap)) + items,
    readAnchorIndex = readAnchorIndex?.plus(1),
    latestContentIndex = 0,
    latestContentVersion = "$latestContentVersion:recap=${recap.runtimeMs}:${recap.outputTokens ?: ""}",
  )
}

// Reader restoration only needs to detect changes at the live edge. Avoid hashing
// the full transcript whenever a streamed response updates.
private fun latestContentVersion(
  messages: List<ChatMessage>,
  pendingRunCount: Int,
  pendingToolCalls: List<ChatPendingToolCall>,
  stream: String?,
  outboxItems: List<ChatOutboxItem> = emptyList(),
  questions: List<ChatQuestionPrompt> = emptyList(),
): String {
  val latest = messages.lastOrNull()
  return buildString {
    append(messages.size)
    append(':')
    append(latest?.id.orEmpty())
    append(':')
    append(latest?.role.orEmpty())
    append(':')
    append(latest?.timestampMs ?: "")
    latest?.content?.forEach { content ->
      append(':')
      append(content.type)
      append('=')
      append(content.text?.hashCode() ?: 0)
      append(',')
      append(content.mimeType.orEmpty())
      append(',')
      append(content.fileName.orEmpty())
      append(',')
      append(content.base64?.length ?: 0)
    }
    append(":runs=")
    append(pendingRunCount)
    append(":tools=")
    pendingToolCalls.forEach { call ->
      append(call.toolCallId)
      append(',')
      append(call.name)
      append(',')
      append(call.isError)
      append(',')
      append(call.liveDiff)
      append(';')
    }
    append(":stream=")
    append(stream?.hashCode() ?: 0)
    append(":outbox=")
    outboxItems.forEach { item ->
      append(item.id)
      append(',')
      append(item.status)
      append(';')
    }
    append(":questions=")
    questions.forEach { prompt ->
      append(prompt.record.id)
      append(',')
      append(prompt.status())
      append(',')
      append(prompt.submitting)
      append(',')
      append(prompt.skipping)
      append(',')
      append(prompt.errorText?.hashCode() ?: 0)
      append(',')
      append(prompt.record.answers.hashCode())
      append(';')
    }
  }
}

internal fun chatTimelineItemKey(item: ChatTimelineItem): String =
  when (item) {
    is ChatTimelineItem.Approval -> "approval:${item.review.id}"
    is ChatTimelineItem.TranscriptMessage -> "message:${item.message.id}"
    is ChatTimelineItem.OutboxCommand -> "outbox:${item.item.id}"
    is ChatTimelineItem.RecoveryOutboxCommand -> "outbox-recovery:${item.item.id}"
    is ChatTimelineItem.OutboxRecoveryHeader -> "outbox-recovery-header"
    is ChatTimelineItem.QuestionPrompt -> "question:${item.prompt.record.id}"
    is ChatTimelineItem.Attention -> "attention:${item.state.key}"
    is ChatTimelineItem.CurrentWork -> "current-work"
    is ChatTimelineItem.Result -> "result:${item.state.key}"
    is ChatTimelineItem.TurnRecapSummary -> "turn-recap"
    is ChatTimelineItem.SystemNotice -> item.key
    is ChatTimelineItem.SystemDivider -> item.key
    is ChatTimelineItem.AssistantAnswerDraft -> "answer-draft:${item.draft.runId}"
  }

private fun classifyTranscriptMessage(
  message: ChatMessage,
  index: Int,
): ChatTimelineItem? {
  message.transcriptMarker?.let { marker ->
    val keySuffix = marker.id ?: "${message.timestampMs ?: "missing"}:$index"
    return when (marker.kind) {
      "compaction" -> {
        val before = marker.tokensBefore
        val after = marker.tokensAfter
        val saved =
          if (before != null && before.isFinite() && after != null && after.isFinite() && before > after) {
            (before - after).toLong()
          } else {
            null
          }
        ChatTimelineItem.SystemDivider(
          key = "divider:compaction:$keySuffix",
          kind = SystemDividerKind.Compaction,
          label = nativeString("Compacted history"),
          metric = saved?.let { nativeString("saved \$count tokens", formatCompactTokenCount(it)) },
        )
      }
      "reset" ->
        ChatTimelineItem.SystemDivider(
          key = "divider:reset:$keySuffix",
          kind = SystemDividerKind.Reset,
          label = nativeString("Session reset"),
          secondary = nativeString("The earlier conversation was cleared."),
        )
      else -> null
    }
  }

  val provenance = message.provenance
  if (message.role == "user" && provenance?.kind == "internal_system") {
    val rawBody = chatMessagePlainText(message.content).removePrefix("[System] ")
    val label: String
    val body: String
    when (provenance.sourceTool) {
      "main_session_restart_recovery" -> {
        label = nativeString("System · restart recovery")
        body = nativeString("Turn interrupted by a gateway restart — asked the agent to resume and finish the response.")
      }
      "restart-sentinel" -> {
        label = nativeString("System · gateway restarted")
        body = rawBody
      }
      else -> {
        label = nativeString("System")
        body = rawBody
      }
    }
    if (body.isBlank()) return null
    val keySuffix = message.entryId ?: message.idempotencyKey ?: "${message.timestampMs ?: "missing"}:$index"
    return ChatTimelineItem.SystemNotice(
      key = "system-notice:$keySuffix",
      label = label,
      body = body,
    )
  }

  // LazyColumn spacing applies even when a composable emits no layout. Admit only
  // transcript entries that can produce a bubble so protocol-only activity cannot
  // become invisible rows between a user prompt and its canonical answer.
  if (projectChatBubbleContent(message.content).parts.isEmpty()) return null

  return if (message.role.trim().equals("assistant", ignoreCase = true)) {
    ChatTimelineItem.AssistantAnswer(message)
  } else {
    ChatTimelineItem.ConversationMessage(message)
  }
}
