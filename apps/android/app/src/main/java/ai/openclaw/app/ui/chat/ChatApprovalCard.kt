package ai.openclaw.app.ui.chat

import ai.openclaw.app.approval.ApprovalActions
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.approval.ApprovalDecisionButtons
import ai.openclaw.app.ui.approval.ApprovalReview
import ai.openclaw.app.ui.approval.ApprovalSummary
import ai.openclaw.app.ui.approval.approvalReviewStatus
import ai.openclaw.app.ui.approval.approvalTitle
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun ChatApprovalCard(
  review: ApprovalReview,
  connected: Boolean,
  actions: ApprovalActions,
  onDetails: (String) -> Unit,
) {
  ClawPanel(modifier = Modifier.fillMaxWidth().testTag("chat-approval-${review.id}")) {
    Column(verticalArrangement = Arrangement.spacedBy(ClawTheme.spacing.sm)) {
      if (review is ApprovalReview.Pending) {
        ApprovalSummary(review)
      } else {
        Text(approvalTitle(review.details), style = ClawTheme.type.section, color = ClawTheme.colors.text)
      }
      Text(approvalReviewStatus(review, System.currentTimeMillis()), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      ClawSecondaryButton(text = nativeString("Details"), onClick = { onDetails(review.id) }, modifier = Modifier.fillMaxWidth())
      ApprovalDecisionButtons(review, connected, actions, System.currentTimeMillis())
    }
  }
}
