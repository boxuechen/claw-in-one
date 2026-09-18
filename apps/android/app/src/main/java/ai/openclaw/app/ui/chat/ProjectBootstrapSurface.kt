package ai.openclaw.app.ui.chat

import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.design.DevelopmentBrand
import ai.openclaw.app.ui.design.DevelopmentBrandIcon
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private sealed interface ProjectStarterVisual {
  data class BrandMark(
    val brand: DevelopmentBrand,
    val size: Dp,
  ) : ProjectStarterVisual

  data class Semantic(
    val imageVector: ImageVector,
  ) : ProjectStarterVisual
}

private fun projectStarterVisual(icon: ProjectStarterIcon): ProjectStarterVisual =
  when (icon) {
    ProjectStarterIcon.Kotlin -> ProjectStarterVisual.BrandMark(DevelopmentBrand.Kotlin, 32.dp)
    ProjectStarterIcon.Flutter -> ProjectStarterVisual.BrandMark(DevelopmentBrand.Flutter, 32.dp)
    ProjectStarterIcon.Godot -> ProjectStarterVisual.BrandMark(DevelopmentBrand.Godot, 34.dp)
    ProjectStarterIcon.Web -> ProjectStarterVisual.Semantic(Icons.Default.Language)
  }

@Composable
internal fun ProjectBootstrapSurface(
  starters: List<ProjectStarter>,
  enabled: Boolean,
  onStart: (ProjectStarter) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp).testTag("project-bootstrap"),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Image(
      painter = painterResource(R.drawable.clawinone_logo),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier.size(96.dp).testTag("project-bootstrap-logo"),
    )
    Column(
      modifier = Modifier.padding(top = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = nativeString("What will you build?"),
        style = ClawTheme.type.section,
        color = ClawTheme.colors.text,
        textAlign = TextAlign.Center,
      )
      Text(
        text = nativeString("Choose a starting point, or describe your idea below."),
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        textAlign = TextAlign.Center,
      )
    }
    ProjectStarterGrid(
      starters = starters,
      enabled = enabled,
      onStart = onStart,
      modifier = Modifier.padding(top = 20.dp),
    )
  }
}

@Composable
private fun ProjectStarterGrid(
  starters: List<ProjectStarter>,
  enabled: Boolean,
  onStart: (ProjectStarter) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    starters.chunked(2).forEach { row ->
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { starter ->
          ProjectStarterCard(
            starter = starter,
            enabled = enabled,
            onStart = onStart,
            modifier = Modifier.weight(1f),
          )
        }
        if (row.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun ProjectStarterCard(
  starter: ProjectStarter,
  enabled: Boolean,
  onStart: (ProjectStarter) -> Unit,
  modifier: Modifier = Modifier,
) {
  val actionLabel = nativeString("Add \$starter to the chat input", starter.title)
  Surface(
    modifier =
      modifier
        .heightIn(min = 108.dp)
        .alpha(if (enabled) 1f else 0.52f)
        .testTag("project-starter-${starter.id}")
        .clickable(
          enabled = enabled,
          role = Role.Button,
          onClickLabel = actionLabel,
          onClick = { onStart(starter) },
        ).semantics(mergeDescendants = true) {
          if (!enabled) disabled()
        },
    shape = RoundedCornerShape(ClawTheme.radii.panel),
    color = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      ProjectStarterIcon(
        icon = starter.icon,
        modifier = Modifier.testTag("project-starter-icon-${starter.id}"),
      )
      Text(
        text = starter.title,
        style = ClawTheme.type.label,
        color = ClawTheme.colors.text,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun ProjectStarterIcon(
  icon: ProjectStarterIcon,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.size(36.dp), contentAlignment = Alignment.Center) {
    when (val visual = projectStarterVisual(icon)) {
      is ProjectStarterVisual.BrandMark ->
        DevelopmentBrandIcon(
          brand = visual.brand,
          size = visual.size,
        )
      is ProjectStarterVisual.Semantic ->
        Icon(
          imageVector = visual.imageVector,
          contentDescription = null,
          tint = ClawTheme.colors.textMuted,
          modifier = Modifier.size(30.dp),
        )
    }
  }
}
