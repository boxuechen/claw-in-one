package ai.openclaw.app.ui.chat

import ai.openclaw.app.ChatDraft
import ai.openclaw.app.ChatDraftPlacement
import ai.openclaw.app.ChatShareDraft
import ai.openclaw.app.SharedAttachment
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.OUTBOX_MAX_COMMAND_ATTACHMENT_BYTES
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import kotlinx.coroutines.CancellationException

internal const val CHAT_COMPOSER_MAX_DRAFT_OWNERS = 16
internal const val CHAT_COMPOSER_DRAFT_SNAPSHOT_MAX_CHARS = 64 * 1024
internal const val CHAT_COMPOSER_MAX_SEND_CHARS = 20_000
private const val CHAT_COMPOSER_DRAFT_SNAPSHOT_FIELDS = 9
private const val CHAT_COMPOSER_DRAFT_RECORD = "draft"
private const val CHAT_COMPOSER_PENDING_SEND_RECORD = "pending-send"
private const val CHAT_COMPOSER_PENDING_SEND_WITHOUT_INPUT_RECORD = "pending-send-without-input"

internal data class PendingChatComposerSend(
  val commandId: String,
  val owner: ChatComposerOwner,
  val inputSnapshot: String?,
  val skillReferences: List<String> = emptyList(),
)

internal data class ChatComposerDraftSnapshot(
  val drafts: Map<ChatComposerOwner, String> = emptyMap(),
  val skillReferences: Map<ChatComposerOwner, List<String>> = emptyMap(),
  val pendingSends: List<PendingChatComposerSend> = emptyList(),
)

