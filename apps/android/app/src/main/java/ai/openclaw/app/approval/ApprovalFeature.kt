package ai.openclaw.app.approval

import kotlinx.coroutines.flow.StateFlow

/** Review-only access to the single inbox; no transport, subscription, or grant lifecycle. */
internal class ApprovalFeature(
  val state: StateFlow<ApprovalInboxState>,
  val actions: ApprovalActions,
)

internal data class ApprovalActions(
  val refresh: () -> Unit,
  val resolve: (String, ApprovalKind, String) -> Unit,
  val dismiss: (ApprovalNotice) -> Unit,
)
