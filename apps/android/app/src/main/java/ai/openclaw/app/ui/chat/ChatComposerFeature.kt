package ai.openclaw.app.ui.chat

import ai.openclaw.app.ChatDraft
import ai.openclaw.app.ChatShareDraft
import ai.openclaw.app.PendingAssistantAutoSend
import ai.openclaw.app.chat.ChatComposerOwner
import kotlinx.coroutines.flow.StateFlow

/** ViewModel-lifetime composer state, split by responsibility rather than exposed as a ViewModel. */
internal class ChatComposerFeature(
  val state: ChatComposerStateStore,
  val drafts: ChatComposerDraftFeature,
  val shares: ChatComposerShareFeature,
  val autoSend: ChatComposerAutoSendFeature,
  val owners: ChatComposerOwnerFeature,
  val delivery: ChatComposerDeliveryFeature,
  val attachments: ChatComposerAttachmentFeature,
)

internal class ChatComposerDraftFeature(
  val pending: StateFlow<ChatDraft?>,
  private val consumeAction: (expected: ChatDraft, owner: ChatComposerOwner, mainSessionKey: String) -> ChatDraft?,
  private val setAction: (ChatDraft?) -> Unit,
  private val setReplyAction: (value: String, owner: ChatComposerOwner) -> Unit,
) {
  fun consume(
    expected: ChatDraft,
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ) = consumeAction(expected, owner, mainSessionKey)

  fun set(value: ChatDraft?) = setAction(value)

  fun setReply(
    value: String,
    owner: ChatComposerOwner,
  ) = setReplyAction(value, owner)
}

internal class ChatComposerShareFeature(
  val queued: StateFlow<List<ChatShareDraft>>,
  val ownerRevision: StateFlow<Long>,
  private val targetsOwnerAction: (id: Long, owner: ChatComposerOwner, mainSessionKey: String) -> Boolean,
  private val forOwnerAction: (owner: ChatComposerOwner, mainSessionKey: String) -> ChatShareDraft?,
  private val resolveOwnerAction: (id: Long?, owner: ChatComposerOwner, mainSessionKey: String) -> Unit,
  private val acknowledgeAction: (id: Long, owner: ChatComposerOwner) -> Boolean,
  private val withLeaseAction: suspend (id: Long, owner: ChatComposerOwner, block: suspend () -> Unit) -> Boolean,
) {
  fun targetsOwner(
    id: Long,
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ) = targetsOwnerAction(id, owner, mainSessionKey)

  fun forOwner(
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ) = forOwnerAction(owner, mainSessionKey)

  fun resolveOwner(
    id: Long?,
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ) = resolveOwnerAction(id, owner, mainSessionKey)

  fun acknowledge(
    id: Long,
    owner: ChatComposerOwner,
  ) = acknowledgeAction(id, owner)

  suspend fun withLease(
    id: Long,
    owner: ChatComposerOwner,
    block: suspend () -> Unit,
  ) = withLeaseAction(id, owner, block)
}

internal class ChatComposerAutoSendFeature(
  val pending: StateFlow<PendingAssistantAutoSend?>,
  val inFlight: StateFlow<Boolean>,
  private val dispatchAction: (pending: PendingAssistantAutoSend, thinking: String) -> Unit,
) {
  fun dispatch(
    pending: PendingAssistantAutoSend,
    thinking: String,
  ) = dispatchAction(pending, thinking)
}

internal class ChatComposerOwnerFeature(
  private val isCurrentAction: (ChatComposerOwner) -> Boolean,
  private val resolveAliasesAction: (owner: ChatComposerOwner, mainSessionKey: String) -> Unit,
) {
  fun isCurrent(owner: ChatComposerOwner) = isCurrentAction(owner)

  fun resolveAliases(
    owner: ChatComposerOwner,
    mainSessionKey: String,
  ) = resolveAliasesAction(owner, mainSessionKey)
}

internal class ChatComposerDeliveryFeature(
  private val beginAction: (owner: ChatComposerOwner, thinking: String) -> ChatComposerSendStartResult,
  private val acknowledgeAdmissionAction: (owner: ChatComposerOwner, id: String) -> Unit,
) {
  fun begin(
    owner: ChatComposerOwner,
    thinking: String,
  ) = beginAction(owner, thinking)

  fun acknowledgeAdmission(
    owner: ChatComposerOwner,
    id: String,
  ) = acknowledgeAdmissionAction(owner, id)
}

internal class ChatComposerAttachmentFeature(
  private val importAttachmentsAction: (
    owner: ChatComposerOwner,
    attachmentAuthorizationId: String,
    mainSessionKey: String,
    expectedCount: Int,
    load: suspend () -> List<PendingAttachment>,
  ) -> Unit,
) {
  fun importAttachments(
    owner: ChatComposerOwner,
    attachmentAuthorizationId: String,
    mainSessionKey: String,
    expectedCount: Int,
    load: suspend () -> List<PendingAttachment>,
  ) = importAttachmentsAction(owner, attachmentAuthorizationId, mainSessionKey, expectedCount, load)
}