internal class ChatComposerTextDraftStore(
  initial: ChatComposerDraftSnapshot = ChatComposerDraftSnapshot(),
  private val onSnapshotChanged: (ArrayList<String>) -> Unit = {},
) {
  private val drafts = mutableStateMapOf<ChatComposerOwner, String>()
  private val skillReferences = mutableStateMapOf<ChatComposerOwner, List<String>>()
  private val recency = ArrayDeque<ChatComposerOwner>()
  private val pendingSends = LinkedHashMap<String, PendingChatComposerSend>()

  init {
    initial.drafts.forEach { (owner, text) ->
      drafts[owner] = text
      recency.addLast(owner)
    }
    initial.skillReferences.forEach { (owner, references) ->
      val normalized = normalizeChatSkillReferences(references)
      if (normalized.isNotEmpty()) skillReferences[owner] = normalized
      if (owner !in recency) recency.addLast(owner)
    }
    initial.pendingSends.forEach { pending -> pendingSends[pending.commandId] = pending }
  }

  operator fun get(owner: ChatComposerOwner): String = drafts[owner].orEmpty()

  operator fun set(
    owner: ChatComposerOwner,
    value: String,
  ) {
    recency.remove(owner)
    if (value.isEmpty()) {
      drafts.remove(owner)
      if (skillReferences[owner].isNullOrEmpty()) recency.remove(owner) else recency.addLast(owner)
      onSnapshotChanged(snapshot())
      return
    }
    ensureOwnerCapacity(owner)
    drafts[owner] = value
    recency.addLast(owner)
    onSnapshotChanged(snapshot())
  }

  fun skillReferences(owner: ChatComposerOwner): List<String> = skillReferences[owner].orEmpty()

  fun stageProjectStarter(
    owner: ChatComposerOwner,
    prompt: String,
    skillReference: String,
  ): Boolean {
    if (prompt.isBlank() || skillReference.isBlank()) return false
    if (drafts[owner].orEmpty().isNotBlank() || skillReferences(owner).isNotEmpty()) return false
    val references = normalizeChatSkillReferences(listOf(skillReference))
    if (references.isEmpty()) return false
    ensureOwnerCapacity(owner)
    drafts[owner] = prompt
    skillReferences[owner] = references
    recency.remove(owner)
    recency.addLast(owner)
    onSnapshotChanged(snapshot())
    return true
  }

  fun selectSkillReference(
    owner: ChatComposerOwner,
    reference: String,
  ) {
    val next = normalizeChatSkillReferences(skillReferences(owner) + reference)
    if (next == skillReferences(owner)) return
    ensureOwnerCapacity(owner)
    skillReferences[owner] = next
    recency.remove(owner)
    recency.addLast(owner)
    onSnapshotChanged(snapshot())
  }

  fun removeSkillReference(
    owner: ChatComposerOwner,
    reference: String,
  ) {
    val current = skillReferences(owner)
    val next = current.filterNot { it == reference }
    if (next == current) return
    if (next.isEmpty()) skillReferences.remove(owner) else skillReferences[owner] = next
    if (drafts[owner].isNullOrEmpty() && next.isEmpty()) recency.remove(owner)
    onSnapshotChanged(snapshot())
  }

  fun migrate(
    from: ChatComposerOwner,
    to: ChatComposerOwner,
  ) {
    if (from == to) return
    val draft = drafts.remove(from)
    val incomingReferences = skillReferences.remove(from).orEmpty()
    val existing = drafts[to]
    var changed = false
    pendingSends.toMap().forEach { (commandId, pending) ->
      if (pending.owner == from) {
        changed = true
        pendingSends[commandId] = pending.copy(owner = to)
      }
    }
    if (draft == null && incomingReferences.isEmpty()) {
      if (changed) onSnapshotChanged(snapshot())
      return
    }
    recency.remove(from)
    val mergedText = mergeChatComposerDraftText(existing, draft)
    if (mergedText != null) drafts[to] = mergedText
    val mergedReferences = normalizeChatSkillReferences(incomingReferences + skillReferences(to))
    if (mergedReferences.isNotEmpty()) skillReferences[to] = mergedReferences
    ensureOwnerCapacity(to)
    recency.remove(to)
    recency.addLast(to)
    onSnapshotChanged(snapshot())
  }

  /** Resolves every parked alias, including drafts not visited since gateway hello. */
  fun migrateMatching(
    to: ChatComposerOwner,
    mainSessionKey: String,
  ): Set<ChatComposerOwner> {
    val sources =
      (recency + pendingSends.values.map(PendingChatComposerSend::owner))
        .filterTo(linkedSetOf()) { source -> shouldMigrateComposerDraft(source, to, mainSessionKey) }
    sources.forEach { source -> migrate(from = source, to = to) }
    return sources
  }

  /** Checkpoints the pre-send draft with the id that the durable outbox will use. */
  fun beginAdmission(
    commandId: String,
    owner: ChatComposerOwner,
    inputSnapshot: String,
    selectedSkillReferences: List<String> = emptyList(),
  ): Boolean {
    check(commandId !in pendingSends)
    if (inputSnapshot.length > CHAT_COMPOSER_MAX_SEND_CHARS) return false
    val pending = PendingChatComposerSend(commandId, owner, inputSnapshot, normalizeChatSkillReferences(selectedSkillReferences))
    val checkpointChars =
      (pendingSends.values + pending).sumOf { pendingSendCheckpointEntry(it, includeInput = true).sumOf(String::length) }
    if (checkpointChars > CHAT_COMPOSER_DRAFT_SNAPSHOT_MAX_CHARS) return false
    pendingSends[commandId] = pending
    if (drafts[owner] == inputSnapshot) {
      drafts.remove(owner)
    }
    if (skillReferences(owner) == pending.skillReferences) skillReferences.remove(owner)
    if (drafts[owner].isNullOrEmpty() && skillReferences[owner].isNullOrEmpty()) recency.remove(owner)
    onSnapshotChanged(snapshot())
    return true
  }

  /** Resolves one live or process-restored send without clearing text edited after admission. */
  fun resolveAdmission(
    commandId: String,
    admitted: Boolean,
  ): PendingChatComposerSend? {
    val pending = pendingSends.remove(commandId) ?: return null
    val current = drafts[pending.owner]
    if (!admitted) {
      val restored = mergeChatComposerDraftText(pending.inputSnapshot, current)
      if (restored != null) {
        drafts[pending.owner] = restored
        recency.remove(pending.owner)
        recency.addLast(pending.owner)
      }
      val restoredReferences = normalizeChatSkillReferences(pending.skillReferences + skillReferences(pending.owner))
      if (restoredReferences.isNotEmpty()) {
        skillReferences[pending.owner] = restoredReferences
        recency.remove(pending.owner)
        recency.addLast(pending.owner)
      }
    }
    onSnapshotChanged(snapshot())
    return pending
  }

  fun pendingAdmissions(): List<PendingChatComposerSend> = pendingSends.values.toList()

  fun pendingAdmission(commandId: String): PendingChatComposerSend? = pendingSends[commandId]

  fun removeOwners(matches: (ChatComposerOwner) -> Boolean) {
    val owners = (drafts.keys + skillReferences.keys).filterTo(linkedSetOf(), matches)
    val removedPending = pendingSends.entries.removeAll { matches(it.value.owner) }
    if (owners.isEmpty() && !removedPending) return
    owners.forEach(drafts::remove)
    owners.forEach(skillReferences::remove)
    recency.removeAll(owners::contains)
    onSnapshotChanged(snapshot())
  }

  internal fun snapshot(): ArrayList<String> {
    var remainingChars = CHAT_COMPOSER_DRAFT_SNAPSHOT_MAX_CHARS
    val records = mutableListOf<List<String>>()
    // Pending ids are the crash-consistency boundary. Always checkpoint the marker; keep its
    // draft too when it fits, so restart can restore it only after proving no outbox row exists.
    pendingSends.values.forEach { pending ->
      val fullEntry = pendingSendCheckpointEntry(pending, includeInput = true)
      val entry =
        if (fullEntry.sumOf(String::length) <= remainingChars) {
          fullEntry
        } else {
          pendingSendCheckpointEntry(pending, includeInput = false)
        }
      records += entry
      remainingChars -= entry.sumOf(String::length)
    }
    val retainedNewestFirst = mutableListOf<List<String>>()
    // SavedStateHandle is written into the Activity transaction. Keep the full in-memory drafts,
    // but checkpoint only the newest complete entries that fit the bounded process-death budget.
    for (owner in recency.reversed()) {
      val text = drafts[owner].orEmpty()
      val references = skillReferences(owner)
      if (text.isEmpty() && references.isEmpty()) continue
      val entry = listOf(CHAT_COMPOSER_DRAFT_RECORD) + owner.toCheckpointValues() + listOf("", text, encodeSkillReferences(references))
      val entryChars = entry.sumOf(String::length)
      if (entryChars > remainingChars) continue
      retainedNewestFirst += entry
      remainingChars -= entryChars
    }
    records += retainedNewestFirst.asReversed()
    return ArrayList(records.flatten())
  }

  internal fun size(): Int = (drafts.keys + skillReferences.keys).toSet().size

  private fun ensureOwnerCapacity(owner: ChatComposerOwner) {
    if (owner in drafts || owner in skillReferences) return
    while (size() >= CHAT_COMPOSER_MAX_DRAFT_OWNERS) {
      val retired = recency.removeFirst()
      drafts.remove(retired)
      skillReferences.remove(retired)
    }
  }
}

