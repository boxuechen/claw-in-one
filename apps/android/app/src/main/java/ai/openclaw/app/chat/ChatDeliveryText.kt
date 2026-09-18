package ai.openclaw.app.chat

import ai.openclaw.app.i18n.NativeText
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.i18n.verbatimText

internal fun chatOutboxQueueFailureText(): NativeText = nativeText("Could not queue message for later delivery.")

/** Presentation mapping shared by admission and the composer; the journal emits typed failures. */
internal fun ChatJournalFailure.toNativeText(): NativeText =
  when (this) {
    ChatJournalFailure.Unavailable -> nativeText("Gateway health not OK; cannot send")
    ChatJournalFailure.AttachmentInvalid -> nativeText("Could not stage an attachment for sending.")
    ChatJournalFailure.StorageUnavailable -> chatOutboxQueueFailureText()
    ChatJournalFailure.QueueFull -> nativeText("Offline queue is full (\$OUTBOX_MAX_QUEUED messages); delete queued items first.", OUTBOX_MAX_QUEUED)
    ChatJournalFailure.AttachmentsTooLarge -> nativeText("Attachments are too large to queue for one message; remove some and try again.")
    ChatJournalFailure.StorageFull -> nativeText("Offline attachment storage is full; delete queued items first.")
  }

/** Only the composition layer translates delivery evidence into visible error copy. */
internal fun ChatDeliveryFailure?.errorText(): NativeText? =
  when (this) {
    null -> null
    ChatDeliveryFailure.GatewayUnavailable -> nativeText("Gateway health not OK; cannot send")
    ChatDeliveryFailure.TerminalFailed -> nativeText("Chat failed before the run started; try again.")
    is ChatDeliveryFailure.Admission -> reason.toNativeText()
    is ChatDeliveryFailure.Transport -> message?.let(::verbatimText)
  }
