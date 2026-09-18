package ai.openclaw.app.chat

import ai.openclaw.app.gateway.SessionObserverDigest

internal fun mergeChatSessionEntry(
  existing: ChatSessionEntry,
  next: ChatSessionEntry,
  preserveExistingContextUsageWithoutTotal: Boolean = false,
  replaceActiveRunIds: Boolean = false,
): ChatSessionEntry {
  val preserveExistingContextUsage = preserveExistingContextUsageWithoutTotal && next.totalTokens == null
  val hasActiveRun = if (next.hasActiveRunMetadata) next.hasActiveRun else existing.hasActiveRun
  val activeRunIds =
    if (replaceActiveRunIds || next.hasActiveRunIdsMetadata) next.activeRunIds else existing.activeRunIds
  val observerDigest =
    reconcileSessionObserverDigest(
      existing = existing.observerDigest,
      next = next.observerDigest,
      hasNextProjection = next.hasObserverDigestMetadata,
      hasActiveRun = hasActiveRun,
      activeRunIds = activeRunIds,
      status = if (next.hasRunMetadata) next.status else existing.status,
    )
  return existing.copy(
    // Partial events may omit identity; retain the last observed occurrence until
    // an authoritative event supplies the replacement session ID.
    sessionId = next.sessionId ?: existing.sessionId,
    updatedAtMs = next.updatedAtMs ?: existing.updatedAtMs,
    ownerAgentId = next.ownerAgentId ?: existing.ownerAgentId,
    classification = if (next.hasClassificationMetadata) next.classification else existing.classification,
    accountId = if (next.hasClassificationMetadata) next.accountId else existing.accountId,
    peerKind = if (next.hasClassificationMetadata) next.peerKind else existing.peerKind,
    isMain = if (next.hasClassificationMetadata) next.isMain else existing.isMain,
    isBackground = if (next.hasClassificationMetadata) next.isBackground else existing.isBackground,
    hasClassificationMetadata = existing.hasClassificationMetadata || next.hasClassificationMetadata,
    displayName = next.displayName ?: existing.displayName,
    label = next.label ?: existing.label,
    // Omitted metadata preserves the tint; explicit null from another client clears it.
    color = if (next.hasColorMetadata) next.color else existing.color,
    hasColorMetadata = existing.hasColorMetadata || next.hasColorMetadata,
    pinned = next.pinned ?: existing.pinned,
    archived = next.archived ?: existing.archived,
    unread = next.unread ?: existing.unread,
    lastReadAt = next.lastReadAt ?: existing.lastReadAt,
    markedUnreadAt =
      if (next.hasMarkedUnreadMetadata) next.markedUnreadAt else existing.markedUnreadAt,
    hasMarkedUnreadMetadata =
      existing.hasMarkedUnreadMetadata || next.hasMarkedUnreadMetadata,
    agentStatus = if (next.hasAgentStatusMetadata) next.agentStatus else existing.agentStatus,
    hasAgentStatusMetadata = existing.hasAgentStatusMetadata || next.hasAgentStatusMetadata,
    observerDigest = observerDigest,
    hasObserverDigestMetadata = existing.hasObserverDigestMetadata || next.hasObserverDigestMetadata,
    lastActivityAt = next.lastActivityAt ?: existing.lastActivityAt,
    totalTokens =
      when {
        preserveExistingContextUsage -> existing.totalTokens
        next.hasContextUsageMetadata -> next.totalTokens
        else -> null
      },
    totalTokensFresh =
      when {
        preserveExistingContextUsage -> existing.totalTokensFresh
        next.hasContextUsageMetadata -> next.totalTokensFresh
        else -> null
      },
    modelProvider = next.modelProvider ?: existing.modelProvider,
    model = next.model ?: existing.model,
    thinkingLevel = next.thinkingLevel ?: existing.thinkingLevel,
    thinkingLevels = next.thinkingLevels ?: existing.thinkingLevels,
    thinkingDefault = next.thinkingDefault ?: existing.thinkingDefault,
    contextTokens =
      when {
        preserveExistingContextUsage -> next.contextTokens ?: existing.contextTokens
        next.hasContextUsageMetadata -> next.contextTokens
        else -> null
      },
    hasContextUsageMetadata =
      when {
        preserveExistingContextUsage -> existing.hasContextUsageMetadata || next.contextTokens != null
        else -> next.hasContextUsageMetadata
      },
    hasActiveRun = hasActiveRun,
    activeRunIds = activeRunIds,
    hasActiveRunMetadata = existing.hasActiveRunMetadata || next.hasActiveRunMetadata,
    hasActiveRunIdsMetadata =
      if (replaceActiveRunIds) {
        next.hasActiveRunIdsMetadata
      } else {
        existing.hasActiveRunIdsMetadata || next.hasActiveRunIdsMetadata
      },
    status = if (next.hasRunMetadata) next.status else existing.status,
    lastRunError = if (next.hasRunMetadata) next.lastRunError else existing.lastRunError,
    startedAt = if (next.hasRunMetadata) next.startedAt else existing.startedAt,
    endedAt = if (next.hasRunMetadata) next.endedAt else existing.endedAt,
    runtimeMs = if (next.hasRunMetadata) next.runtimeMs else existing.runtimeMs,
    outputTokens = if (next.hasRunMetadata) next.outputTokens else existing.outputTokens,
    hasRunMetadata = existing.hasRunMetadata || next.hasRunMetadata,
  )
}

