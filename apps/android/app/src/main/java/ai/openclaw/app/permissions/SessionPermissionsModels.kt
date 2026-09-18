package ai.openclaw.app.permissions

/** Gateway modes are not interchangeable: unset and Workspace never imply Full. */
internal enum class SessionPermissionMode(
  val wire: String?,
) {
  Default(null),
  Standard("guarded"),
  Full("full"),
  ReadOnly("read-only"),
  Workspace("workspace"),
}

internal data class SessionPermissionTarget(
  val gatewayId: String,
  val key: String,
  val agentId: String,
) {
  init {
    require(gatewayId.isNotBlank() && agentId.isNotBlank())
    require(key.startsWith("agent:$agentId:") && key.length > "agent:$agentId:".length)
  }
}

internal data class SessionPermissionConnection(
  val gatewayId: String,
  val generation: Long,
  val catalogRevision: Long,
  val scopes: Set<String>,
  val methods: Set<String>,
) {
  val canRead get() = scopes.any { it in setOf("operator.read", "operator.write", "operator.admin") }
  val canSend get() = scopes.any { it in setOf("operator.write", "operator.admin") }

  fun canChoose(mode: SessionPermissionMode): Boolean =
    when (mode) {
      SessionPermissionMode.Default,
      SessionPermissionMode.ReadOnly,
      SessionPermissionMode.Standard,
      SessionPermissionMode.Workspace,
      -> canSend
      SessionPermissionMode.Full -> "operator.admin" in scopes
    }
}

internal data class SessionPermissionRef(
  val target: SessionPermissionTarget,
  val connection: SessionPermissionConnection,
  val sessionId: String,
  /** Only a current create/patch response supplies this; ordinary rows do not. */
  val lifecycleRevision: String? = null,
  val projectId: String? = null,
  val projectRoot: String? = null,
)

internal enum class SessionPermissionPhase { Loading, Ready, Applying, Unconfirmed, Unavailable }

internal enum class SessionPermissionFailure {
  Disconnected,
  MissingAuthority,
  Unsupported,
  ReadFailed,
  ChangeRejected,
  ChangeUnconfirmed,
  IdentityChanged,
}

internal data class SessionPermissionsState(
  val target: SessionPermissionTarget,
  val ref: SessionPermissionRef? = null,
  val savedMode: SessionPermissionMode? = null,
  val confirmedMode: SessionPermissionMode? = null,
  val requestedMode: SessionPermissionMode? = null,
  val phase: SessionPermissionPhase = SessionPermissionPhase.Loading,
  val failure: SessionPermissionFailure? = null,
) {
  val readyToSend get() = phase == SessionPermissionPhase.Ready && confirmedMode != null && ref?.connection?.canSend == true
}

internal data class SessionPermissionRow(
  val key: String,
  val agentId: String,
  val sessionId: String,
  val mode: SessionPermissionMode,
  val pending: Boolean,
  val projectId: String? = null,
  val sessionRoot: String? = null,
  val modelRef: String? = null,
  val thinkingLevel: String? = null,
  val displayName: String? = null,
)

internal data class SessionPermissionMutation(
  val key: String,
  val sessionId: String,
  val mode: SessionPermissionMode,
  val lifecycleRevision: String?,
  val projectId: String? = null,
)

/** Local intent. Identity and payload stay fixed from the first creation attempt. */
internal data class SessionPermissionDraft(
  val target: SessionPermissionTarget,
  val idempotencyKey: String,
  val mode: SessionPermissionMode = SessionPermissionMode.Standard,
  val projectId: String? = null,
  val projectRoot: String? = null,
  // One-time creation settings, frozen by the Chat draft owner. Ongoing settings stay in Chat.
  val modelRef: String? = null,
  val thinkingLevel: String? = null,
  val displayName: String? = null,
) {
  init {
    require(idempotencyKey.isNotBlank())
    require(mode in setOf(SessionPermissionMode.Standard, SessionPermissionMode.Full, SessionPermissionMode.Workspace))
    require((mode == SessionPermissionMode.Workspace) == (!projectId.isNullOrBlank() && !projectRoot.isNullOrBlank()))
    require(modelRef == null || modelRef.isNotBlank())
    require(thinkingLevel == null || thinkingLevel.isNotBlank())
    require(displayName == null || (displayName.isNotBlank() && displayName.length <= 500))
  }
}

/** Authentication and socket ownership stay in the Gateway adapter, never in UI or this domain. */
internal interface SessionPermissionsTransport {
  fun capture(): SessionPermissionConnection?

  /** Publishes under the transport's connection lock, excluding socket replacement. */
  fun publish(
    connection: SessionPermissionConnection,
    block: () -> Unit,
  ): Boolean

  suspend fun request(
    connection: SessionPermissionConnection,
    method: String,
    params: String,
  ): String
}