private fun pendingSendCheckpointEntry(
  pending: PendingChatComposerSend,
  includeInput: Boolean,
): List<String> =
  listOf(if (includeInput) CHAT_COMPOSER_PENDING_SEND_RECORD else CHAT_COMPOSER_PENDING_SEND_WITHOUT_INPUT_RECORD) +
    pending.owner.toCheckpointValues() +
    listOf(
      pending.commandId,
      if (includeInput) pending.inputSnapshot.orEmpty() else "",
      if (includeInput) encodeSkillReferences(pending.skillReferences) else "",
    )

internal fun chatComposerTextDraftsFromSnapshot(values: List<String>?): ChatComposerDraftSnapshot {
  if (values == null || values.size % CHAT_COMPOSER_DRAFT_SNAPSHOT_FIELDS != 0) {
    return ChatComposerDraftSnapshot()
  }
  val restored = LinkedHashMap<ChatComposerOwner, String>()
  val restoredSkillReferences = LinkedHashMap<ChatComposerOwner, List<String>>()
  val pending = mutableListOf<PendingChatComposerSend>()
  values.chunked(CHAT_COMPOSER_DRAFT_SNAPSHOT_FIELDS).forEach { entry ->
    val owner = chatComposerOwnerFromCheckpointValues(entry.subList(1, 6)) ?: return@forEach
    when (entry[0]) {
      CHAT_COMPOSER_DRAFT_RECORD -> {
        if (entry[7].isNotEmpty()) restored[owner] = entry[7]
        decodeSkillReferences(entry[8]).takeIf(List<String>::isNotEmpty)?.let { restoredSkillReferences[owner] = it }
      }
      CHAT_COMPOSER_PENDING_SEND_RECORD -> {
        if (entry[6].isNotEmpty()) {
          pending += PendingChatComposerSend(entry[6], owner, entry[7], decodeSkillReferences(entry[8]))
        }
      }
      CHAT_COMPOSER_PENDING_SEND_WITHOUT_INPUT_RECORD -> {
        if (entry[6].isNotEmpty()) {
          pending += PendingChatComposerSend(entry[6], owner, null)
        }
      }
    }
  }
  return ChatComposerDraftSnapshot(drafts = restored, skillReferences = restoredSkillReferences, pendingSends = pending)
}