internal fun applySessionObserverDigest(
  sessions: List<ChatSessionEntry>,
  digest: SessionObserverDigest,
  activeAgentId: String? = null,
): List<ChatSessionEntry> {
  val digestAgentId = normalizedObserverAgentId(digest.agentId)
  val selectedAgentId = normalizedObserverAgentId(activeAgentId)
  val scopedSessions =
    reconcileGlobalObserverDigestOwner(sessions, selectedAgentId, adoptOwnerless = false)
  if (
    digest.sessionKey == "global" &&
    (selectedAgentId == null || digestAgentId == null || selectedAgentId != digestAgentId)
  ) {
    return scopedSessions
  }
  val index = scopedSessions.indexOfFirst { it.key == digest.sessionKey }
  if (index < 0) return scopedSessions
  val session = scopedSessions[index]
  val runId = digest.runId?.trim()?.takeIf { it.isNotEmpty() } ?: return scopedSessions
  val isRunning = session.hasActiveRun == true || session.status?.trim()?.lowercase() == "running"
  val matchesActiveRun = session.activeRunIds.orEmpty().any { it.trim() == runId }
  if (!isRunning || !matchesActiveRun) return scopedSessions
  val previous = session.observerDigest
  if (previous?.runId == runId && !observerDigestIsNewer(digest, previous)) return scopedSessions
  return scopedSessions.toMutableList().also {
    it[index] = session.copy(observerDigest = digest, hasObserverDigestMetadata = true)
  }
}

internal fun reconcileGlobalObserverDigestOwner(
  sessions: List<ChatSessionEntry>,
  activeAgentId: String?,
  adoptOwnerless: Boolean = true,
): List<ChatSessionEntry> {
  // A missing owner is transient disconnect state, not a selection change.
  // Callers retain the last verified offline projection until hello supplies an owner.
  val selectedAgentId = normalizedObserverAgentId(activeAgentId) ?: return sessions
  val index = sessions.indexOfFirst { it.key == "global" }
  if (index < 0) return sessions
  val session = sessions[index]
  val digestAgentId = normalizedObserverAgentId(session.observerDigest?.agentId)
  if (digestAgentId == selectedAgentId) return sessions
  return sessions.toMutableList().also {
    it[index] =
      session.copy(
        observerDigest =
          if (digestAgentId == null && adoptOwnerless) {
            session.observerDigest?.copy(agentId = selectedAgentId)
          } else {
            null
          },
        hasObserverDigestMetadata = true,
      )
  }
}

internal fun reconcileSessionObserverProjectionOwner(
  session: ChatSessionEntry,
  ownerAgentId: String?,
): ChatSessionEntry {
  val digest = session.observerDigest
  if (session.key != "global" || digest == null) return session
  val owner =
    normalizedObserverAgentId(ownerAgentId)
      ?: return session.copy(observerDigest = null, hasObserverDigestMetadata = false)
  val digestOwner = normalizedObserverAgentId(digest.agentId)
  return when (digestOwner) {
    null -> session.copy(observerDigest = digest.copy(agentId = owner))
    owner -> session
    else -> session.copy(observerDigest = null, hasObserverDigestMetadata = false)
  }
}

private fun normalizedObserverAgentId(agentId: String?): String? = agentId?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

private fun reconcileSessionObserverDigest(
  existing: SessionObserverDigest?,
  next: SessionObserverDigest?,
  hasNextProjection: Boolean,
  hasActiveRun: Boolean?,
  activeRunIds: List<String>?,
  status: String?,
): SessionObserverDigest? {
  val isRunning = hasActiveRun == true || status?.trim()?.lowercase() == "running"
  val activeIds = activeRunIds.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
  var resolved = existing
  if (isRunning && resolved?.runId?.trim()?.let(activeIds::contains) != true) {
    resolved = null
  }
  if (next != null) {
    val matchesActiveRun = !isRunning || next.runId?.trim()?.let(activeIds::contains) == true
    if (matchesActiveRun) {
      val previous = resolved
      resolved =
        if (previous != null && previous.runId == next.runId && !observerDigestIsNewer(next, previous)) {
          previous
        } else {
          next
        }
    }
  } else if (hasNextProjection) {
    resolved = null
  }
  return resolved
}

private fun observerDigestIsNewer(
  candidate: SessionObserverDigest,
  previous: SessionObserverDigest,
): Boolean =
  candidate.revision > previous.revision ||
    (candidate.revision == previous.revision && candidate.updatedAt > previous.updatedAt)
