package ai.openclaw.app.ui.sidebar

import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class SidebarPalette(
  val background: Color,
  val elevated: Color,
  val selection: Color,
  val text: Color,
  val muted: Color,
  val hairline: Color,
)

@Composable
private fun sidebarPalette(): SidebarPalette {
  val colors = ClawTheme.colors
  val dark = colors.canvas.luminance() < 0.5f
  return SidebarPalette(
    background = colors.canvas,
    elevated = colors.surface,
    selection = colors.surfaceRaised,
    text = colors.text,
    muted = colors.textMuted,
    hairline = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
  )
}

@Composable
internal fun SidebarScreen(
  state: SidebarUiState,
  onAction: (SidebarAction) -> Unit,
) {
  val palette = sidebarPalette()

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(palette.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = 20.dp, vertical = 10.dp),
  ) {
    SidebarHeader(
      palette = palette,
      onSearch = { onAction(SidebarAction.ToggleSearch) },
    )

    if (state.searchOpen) {
      SidebarSearchField(
        query = state.searchQuery,
        onQueryChange = { onAction(SidebarAction.UpdateSearch(it)) },
        palette = palette,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
      )
    }

    Column(
      modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      sidebarPrimaryDestinations.forEach { destination ->
        SidebarNavigationActionRow(
          label = destination.localizedLabel(),
          icon = destination.icon,
          palette = palette,
          modifier = Modifier.testTag("drawer-destination-${destination.name.lowercase()}"),
          onClick = { onAction(SidebarAction.OpenDestination(destination)) },
        )
      }
    }

    SidebarSectionTitle(
      label = nativeString("Projects"),
      palette = palette,
      modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )

    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth().testTag("drawer-project-list"),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      when {
        state.projectsLoading && state.projects.isEmpty() ->
          item("loading") { SidebarEmptyLabel(nativeString("Loading projects"), palette) }
        state.projects.isEmpty() ->
          item("empty") {
            SidebarEmptyLabel(
              if (state.searchOpen && state.searchQuery.isNotBlank()) {
                nativeString("No matching projects or chats")
              } else {
                nativeString("No projects yet")
              },
              palette,
            )
          }
        else ->
          items(state.projects, key = SidebarProjectUi::id) { project ->
            SidebarProjectGroup(
              project = project,
              palette = palette,
              onToggle = { onAction(SidebarAction.ToggleProjectChats(project.id)) },
              onNewChat = { onAction(SidebarAction.NewChatInProject(project.id)) },
              onSelectSession = { onAction(SidebarAction.OpenChat(it.session)) },
            )
          }
      }
    }

    SidebarFooter(
      palette = palette,
      onNewProject = { onAction(SidebarAction.NewProject) },
      onOpenSettings = { onAction(SidebarAction.OpenSettings) },
    )
  }
}

@Composable
private fun SidebarHeader(
  palette: SidebarPalette,
  onSearch: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = sidebarActionTouchTarget),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
      painter = painterResource(R.drawable.clawinone_logo),
      contentDescription = null,
      modifier = Modifier.size(32.dp),
    )
    Text(
      text = nativeString("ClawInOne"),
      style = ClawTheme.type.title,
      color = palette.text,
      modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    SidebarIconButton(
      icon = Icons.Rounded.Search,
      contentDescription = nativeString("Search projects and chats"),
      onClick = onSearch,
      palette = palette,
      modifier = Modifier.testTag("drawer-search"),
    )
  }
}

@Composable
private fun SidebarEmptyLabel(
  text: String,
  palette: SidebarPalette,
) {
  Text(
    text = text,
    style = ClawTheme.type.caption,
    color = palette.muted,
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
  )
}

@Composable
private fun SidebarProjectGroup(
  project: SidebarProjectUi,
  palette: SidebarPalette,
  onToggle: () -> Unit,
  onNewChat: () -> Unit,
  onSelectSession: (SidebarChatUi) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        onClick = onToggle,
        color = Color.Transparent,
        contentColor = palette.text,
        shape = RoundedCornerShape(10.dp),
        modifier =
          Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .semantics {
              stateDescription = nativeString(if (project.expanded) "Expanded" else "Collapsed")
            }.testTag("project-row-${project.id}"),
      ) {
        Row(
          modifier = Modifier.padding(start = 2.dp, end = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = if (project.expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
            contentDescription = null,
            tint = palette.text,
            modifier = Modifier.size(sidebarProjectFolderIconSize),
          )
          Text(
            text = project.displayName,
            style = ClawTheme.type.section,
            color = palette.text,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      SidebarIconButton(
        icon = Icons.Outlined.Edit,
        contentDescription = nativeString("New Chat in \$project", project.displayName),
        onClick = onNewChat,
        palette = palette,
        iconSize = sidebarNewChatIconSize,
        modifier = Modifier.testTag("project-new-chat-${project.id}"),
      )
    }

    if (project.expanded) {
      project.chats.forEach { chat ->
        SidebarProjectChatRow(
          chat = chat,
          palette = palette,
          onClick = { onSelectSession(chat) },
        )
      }
    }
  }
}

@Composable
private fun SidebarProjectChatRow(
  chat: SidebarChatUi,
  palette: SidebarPalette,
  onClick: () -> Unit,
) {
  val session = chat.session
  Surface(
    onClick = onClick,
    color = if (chat.selected) palette.selection else Color.Transparent,
    contentColor = palette.text,
    shape = RoundedCornerShape(10.dp),
    modifier = Modifier.fillMaxWidth().heightIn(min = sidebarActionTouchTarget).padding(start = 40.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = sidebarSessionTitle(session),
        style = ClawTheme.type.body,
        color = palette.text,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (chat.approvalCount > 0) {
        Text(text = chat.approvalCount.toString(), style = ClawTheme.type.caption, color = ClawTheme.colors.accent)
      } else if (session.hasActiveRun == true) {
        Text(text = nativeString("Working"), style = ClawTheme.type.caption, color = palette.muted)
      }
    }
  }
}

@Composable
private fun SidebarFooter(
  palette: SidebarPalette,
  onNewProject: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      onClick = onNewProject,
      color = ClawTheme.colors.accent,
      contentColor = Color.White,
      shape = RoundedCornerShape(24.dp),
      modifier =
        Modifier
          .heightIn(min = sidebarActionTouchTarget)
          .semantics { contentDescription = nativeString("New Project") }
          .testTag("drawer-new-project"),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(nativeString("Project"), style = ClawTheme.type.label, maxLines = 1)
      }
    }
    Spacer(Modifier.weight(1f))
    SidebarIconButton(
      icon = Icons.Rounded.Settings,
      contentDescription = nativeString("Open Settings"),
      onClick = onOpenSettings,
      palette = palette,
      modifier = Modifier.testTag("drawer-open-settings"),
    )
  }
}

@Composable
private fun SidebarIconButton(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  palette: SidebarPalette,
  modifier: Modifier = Modifier,
  iconSize: Dp = 24.dp,
) {
  Surface(
    onClick = onClick,
    color = Color.Transparent,
    contentColor = palette.text,
    shape = CircleShape,
    modifier =
      modifier
        .size(sidebarActionTouchTarget)
        .semantics { this.contentDescription = contentDescription },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
    }
  }
}
