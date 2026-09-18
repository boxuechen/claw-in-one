package ai.openclaw.app.approval

enum class ApprovalKind(
  val wire: String,
) {
  Exec("exec"),
  Plugin("plugin"),
}

data class ApprovalSession(
  val key: String,
  val agentId: String? = null,
)

data class ApprovalSource(
  val sessionKey: String?,
  val agentId: String?,
)

/** Canonical source and subscriber audience are deliberately different identities. */
data class ApprovalAttribution(
  val sourceSessionKey: String? = null,
  val audiences: Set<String> = emptySet(),
  val conflicted: Boolean = false,
)

sealed interface ApprovalDetails {
  val kind: ApprovalKind
  val agentId: String?
  val scope: List<Pair<String, String>>

  data class Exec(
    val command: String,
    val preview: String?,
    val warning: String?,
    val host: String?,
    val nodeId: String?,
    override val agentId: String?,
    override val scope: List<Pair<String, String>>,
  ) : ApprovalDetails {
    override val kind = ApprovalKind.Exec
  }

  data class Plugin(
    val title: String,
    val description: String,
    val detail: String?,
    val severity: String,
    val pluginId: String?,
    val toolName: String?,
    val externalResolutionLabel: String?,
    val externalDecisions: Set<String>,
    override val agentId: String?,
    override val scope: List<Pair<String, String>>,
  ) : ApprovalDetails {
    override val kind = ApprovalKind.Plugin
  }
}

data class ApprovalRow(
  val id: String,
  val kind: ApprovalKind,
  val createdAtMs: Long,
  val expiresAtMs: Long,
  val details: ApprovalDetails? = null,
  val allowedDecisions: List<String> = emptyList(),
  val resolvingDecision: String? = null,
  val failure: ApprovalFailure? = null,
  val attribution: ApprovalAttribution = ApprovalAttribution(),
)

enum class ApprovalFailure {
  LoadInbox,
  LoadDetails,
  Resolve,
  OutcomeUnknown,
}

enum class ApprovalStatus {
  Allowed,
  Denied,
  Expired,
  Cancelled,
}

sealed interface ApprovalSnapshot {
  val id: String
  val kind: ApprovalKind

  data class Pending(
    val row: ApprovalRow,
  ) : ApprovalSnapshot {
    override val id get() = row.id
    override val kind get() = row.kind
  }

  data class Terminal(
    override val id: String,
    override val kind: ApprovalKind,
    val status: ApprovalStatus,
    val decision: String?,
    val source: ApprovalSource? = null,
    val resolvedAtMs: Long = 0,
    val details: ApprovalDetails? = null,
    val createdAtMs: Long = 0,
  ) : ApprovalSnapshot
}

data class ApprovalResolution(
  val applied: Boolean,
  val approval: ApprovalSnapshot.Terminal,
)

data class ApprovalNotice(
  val approvalId: String,
  val status: ApprovalStatus,
  val decision: String?,
  val appliedHere: Boolean,
  val publication: Long,
  val sourceSessionKey: String? = null,
)

data class ApprovalOutcome(
  val approval: ApprovalSnapshot.Terminal,
  val row: ApprovalRow?,
  val attribution: ApprovalAttribution,
)

internal data class ApprovalSessionEvent(
  val audience: String,
  val sourceSessionKey: String?,
  val updatedAtMs: Long,
  val approval: ApprovalSnapshot,
)

internal data class ApprovalSessionReplay(
  val audience: String,
  val updatedAtMs: Long,
  val approvals: List<ApprovalRow>,
  val truncated: Boolean,
)

data class ApprovalInboxState(
  val rows: List<ApprovalRow> = emptyList(),
  val refreshing: Boolean = false,
  val failure: ApprovalFailure? = null,
  val notice: ApprovalNotice? = null,
  val outcomes: List<ApprovalOutcome> = emptyList(),
  val sessionKeys: Map<ApprovalSession, String> = emptyMap(),
  val incompleteSessions: Set<ApprovalSession> = emptySet(),
) {
  fun canonicalKey(session: ApprovalSession): String = sessionKeys[session] ?: session.key

  fun pendingFor(session: ApprovalSession): List<ApprovalRow> = rows.filter { !it.attribution.conflicted && it.attribution.sourceSessionKey == canonicalKey(session) }

  val unattributed: List<ApprovalRow>
    get() = rows.filter { it.attribution.conflicted || it.attribution.sourceSessionKey == null }
}
