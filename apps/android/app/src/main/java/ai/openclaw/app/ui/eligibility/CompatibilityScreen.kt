package ai.openclaw.app.ui.eligibility

import ai.openclaw.app.R
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun CompatibilityScreen(
  state: CompatibilityUiState,
  onAction: (CompatibilityAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  ClawScaffold(modifier = modifier.testTag("compatibility-screen")) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(
        modifier =
          Modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Image(
          painter = painterResource(R.drawable.clawinone_logo),
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier = Modifier.size(88.dp),
        )
        Spacer(Modifier.height(22.dp))
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
        if (state.checking) {
          Spacer(Modifier.height(22.dp))
          CircularProgressIndicator(
            modifier = Modifier.size(22.dp).testTag("compatibility-progress"),
            color = ClawTheme.colors.primary,
            strokeWidth = 2.dp,
          )
        }
        state.primaryLabel?.let { label ->
          Spacer(Modifier.height(24.dp))
          ClawPrimaryButton(
            text = label,
            onClick = { onAction(checkNotNull(state.primaryAction)) },
            modifier = Modifier.fillMaxWidth().testTag("compatibility-primary-action"),
          )
        }
        state.secondaryLabel?.let { label ->
          ClawTextButton(
            text = label,
            onClick = { onAction(checkNotNull(state.secondaryAction)) },
            modifier = Modifier.fillMaxWidth().testTag("compatibility-secondary-action"),
          )
        }
      }
    }
  }
}