private fun encodeSkillReferences(references: List<String>): String = normalizeChatSkillReferences(references).joinToString("\u001f")

private fun decodeSkillReferences(value: String): List<String> = normalizeChatSkillReferences(value.split('\u001f').filter(String::isNotEmpty))

private fun mergeChatComposerDraftText(
  existing: String?,
  incoming: String?,
): String? =
  when {
    existing.isNullOrEmpty() -> incoming?.takeIf(String::isNotEmpty)
    incoming.isNullOrEmpty() || incoming == existing -> existing
    else -> "$existing\n\n$incoming"
  }

internal fun ChatComposerOwner.matchesSession(
  gatewayStableId: String,
  agentId: String,
  sessionKey: String,
  mainSessionKey: String,
): Boolean {
  if (this.gatewayStableId != gatewayStableId) return false
  val canonicalMain = mainSessionKey.trim().ifEmpty { "main" }
  val ownerKey = this.sessionKey.trim().let { if (it == "main") canonicalMain else it }
  val deletedKey = sessionKey.trim().let { if (it == "main") canonicalMain else it }
  return ownerKey == deletedKey && (this.agentId == agentId || !routingVerified)
}

/** One-shot owner lease for an Android attachment-picker result. */
internal class ChatComposerAttachmentCheckpoint(
  var owner: ChatComposerOwner? = null,
  private var attachmentAuthorizationId: String? = null,
) {
  fun begin(
    owner: ChatComposerOwner,
    attachmentAuthorizationId: String,
  ) {
    this.owner = owner
    this.attachmentAuthorizationId = attachmentAuthorizationId
  }

  fun consume(): ChatComposerAttachmentLease? {
    val capturedOwner = owner ?: return null
    val capturedAuthorizationId = attachmentAuthorizationId ?: return null
    return ChatComposerAttachmentLease(capturedOwner, capturedAuthorizationId).also { clear() }
  }

  fun clear(): ChatComposerAttachmentLease? {
    val lease =
      owner?.let { capturedOwner ->
        attachmentAuthorizationId?.let { capturedAuthorizationId ->
          ChatComposerAttachmentLease(capturedOwner, capturedAuthorizationId)
        }
      }
    owner = null
    attachmentAuthorizationId = null
    return lease
  }

  companion object {
    val Saver =
      listSaver<ChatComposerAttachmentCheckpoint, String>(
        save = { checkpoint ->
          val capturedOwner = checkpoint.owner
          val capturedAuthorizationId = checkpoint.attachmentAuthorizationId
          if (capturedOwner == null || capturedAuthorizationId == null) {
            emptyList()
          } else {
            capturedOwner.toCheckpointValues() + capturedAuthorizationId
          }
        },
        restore = { values ->
          ChatComposerAttachmentCheckpoint(
            owner = chatComposerOwnerFromCheckpointValues(values.take(5)),
            attachmentAuthorizationId = values.getOrNull(5),
          )
        },
      )
  }
}

internal data class ChatComposerAttachmentLease(
  val owner: ChatComposerOwner,
  val authorizationId: String,
)

internal fun ChatComposerOwner.toCheckpointValues(): List<String> =
  listOf(
    if (gatewayStableId == null) "0" else "1",
    gatewayStableId.orEmpty(),
    agentId,
    sessionKey,
    if (routingVerified) "1" else "0",
  )

