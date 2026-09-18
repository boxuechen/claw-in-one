package ai.openclaw.app.ui.chat

import ai.openclaw.app.SHARED_DOCUMENT_MIME_TYPES
import ai.openclaw.app.chat.ChatComposerOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException

/**
 * Android-owned composer state and actions.
 *
 * Picker leases stay here; the chat route only decides when these actions are available.
 */
internal data class ChatComposerPlatformBinding(
  val attachments: List<PendingAttachment>,
  val attachmentNotice: ChatComposerAttachmentNotice?,
  val sendState: ChatComposerSendState?,
  val pickImages: () -> Unit,
  val pickDocument: () -> Unit,
  val removeAttachment: (String) -> Unit,
  val clearAttachmentNotice: () -> Unit,
) {
  val sendInFlight: Boolean
    get() = sendState != null

  val pendingSendAdmissionIds: Set<String>
    get() = sendState?.pendingAdmissionIds.orEmpty()
}

@Composable
internal fun rememberChatComposerPlatformBinding(
  composer: ChatComposerFeature,
  owner: ChatComposerOwner,
  mainSessionKey: String,
): ChatComposerPlatformBinding {
  val composerState = composer.state
  val context = LocalContext.current
  val resolver = context.applicationContext.contentResolver
  val imagePickerOwnerCheckpoint =
    rememberSaveable(saver = ChatComposerAttachmentCheckpoint.Saver) { ChatComposerAttachmentCheckpoint() }
  val filePickerOwnerCheckpoint =
    rememberSaveable(saver = ChatComposerAttachmentCheckpoint.Saver) { ChatComposerAttachmentCheckpoint() }
  val currentPickerOwner by rememberUpdatedState(owner)
  val currentPickerMainSessionKey by rememberUpdatedState(mainSessionKey)
  val attachmentsByOwner by composerState.attachments.collectAsState()
  val sendStates by composerState.sendStates.collectAsState()
  val attachmentNotices by composerState.attachmentNotices.collectAsState()

  val pickImages =
    rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
      val lease = imagePickerOwnerCheckpoint.consume() ?: return@rememberLauncherForActivityResult
      if (uris.isNullOrEmpty()) {
        composerState.cancelAttachmentAcquisition(lease.authorizationId)
        return@rememberLauncherForActivityResult
      }
      val importOwner =
        if (shouldMigrateComposerDraft(lease.owner, currentPickerOwner, currentPickerMainSessionKey)) {
          currentPickerOwner
        } else {
          lease.owner
        }
      val selectedUris = uris.take(8)
      composer.attachments.importAttachments(
        owner = importOwner,
        attachmentAuthorizationId = lease.authorizationId,
        mainSessionKey = currentPickerMainSessionKey,
        expectedCount = uris.size,
      ) {
        selectedUris.mapNotNull { uri ->
          try {
            loadSizedImageAttachment(resolver, uri)
          } catch (err: CancellationException) {
            throw err
          } catch (_: Throwable) {
            null
          }
        }
      }
    }
  val pickDocument =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      val lease = filePickerOwnerCheckpoint.consume() ?: return@rememberLauncherForActivityResult
      if (uri == null) {
        composerState.cancelAttachmentAcquisition(lease.authorizationId)
        return@rememberLauncherForActivityResult
      }
      val importOwner =
        if (shouldMigrateComposerDraft(lease.owner, currentPickerOwner, currentPickerMainSessionKey)) {
          currentPickerOwner
        } else {
          lease.owner
        }
      composer.attachments.importAttachments(
        owner = importOwner,
        attachmentAuthorizationId = lease.authorizationId,
        mainSessionKey = currentPickerMainSessionKey,
        expectedCount = 1,
      ) {
        listOfNotNull(
          try {
            loadPickedDocumentAttachment(resolver, uri)
          } catch (err: CancellationException) {
            throw err
          } catch (_: Throwable) {
            null
          },
        )
      }
    }

  return ChatComposerPlatformBinding(
    attachments = attachmentsByOwner[owner].orEmpty(),
    attachmentNotice = attachmentNotices[owner],
    sendState = sendStates[owner],
    pickImages = {
      if (composer.owners.isCurrent(owner)) {
        composerState.beginAttachmentAcquisition(owner)?.let { authorizationId ->
          imagePickerOwnerCheckpoint.begin(owner, authorizationId)
          pickImages.launch("image/*")
        }
      }
    },
    pickDocument = {
      if (composer.owners.isCurrent(owner)) {
        composerState.beginAttachmentAcquisition(owner)?.let { authorizationId ->
          filePickerOwnerCheckpoint.begin(owner, authorizationId)
          pickDocument.launch(SHARED_DOCUMENT_MIME_TYPES)
        }
      }
    },
    removeAttachment = { id -> composerState.removeAttachments(owner, setOf(id)) },
    clearAttachmentNotice = { composerState.clearAttachmentOmission(owner) },
  )
}
