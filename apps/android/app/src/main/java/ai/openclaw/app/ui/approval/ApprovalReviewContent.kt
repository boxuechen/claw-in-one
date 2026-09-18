package ai.openclaw.app.ui.approval

import ai.openclaw.app.approval.ApprovalActions
import ai.openclaw.app.approval.ApprovalDetails
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun ApprovalSummary(
  review: ApprovalReview,
  expanded: Boolean = false,
) {
  Text(approvalTitle(review.details), style = ClawTheme.type.section, color = ClawTheme.colors.text)
  when (val details = review.details) {
    is ApprovalDetails.Exec -> {
      // The exact command stays readable before any decision, with no ellipsis or preview substitution.
      SelectionContainer {
        Text(details.command, style = ClawTheme.type.body.copy(fontFamily = FontFamily.Monospace), color = ClawTheme.colors.text)
      }
      details.warning?.let { Text(it, style = ClawTheme.type.body, color = ClawTheme.colors.warning) }
    }
    is ApprovalDetails.Plugin -> {
      Text(if (expanded) details.description else details.description.substringBefore("\n\n"), style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
      details.externalResolutionLabel?.let { Text(it, style = ClawTheme.type.body, color = ClawTheme.colors.warning) }
    }
    null -> Unit
  }
}

@Composable
internal fun ApprovalDecisionButtons(
  review: ApprovalReview,
  connected: Boolean,
  actions: ApprovalActions,
  nowMs: Long,
) {
  val pending = (review as? ApprovalReview.Pending)?.row ?: return
  val enabled = approvalCanDecide(pending, connected, nowMs)
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    approvalActions(pending.allowedDecisions).forEach { action ->
      val modifier = Modifier.fillMaxWidth().testTag("approval-decision-${pending.id}-${action.decision}")
      val onClick = { actions.resolve(pending.id, pending.kind, action.decision) }
      if (action.decision == "allow-once") {
        ClawPrimaryButton(text = action.label, onClick = onClick, enabled = enabled, modifier = modifier)
      } else {
        ClawSecondaryButton(text = action.label, onClick = onClick, enabled = enabled, modifier = modifier)
      }
    }
    if (pending.failure != null || pending.expiresAtMs <= nowMs || pending.details == null) {
      ClawSecondaryButton(text = nativeString("Check status"), onClick = actions.refresh, enabled = connected, modifier = Modifier.fillMaxWidth())
    }
  }
}