internal fun chatComposerOwnerFromCheckpointValues(values: List<String>): ChatComposerOwner? {
  if (values.size != 5) return null
  return ChatComposerOwner(
    gatewayStableId = values[1].takeIf { values[0] == "1" },
    agentId = values[2],
    sessionKey = values[3],
    routingVerified = values[4] == "1",
  )
}

internal fun shouldMigrateComposerDraft(
  previous: ChatComposerOwner?,
  current: ChatComposerOwner,
  mainSessionKey: String,
): Boolean {
  if (previous == null || previous == current) return false
  val canonicalMain = mainSessionKey.trim()
  val mainAliasResolved =
    previous.sessionKey == "main" &&
      canonicalMain != "main" &&
      current.sessionKey == canonicalMain
  val unboundGatewayClaimed = previous.gatewayStableId == null && current.gatewayStableId != null
  if (previous.gatewayStableId != current.gatewayStableId && !unboundGatewayClaimed) return false
  // Content captured before Gateway startup belongs to the local pairing once routing is
  // verified. Session identity still must match; an unverified agent id is only a placeholder
  // and cannot claim the draft after pairing replacement.
  if (unboundGatewayClaimed) {
    if (!current.routingVerified) return false
    if (previous.routingVerified && previous.agentId != current.agentId) return false
    return previous.sessionKey == current.sessionKey || mainAliasResolved
  }
  if (!previous.routingVerified && current.routingVerified) {
    return previous.sessionKey == current.sessionKey || mainAliasResolved
  }
  return previous.agentId == current.agentId && mainAliasResolved
}

internal fun mergeChatDraft(
  draft: ChatDraft?,
  currentInput: String,
  currentOwner: ChatComposerOwner? = null,
): String? {
  if (draft?.owner != null && draft.owner != currentOwner) return null
  val text = draft?.text?.takeIf { it.isNotBlank() } ?: return null
  return when (draft.placement) {
    ChatDraftPlacement.Replace -> text
    ChatDraftPlacement.BeforeExisting -> text + currentInput
  }
}

/** Appends system shares so existing drafts stay first and queued shares remain FIFO. */
internal fun mergeSharedChatText(
  sharedText: String?,
  currentInput: String,
): String {
  val shared = sharedText?.trim()?.takeIf { it.isNotEmpty() } ?: return currentInput
  return if (currentInput.isEmpty()) shared else listOf(currentInput, shared).joinToString(separator = "\n\n")
}

internal data class StagedChatShare(
  val text: String?,
  val attachments: List<PendingAttachment>,
  val failedAttachmentCount: Int,
  val droppedAttachmentCount: Int,
)

internal const val CHAT_COMPOSER_MAX_ATTACHMENTS = 8

// Images retain their 6 MiB Gateway cap; ordinary files use the Android outbox's 8 MiB cap.
internal const val CHAT_COMPOSER_MAX_IMAGE_DECODED_BYTES = 6L * 1024L * 1024L
internal const val CHAT_COMPOSER_MAX_DOCUMENT_DECODED_BYTES = OUTBOX_MAX_COMMAND_ATTACHMENT_BYTES
internal const val CHAT_COMPOSER_MAX_DECODED_ATTACHMENT_BYTES = CHAT_COMPOSER_MAX_DOCUMENT_DECODED_BYTES
internal const val CHAT_COMPOSER_MAX_BASE64_CHARS = ((CHAT_COMPOSER_MAX_DECODED_ATTACHMENT_BYTES + 2) / 3) * 4
internal const val CHAT_COMPOSER_MAX_TOTAL_ATTACHMENTS = 24
internal const val CHAT_COMPOSER_MAX_TOTAL_DECODED_ATTACHMENT_BYTES = CHAT_COMPOSER_MAX_DECODED_ATTACHMENT_BYTES * 3
internal const val CHAT_COMPOSER_MAX_TOTAL_BASE64_CHARS = ((CHAT_COMPOSER_MAX_TOTAL_DECODED_ATTACHMENT_BYTES + 2) / 3) * 4

internal data class ChatAttachmentAdmission(
  val accepted: List<PendingAttachment>,
  val omittedCount: Int,
)

