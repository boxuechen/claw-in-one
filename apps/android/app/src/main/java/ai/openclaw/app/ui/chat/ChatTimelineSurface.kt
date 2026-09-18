package ai.openclaw.app.ui.chat

import ai.openclaw.app.approval.ApprovalActions
import ai.openclaw.app.chat.ChatAssistantAnswerDraft
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatFullMessageSource
import ai.openclaw.app.chat.ChatHistoryFeature
import ai.openclaw.app.chat.ChatMessage
import ai.openclaw.app.chat.ChatMessageContent
import ai.openclaw.app.chat.ChatOutboxItem
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.chat.ChatQuestionDraft
import ai.openclaw.app.chat.ChatQuestionPrompt
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.chat.ChatTranscriptAnchorState
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.approval.ApprovalReview
import ai.openclaw.app.ui.design.ClawLoadingState
import ai.openclaw.app.ui.design.ClawTheme
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatMessageList(
  presentationState: ChatPresentationState,
  blankContent: ChatBlankContent,
  onProjectStarter: (ProjectStarter) -> Unit,
  sessionKey: String,
  fullMessageOwner: ChatComposerOwner,
  selectionGeneration: Long,
  gatewayCatalogRevision: Long,
  fullMessageSource: ChatFullMessageSource?,
  session: ChatSessionEntry?,
  messages: List<ChatMessage>,
  transcriptAnchor: ChatTranscriptAnchorState?,
  historyLoading: Boolean,
  activeRunCount: Int,
  activeRunId: String?,
  activeRunClockKey: String?,
  activeRunOutputTokens: Long?,
  pendingToolCalls: List<ChatPendingToolCall>,
  questions: List<ChatQuestionPrompt>,
  approvals: List<ApprovalReview>,
  timelineSupplement: ChatTimelineSupplement,
  approvalActions: ApprovalActions,
  onApprovalDetails: (String) -> Unit,
  focusApprovalId: String?,
  onApprovalFocusConsumed: () -> Unit,
  answerDraft: ChatAssistantAnswerDraft?,
  healthOk: Boolean,
  gatewayOffline: Boolean,
  outboxItems: List<ChatOutboxItem>,
  recoveryOutboxItems: List<ChatOutboxItem>,
  onRetryOutbox: (String) -> Unit,
  onDeleteOutbox: (String) -> Unit,
  onResolveQuestion: (ChatQuestionPrompt, Map<String, List<String>>) -> Unit,
  onQuestionDraftChanged: (ChatQuestionPrompt, (ChatQuestionDraft) -> ChatQuestionDraft) -> Unit,
  onSkipQuestion: (ChatQuestionPrompt) -> Unit,
  onReplyMessage: (String) -> Unit,
  onAttentionAction: (ChatAttentionAction) -> Unit,
  onOpenResult: (ChatResultState) -> Unit,
  history: ChatHistoryFeature?,
  modifier: Modifier = Modifier,
) {
  val baseTimeline =
    remember(presentationState, messages, activeRunCount, pendingToolCalls, questions, answerDraft, outboxItems, recoveryOutboxItems, approvals, timelineSupplement) {
      buildChatTimeline(
        messages = if (presentationState.hasConversation) messages else emptyList(),
        pendingRunCount = if (presentationState.hasConversation) activeRunCount else 0,
        pendingToolCalls = if (presentationState.hasConversation) pendingToolCalls else emptyList(),
        answerDraft = answerDraft.takeIf { presentationState.hasConversation },
        outboxItems = if (presentationState.hasConversation) outboxItems else emptyList(),
        recoveryOutboxItems = if (presentationState.hasConversation) recoveryOutboxItems else emptyList(),
        questions = if (presentationState.hasConversation) questions else emptyList(),
        approvals = if (presentationState.hasConversation) approvals else emptyList(),
        supplement = timelineSupplement,
      )
    }
  val indicatorVisible = activeRunCount > 0
  val workingRunTracker = remember(sessionKey) { ChatWorkingRunTracker(sessionKey) }
  val workingRun =
    workingRunTracker.resolve(
      indicatorVisible = indicatorVisible,
      clockKey = activeRunClockKey,
      authoritativeRunId = activeRunId,
      nowElapsedMs = SystemClock.elapsedRealtime(),
      outputTokens = activeRunOutputTokens,
    )
  val turnRecapResolver = remember { TurnRecapResolver() }
  val turnRecap =
    turnRecapResolver.resolve(
      sessionKey = sessionKey,
      indicatorVisible = indicatorVisible,
      row = session,
      transcript =
        TurnRecapTranscriptState(
          sessionKey = transcriptAnchor?.sessionKey,
          newestItemId = transcriptAnchor?.newestItemId,
          completedEndedAt = transcriptAnchor?.completedEndedAt,
          completedNewestItemId = transcriptAnchor?.completedNewestItemId,
        ),
    )
  val timeline = remember(baseTimeline, turnRecap) { baseTimeline.withTurnRecap(turnRecap) }
  val readerScroll =
    rememberChatReaderScrollController(
      sessionKey = sessionKey,
      timeline = timeline,
      historyLoading = historyLoading,
    )
  DisposableEffect(sessionKey, turnRecapResolver) {
    onDispose { turnRecapResolver.abandonActiveWatch(sessionKey) }
  }
  LaunchedEffect(focusApprovalId, historyLoading, timeline.items) {
    if (focusApprovalId != null && !historyLoading) {
      val index = timeline.items.indexOfFirst { it is ChatTimelineItem.Approval && it.review.id == focusApprovalId }
      if (index >= 0) {
        readerScroll.listState.scrollToItem(index)
        onApprovalFocusConsumed()
      }
    }
  }

  CompositionLocalProvider(LocalChatReaderNavigation provides readerScroll.onManualNavigation) {
    ChatMessageDisclosure(
      messages = messages,
      owner = fullMessageOwner,
      selectionGeneration = selectionGeneration,
      catalogRevision = gatewayCatalogRevision,
      source = fullMessageSource,
    ) { visibleContent, disclosure ->
      Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        LazyColumn(
          modifier =
            Modifier
              .fillMaxSize()
              .nestedScroll(readerScroll.nestedScrollConnection)
              .padding(bottom = chatReaderListBottomInset(readerScroll.showJumpToLatest)),
          state = readerScroll.listState,
          reverseLayout = true,
          verticalArrangement = Arrangement.spacedBy(10.dp),
          contentPadding = PaddingValues(top = 10.dp, bottom = 4.dp),
        ) {
          itemsIndexed(items = timeline.items, key = { _, item -> chatTimelineItemKey(item) }) { _, item ->
            when (item) {
              is ChatTimelineItem.Approval -> ChatApprovalCard(item.review, !gatewayOffline, approvalActions, onApprovalDetails)
              is ChatTimelineItem.ConversationMessage ->
                ChatBubble(
                  messageId = item.message.id,
                  role = item.message.role,
                  live = false,
                  content = visibleContent(item.message),
                  timestampMs = item.message.timestampMs,
                  onReplyMessage = onReplyMessage,
                  imageResolverReady = healthOk,
                  loadImage = { artifactId -> history?.loadImage?.invoke(artifactId) },
                  senderLabel = item.message.senderLabel,
                  showQuickActions = false,
                  disclosure = { disclosure(item.message) },
                )
              is ChatTimelineItem.AssistantAnswer ->
                ChatBubble(
                  messageId = item.message.id,
                  role = item.message.role,
                  live = false,
                  content = visibleContent(item.message),
                  timestampMs = item.message.timestampMs,
                  onReplyMessage = onReplyMessage,
                  imageResolverReady = healthOk,
                  loadImage = { artifactId -> history?.loadImage?.invoke(artifactId) },
                  senderLabel = item.message.senderLabel,
                  showQuickActions = true,
                  disclosure = { disclosure(item.message) },
                )
              is ChatTimelineItem.OutboxCommand ->
                ChatOutboxBubble(
                  item = item.item,
                  onRetry = { onRetryOutbox(item.item.id) },
                  onDelete = { onDeleteOutbox(item.item.id) },
                )
              is ChatTimelineItem.RecoveryOutboxCommand ->
                ChatOutboxBubble(
                  item = item.item,
                  retryEnabled = false,
                  onRetry = { onRetryOutbox(item.item.id) },
                  onDelete = { onDeleteOutbox(item.item.id) },
                )
              is ChatTimelineItem.OutboxRecoveryHeader ->
                ChatNotice(
                  title = nativeString("Messages to recover"),
                  body =
                    nativeString(
                      "\${item.count} message(s) need recovery. Re-enter anything you want to keep, then delete these rows.",
                      item.count,
                    ),
                )
              is ChatTimelineItem.QuestionPrompt ->
                ChatQuestionCard(prompt = item.prompt, onDraftChanged = onQuestionDraftChanged, onSubmit = onResolveQuestion, onSkip = onSkipQuestion)
              is ChatTimelineItem.Attention -> ChatAttentionRow(item.state, onAction = onAttentionAction)
              is ChatTimelineItem.CurrentWork -> {
                val run = workingRun
                if (run != null) ChatCurrentWorkRow(state = item.state, run = run)
              }
              is ChatTimelineItem.Result -> ChatResultRow(item.state, onOpen = { onOpenResult(item.state) })
              is ChatTimelineItem.TurnRecapSummary -> ChatTurnRecapRow(item.recap)
              is ChatTimelineItem.SystemNotice -> ChatSystemNoticeRow(item)
              is ChatTimelineItem.SystemDivider -> ChatSystemDividerRow(item)
              is ChatTimelineItem.AssistantAnswerDraft ->
                ChatBubble(
                  messageId = null,
                  role = "assistant",
                  live = true,
                  content = listOf(ChatMessageContent(text = item.draft.text)),
                  timestampMs = null,
                  onReplyMessage = onReplyMessage,
                  imageResolverReady = healthOk,
                  loadImage = { artifactId -> history?.loadImage?.invoke(artifactId) },
                  showQuickActions = false,
                )
            }
          }
        }

        if (timeline.items.isEmpty()) {
          if (showChatLoadingPlaceholder(historyLoading = historyLoading, healthOk = healthOk, gatewayOffline = gatewayOffline)) {
            ClawLoadingState(title = nativeString("Loading thread"), modifier = Modifier.align(Alignment.Center))
          } else if (blankContent is ChatBlankContent.ProjectBootstrap) {
            ProjectBootstrapSurface(
              starters = blankContent.starters,
              enabled = blankContent.enabled,
              onStart = onProjectStarter,
              modifier = Modifier.align(Alignment.Center),
            )
          }
        }

        if (readerScroll.showJumpToLatest) {
          // Compact icon-only affordance; the 36dp circle sits inside an explicit
          // 48dp semantic target so accessibility does not depend on Material defaults.
          val jumpDescription = nativeString("Jump to latest")
          Box(
            modifier =
              Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .size(ClawTheme.sizes.minimumTouchTarget)
                .clip(CircleShape)
                .clickable(onClick = readerScroll.jumpToLatest)
                .clearAndSetSemantics {
                  contentDescription = jumpDescription
                  role = Role.Button
                  onClick {
                    readerScroll.jumpToLatest()
                    true
                  }
                },
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              modifier = Modifier.size(36.dp),
              shape = CircleShape,
              color = ClawTheme.colors.surfaceRaised,
              contentColor = ClawTheme.colors.text,
              shadowElevation = 6.dp,
              border = BorderStroke(1.dp, ClawTheme.colors.border),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.ArrowDownward,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

internal data class ChatWorkingRun(
  val clockKey: String,
  val observedAtElapsedMs: Long,
  val authoritativeRunId: String?,
  val outputTokens: Long?,
)

internal class ChatWorkingRunTracker(
  private val sessionKey: String,
) {
  private var current: ChatWorkingRun? = null

  fun resolve(
    indicatorVisible: Boolean,
    clockKey: String?,
    authoritativeRunId: String?,
    nowElapsedMs: Long,
    outputTokens: Long?,
  ): ChatWorkingRun? {
    if (!indicatorVisible) {
      current = null
      return null
    }
    val resolvedClockKey = clockKey ?: "$sessionKey:active"
    val previous = current
    if (previous == null || previous.clockKey != resolvedClockKey) {
      return ChatWorkingRun(
        clockKey = resolvedClockKey,
        observedAtElapsedMs = nowElapsedMs,
        authoritativeRunId = authoritativeRunId,
        outputTokens = outputTokens,
      ).also { current = it }
    }
    if (previous.authoritativeRunId != authoritativeRunId || previous.outputTokens != outputTokens) {
      current =
        previous.copy(
          authoritativeRunId = authoritativeRunId,
          outputTokens = outputTokens,
        )
    }
    return current
  }
}

internal fun showChatLoadingPlaceholder(
  historyLoading: Boolean,
  healthOk: Boolean,
  gatewayOffline: Boolean,
): Boolean = historyLoading && !healthOk && !gatewayOffline
