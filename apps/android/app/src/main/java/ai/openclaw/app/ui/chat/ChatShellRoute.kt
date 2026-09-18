package ai.openclaw.app.ui.chat

import ai.openclaw.app.approval.ApprovalFeature
import ai.openclaw.app.chat.ChatCurrentWorkFeature
import ai.openclaw.app.chat.ChatDirectoryFeature
import ai.openclaw.app.chat.ChatExecutionFeature
import ai.openclaw.app.chat.ChatGatewayFeature
import ai.openclaw.app.chat.ChatHistoryFeature
import ai.openclaw.app.chat.ChatOutboxFeature
import ai.openclaw.app.chat.ChatSessionOptionsFeature
import ai.openclaw.app.devkit.DeveloperCapabilityCatalogState
import ai.openclaw.app.permissions.SessionPermissionsFeature
import ai.openclaw.app.project.ProjectFeature
import ai.openclaw.app.skill.SkillFeature
import ai.openclaw.app.ui.design.ClawScaffold
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatShellRoute(
  composer: ChatComposerFeature,
  projectStarters: List<ProjectStarter> = emptyList(),
  history: ChatHistoryFeature?,
  currentWork: ChatCurrentWorkFeature?,
  execution: ChatExecutionFeature?,
  appResults: ai.openclaw.app.appdelivery.AndroidAppResultsFeature? = null,
  webResults: ai.openclaw.app.webdelivery.WebProjectResultsFeature? = null,
  gateway: ChatGatewayFeature?,
  outbox: ChatOutboxFeature?,
  directory: ChatDirectoryFeature?,
  sessionOptions: ChatSessionOptionsFeature?,
  approvals: ApprovalFeature?,
  permissions: SessionPermissionsFeature?,
  projects: ProjectFeature?,
  skills: SkillFeature?,
  devKitCatalog: DeveloperCapabilityCatalogState? = null,
  forceBlank: Boolean,
  showSidebarButton: Boolean,
  onOpenSidebar: () -> Unit,
  onOpenAiSettings: () -> Unit = {},
  skillSelectionRequest: ChatSkillSelectionRequest? = null,
  onSkillSelectionConsumed: (Long) -> Unit = {},
  focusApprovalId: String?,
  onApprovalFocusConsumed: () -> Unit,
) {
  ClawScaffold(
    contentPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 0.dp, bottom = 0.dp),
    contentWindowInsets = WindowInsets.safeDrawing,
  ) {
    ChatRoute(
      composer = composer,
      projectStarters = projectStarters,
      history = history,
      currentWork = currentWork,
      execution = execution,
      appResults = appResults,
      webResults = webResults,
      gateway = gateway,
      outbox = outbox,
      directory = directory,
      sessionOptions = sessionOptions,
      approvals = approvals,
      permissions = permissions,
      projects = projects,
      skills = skills,
      devKitCatalog = devKitCatalog,
      forceBlank = forceBlank,
      showSidebarButton = showSidebarButton,
      onOpenSidebar = onOpenSidebar,
      onOpenAiSettings = onOpenAiSettings,
      skillSelectionRequest = skillSelectionRequest,
      onSkillSelectionConsumed = onSkillSelectionConsumed,
      focusApprovalId = focusApprovalId,
      onApprovalFocusConsumed = onApprovalFocusConsumed,
    )
  }
}