internal fun admitChatAttachments(
  currentAttachments: List<PendingAttachment>,
  candidates: List<PendingAttachment>,
  maxAttachmentCount: Int = CHAT_COMPOSER_MAX_ATTACHMENTS,
  maxBase64Chars: Long = CHAT_COMPOSER_MAX_BASE64_CHARS,
  maxDecodedBytes: Long = CHAT_COMPOSER_MAX_DECODED_ATTACHMENT_BYTES,
): ChatAttachmentAdmission {
  require(maxAttachmentCount >= 0 && maxBase64Chars >= 0 && maxDecodedBytes >= 0)
  val accepted = mutableListOf<PendingAttachment>()
  var base64Chars = currentAttachments.sumOf { it.base64.length.toLong() }
  var decodedBytes = currentAttachments.sumOf { decodedBase64ByteCount(it.base64) }
  var omittedCount = 0
  for (candidate in candidates) {
    val candidateBase64Chars = candidate.base64.length.toLong()
    val candidateDecodedBytes = decodedBase64ByteCount(candidate.base64)
    val withinKind = candidateDecodedBytes <= chatComposerAttachmentDecodedByteLimit(candidate.mimeType)
    val withinCount = currentAttachments.size + accepted.size < maxAttachmentCount
    val withinBase64 = candidateBase64Chars <= maxBase64Chars - base64Chars
    val withinDecoded = candidateDecodedBytes <= maxDecodedBytes - decodedBytes
    if (withinKind && withinCount && withinBase64 && withinDecoded) {
      accepted += candidate
      base64Chars += candidateBase64Chars
      decodedBytes += candidateDecodedBytes
    } else {
      omittedCount += 1
    }
  }
  return ChatAttachmentAdmission(accepted = accepted, omittedCount = omittedCount)
}

internal fun chatComposerAttachmentDecodedByteLimit(mimeType: String): Long =
  when {
    mimeType.startsWith("image/", ignoreCase = true) -> CHAT_COMPOSER_MAX_IMAGE_DECODED_BYTES
    else -> CHAT_COMPOSER_MAX_DOCUMENT_DECODED_BYTES
  }

internal fun decodedBase64ByteCount(base64: String): Long {
  val padding =
    when {
      base64.endsWith("==") -> 2
      base64.endsWith('=') -> 1
      else -> 0
    }
  return ((base64.length.toLong() * 3) / 4 - padding).coerceAtLeast(0)
}

/** Loads a complete queue head before any part of it becomes visible in the composer. */
internal suspend fun stageChatShareDraft(
  draft: ChatShareDraft,
  loadAttachment: suspend (SharedAttachment) -> PendingAttachment,
): StagedChatShare {
  val attachments = mutableListOf<PendingAttachment>()
  var failedAttachmentCount = 0
  var droppedAttachmentCount = draft.droppedAttachmentCount
  for (sharedAttachment in draft.attachments) {
    try {
      val candidate = loadAttachment(sharedAttachment)
      val admission = admitChatAttachments(attachments, listOf(candidate))
      attachments += admission.accepted
      droppedAttachmentCount += admission.omittedCount
    } catch (error: CancellationException) {
      // Screen disposal must leave the queue head unacknowledged for the next ChatScreen.
      throw error
    } catch (_: Exception) {
      failedAttachmentCount += 1
    }
  }
  return StagedChatShare(
    text = draft.text,
    attachments = attachments,
    failedAttachmentCount = failedAttachmentCount,
    droppedAttachmentCount = droppedAttachmentCount,
  )
}

internal fun canCommitStagedChatShare(
  stagedId: Long,
  currentHead: ChatShareDraft?,
  ownerSnapshot: ChatComposerOwner,
  currentOwner: ChatComposerOwner,
): Boolean =
  currentHead?.id == stagedId &&
    ownerSnapshot == currentOwner

internal fun chatComposerSendEnabled(
  pendingRunCount: Int,
  hasContent: Boolean,
  shareStaging: Boolean,
  sendInFlight: Boolean = false,
): Boolean =
  !shareStaging &&
    !sendInFlight &&
    pendingRunCount == 0 &&
    hasContent
