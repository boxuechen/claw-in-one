package ai.openclaw.app.ui.approval

import ai.openclaw.app.approval.ApprovalFeature
import ai.openclaw.app.approval.ApprovalInboxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key

/** A replacement feature must never render the previous inbox with the new actions. */
@Composable
internal fun ApprovalFeature?.collectInbox(): ApprovalInboxState = key(this) { this?.state?.collectAsState()?.value ?: ApprovalInboxState() }
