package ai.openclaw.app.ui.runtime

import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.runtime.RuntimeGateStep
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RuntimeGateScreen(
  state: RuntimeGateUiState,
  onAction: (RuntimeGateUiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  ClawScaffold(modifier = modifier.testTag("runtime-gate")) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(
        modifier =
          Modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Image(
          painter = painterResource(R.drawable.clawinone_logo),
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier = Modifier.size(80.dp).testTag("runtime-gate-logo"),
        )
        Spacer(Modifier.height(20.dp))
        Text(
          text = state.eyebrow,
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.primary,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = state.title,
          style = ClawTheme.type.display,
          color = ClawTheme.colors.text,
          textAlign = TextAlign.Center,
          modifier =
            Modifier.semantics {
              heading()
              liveRegion = LiveRegionMode.Polite
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = state.body,
          style = ClawTheme.type.body,
          color = ClawTheme.colors.textMuted,
          textAlign = TextAlign.Center,
        )
        if (state.showProgress) {
          RuntimeProgress(step = state.step, modifier = Modifier.padding(top = 24.dp))
        }
        if (state.actions.isNotEmpty()) {
          RuntimeGateActions(
            actions = state.actions,
            onAction = onAction,
            modifier = Modifier.padding(top = 24.dp),
          )
        }
        state.error?.let { error ->
          Text(
            text = error,
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.danger,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun RuntimeGateActions(
  actions: List<RuntimeGateActionUiState>,
  onAction: (RuntimeGateUiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    actions.forEach { action ->
      val buttonModifier = Modifier.fillMaxWidth().testTag("runtime-gate-action-${action.action.name}")
      when (action.emphasis) {
        RuntimeGateActionEmphasis.Primary ->
          ClawPrimaryButton(
            text = action.label,
            onClick = { onAction(action.action) },
            modifier = buttonModifier,
            enabled = action.enabled,
          )
        RuntimeGateActionEmphasis.Secondary ->
          ClawSecondaryButton(
            text = action.label,
            onClick = { onAction(action.action) },
            modifier = buttonModifier,
            enabled = action.enabled,
          )
        RuntimeGateActionEmphasis.Tertiary ->
          ClawTextButton(
            text = action.label,
            onClick = { onAction(action.action) },
            modifier = buttonModifier,
            enabled = action.enabled,
          )
      }
    }
  }
}

@Composable
private fun RuntimeProgress(
  step: RuntimeGateStep,
  modifier: Modifier = Modifier,
) {
  val steps = RuntimeGateStep.entries
  val stepNumber = step.ordinal + 1
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .testTag("runtime-gate-progress")
        .semantics {
          progressBarRangeInfo = ProgressBarRangeInfo(stepNumber.toFloat(), 1f..steps.size.toFloat(), steps.size - 1)
        },
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      steps.forEach { item ->
        val color =
          when {
            item.ordinal < step.ordinal -> ClawTheme.colors.primary
            item == step -> ClawTheme.colors.accent
            else -> ClawTheme.colors.border
          }
        Box(
          modifier =
            Modifier
              .weight(1f)
              .height(5.dp)
              .clip(RoundedCornerShape(999.dp))
              .background(color),
        )
      }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      steps.forEach { item ->
        Text(
          text = runtimeStepLabel(item),
          style = ClawTheme.type.captionSmall,
          color = if (item.ordinal <= step.ordinal) ClawTheme.colors.textMuted else ClawTheme.colors.textSubtle,
          textAlign = TextAlign.Center,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@Composable
private fun runtimeStepLabel(step: RuntimeGateStep): String =
  when (step) {
    RuntimeGateStep.Supervisor -> nativeString("Supervisor")
    RuntimeGateStep.Gateway -> nativeString("OpenClaw")
    RuntimeGateStep.AppConnection -> nativeString("App")
    RuntimeGateStep.Ready -> nativeString("Ready")
  }

@Composable
internal fun RuntimeTransientNotice(
  state: RuntimeGateUiState,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(16.dp),
    contentAlignment = Alignment.TopCenter,
  ) {
    ClawPanel(modifier = Modifier.widthIn(max = 520.dp).testTag("runtime-transient-notice")) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(modifier = Modifier.size(10.dp), shape = CircleShape, color = ClawTheme.colors.accent) {}
        Column(modifier = Modifier.weight(1f)) {
          Text(state.title, style = ClawTheme.type.section, color = ClawTheme.colors.text)
          Text(state.body, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
        }
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ClawTheme.colors.primary)
      }
    }
  }
}
