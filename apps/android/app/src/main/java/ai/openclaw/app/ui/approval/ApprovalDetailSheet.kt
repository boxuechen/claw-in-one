package ai.openclaw.app.ui.approval

import ai.openclaw.app.approval.ApprovalActions
import ai.openclaw.app.approval.ApprovalDetails
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawFloatingIconButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** One shared review surface; only the body scrolls. Closing never resolves a request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalDetailSheet(
  review: ApprovalReview?,
  connected: Boolean,
  actions: ApprovalActions,
  onClose: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onClose,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.fillMaxHeight(0.94f),
    containerColor = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
    dragHandle = null,
  ) {
    Column(Modifier.fillMaxHeight().imePadding().testTag("approval-detail-sheet")) {
      Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(nativeString("Review action"), style = ClawTheme.type.section, modifier = Modifier.weight(1f))
        ClawFloatingIconButton(Icons.Default.Close, nativeString("Close approval details"), onClose, modifier = Modifier.testTag("approval-detail-close"))
      }
      Column(
        Modifier
          .weight(1f)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(20.dp)
          .testTag("approval-detail-body"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (review == null) {
          Text(nativeString("This approval is not available. Check its status before continuing."), color = ClawTheme.colors.textMuted)
        } else {
          ApprovalSummary(review, expanded = true)
          SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              when (val details = review.details) {
                is ApprovalDetails.Plugin -> {
                  details.detail?.let { Text(it, style = ClawTheme.type.body) }
                  Text(listOfNotNull(details.pluginId, details.toolName).joinToString(" · "), style = ClawTheme.type.caption, color = ClawTheme.colors.textSubtle)
                }
                is ApprovalDetails.Exec -> Text(listOfNotNull(details.host, details.nodeId).joinToString(" · "), style = ClawTheme.type.caption, color = ClawTheme.colors.textSubtle)
                null -> Unit
              }
              review.details?.scope?.forEach { (label, value) -> Text("$label: $value", style = ClawTheme.type.body) }
              Text(review.id, style = ClawTheme.type.caption, color = ClawTheme.colors.textSubtle)
            }
          }
        }
      }
      Column(Modifier.fillMaxWidth().padding(20.dp).testTag("approval-detail-actions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (review != null) {
          Text(approvalReviewStatus(review, System.currentTimeMillis()), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
          ApprovalDecisionButtons(review, connected, actions, System.currentTimeMillis())
        }
        if (!connected) Text(nativeString("Gateway disconnected."), style = ClawTheme.type.caption, color = ClawTheme.colors.warning)
      }
    }
  }
}
