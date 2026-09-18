package ai.openclaw.app.ui.chat

import ai.openclaw.app.R
import ai.openclaw.app.chat.ChatPlanStepStatus
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatAttentionRow(
  state: ChatAttentionState,
  onAction: (ChatAttentionAction) -> Unit,
) {
  val accent =
    when (state.tone) {
      ChatAttentionTone.Blocking -> ClawTheme.colors.danger
      ChatAttentionTone.Warning -> ClawTheme.colors.warning
      ChatAttentionTone.Status -> ClawTheme.colors.textMuted
    }
  val background =
    when (state.tone) {
      ChatAttentionTone.Blocking -> ClawTheme.colors.dangerSoft
      ChatAttentionTone.Warning -> ClawTheme.colors.warningSoft
      ChatAttentionTone.Status -> ClawTheme.colors.surface
    }
  val icon =
    when (state.tone) {
      ChatAttentionTone.Blocking -> Icons.Default.ErrorOutline
      ChatAttentionTone.Warning -> Icons.Default.WarningAmber
      ChatAttentionTone.Status -> Icons.Default.Info
    }

  Surface(
    modifier = Modifier.fillMaxWidth().testTag("chat-attention-${state.key}"),
    shape = RoundedCornerShape(ClawTheme.radii.row),
    color = background,
    contentColor = ClawTheme.colors.text,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.42f)),
  ) {
    Row(
      modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 8.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(state.title, style = ClawTheme.type.label, color = ClawTheme.colors.text)
        Text(state.body, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        if (state.primaryAction != null || state.secondaryAction != null) {
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.primaryAction?.let { action ->
              ClawTextButton(action.label, { onAction(action.action) })
            }
            state.secondaryAction?.let { action ->
              ClawTextButton(action.label, { onAction(action.action) })
            }
          }
        }
      }
    }
  }
}

@Composable
internal fun ChatCurrentWorkRow(
  state: ChatCurrentWorkUiState,
  run: ChatWorkingRun,
) {
  val steps = state.progress?.steps.orEmpty()
  val currentStep =
    steps.firstOrNull { it.status == ChatPlanStepStatus.InProgress }
      ?: steps.firstOrNull { it.status == ChatPlanStepStatus.Pending }
  val completedCount = steps.count { it.status == ChatPlanStepStatus.Completed }
  val allStepsComplete = steps.isNotEmpty() && completedCount == steps.size
  val currentLabel =
    when {
      state.commentary.isNotEmpty() -> state.commentary.last()
      currentStep != null -> currentStep.step
      allStepsComplete -> nativeString("Finishing…")
      else -> nativeString("Working")
    }
  val detailsAvailable = state.commentary.isNotEmpty() || steps.isNotEmpty() || state.tools.isNotEmpty()
  var expanded by rememberSaveable(run.clockKey) { mutableStateOf(false) }
  val elapsed = rememberWorkingElapsedMs(run.observedAtElapsedMs)
  val expandedState = if (expanded) nativeString("Expanded") else nativeString("Collapsed")
  val rowModifier =
    Modifier
      .fillMaxWidth()
      .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
      .testTag("chat-current-work")
      .then(
        if (detailsAvailable) {
          Modifier
            .clickable { expanded = !expanded }
            .semantics {
              role = Role.Button
              stateDescription = expandedState
            }
        } else {
          Modifier
        },
      )

  Column(modifier = rowModifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      WorkingClawIcon(runKey = run.clockKey, color = ClawTheme.colors.accent)
      Text(
        text = nativeString("Working · \$step", currentLabel),
        style = ClawTheme.type.label,
        color = ClawTheme.colors.text,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (steps.isNotEmpty()) {
        Text("$completedCount/${steps.size}", style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }
      Text(formatLocalizedChatDurationCompact(elapsed), style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      if (detailsAvailable) {
        Icon(
          imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = null,
          tint = ClawTheme.colors.textSubtle,
          modifier = Modifier.size(18.dp),
        )
      }
    }

    if (expanded) {
      HorizontalDivider(color = ClawTheme.colors.border)
      if (state.commentary.isNotEmpty()) {
        Text(nativeString("Updates"), style = ClawTheme.type.captionSmall, color = ClawTheme.colors.textSubtle)
        state.commentary.forEach { update ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
              Box(modifier = Modifier.padding(top = 5.dp).size(5.dp).background(ClawTheme.colors.textSubtle, CircleShape))
            }
            Text(update, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, modifier = Modifier.weight(1f))
          }
        }
      }
      steps.forEach { step -> ChatPlanStepRow(step.step, step.status) }
      state.tools.forEach { tool ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(7.dp).background(ClawTheme.colors.accent, CircleShape))
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(tool.name, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
            Text(nativeString("OpenClaw is working"), style = ClawTheme.type.captionSmall, color = ClawTheme.colors.textSubtle)
          }
          tool.liveDiff?.let { diff ->
            Text(
              nativeString("+\${added} / −\${removed}", diff.added, diff.removed),
              style = ClawTheme.type.caption,
              color = ClawTheme.colors.textMuted,
            )
          }
        }
      }
      run.outputTokens?.let { tokens ->
        Text(
          text = localizedChatOutputTokens(tokens),
          style = ClawTheme.type.captionSmall,
          color = ClawTheme.colors.textSubtle,
        )
      }
    }
  }
}

@Composable
private fun ChatPlanStepRow(
  label: String,
  status: ChatPlanStepStatus,
) {
  val color =
    when (status) {
      ChatPlanStepStatus.Completed -> ClawTheme.colors.textMuted
      ChatPlanStepStatus.InProgress -> ClawTheme.colors.accent
      ChatPlanStepStatus.Pending -> ClawTheme.colors.textSubtle
    }
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
      when (status) {
        ChatPlanStepStatus.Completed ->
          Text("✓", style = ClawTheme.type.caption.copy(fontWeight = FontWeight.Bold), color = ClawTheme.colors.success)
        ChatPlanStepStatus.InProgress ->
          Box(modifier = Modifier.size(8.dp).background(ClawTheme.colors.accent, CircleShape))
        ChatPlanStepStatus.Pending ->
          Box(modifier = Modifier.size(8.dp).background(ClawTheme.colors.textSubtle, CircleShape))
      }
    }
    Text(label, style = ClawTheme.type.caption, color = color)
  }
}

@Composable
internal fun ChatResultRow(
  state: ChatResultState,
  onOpen: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp).testTag("chat-result-${state.key}"),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(state.label, modifier = Modifier.weight(1f), style = ClawTheme.type.body, color = ClawTheme.colors.text)
      TextButton(onClick = onOpen, enabled = !state.opening) {
        Text(
          when (state.kind) {
            ChatResultKind.AndroidApp -> stringResource(if (state.opening) R.string.android_result_opening else R.string.android_result_open)
            ChatResultKind.WebApp -> stringResource(if (state.opening) R.string.web_result_opening else R.string.web_result_open)
          },
        )
      }
    }
    state.warning?.let { message ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = ClawTheme.colors.warning, modifier = Modifier.size(16.dp))
        Text(message, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
      }
    }
    state.error?.let { message ->
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ClawTheme.colors.danger, modifier = Modifier.size(16.dp))
        Text(nativeString(message), style = ClawTheme.type.caption, color = ClawTheme.colors.danger)
      }
    }
  }
}
