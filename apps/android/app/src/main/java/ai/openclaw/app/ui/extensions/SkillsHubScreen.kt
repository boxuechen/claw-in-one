package ai.openclaw.app.ui.extensions

import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawDetailRow
import ai.openclaw.app.ui.design.ClawListPanel
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawSectionHeader
import ai.openclaw.app.ui.design.ClawStatus
import ai.openclaw.app.ui.design.ClawStatusPill
import ai.openclaw.app.ui.design.ClawTextBadge
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun SkillsHubScreen(
  state: SkillsHubUiState,
  contentPadding: PaddingValues,
  onRefresh: () -> Unit,
  onSearchClawHub: (String) -> Unit,
  onOpenSkill: (String) -> Unit,
  onReviewClawHubSkill: (String) -> Unit,
  onDismissNotice: () -> Unit,
  onDismissInstallReview: () -> Unit,
  onInstallReviewedSkill: (SkillInstallReviewItem) -> Unit,
) {
  var query by rememberSaveable { mutableStateOf("") }
  val normalizedQuery = query.trim()
  val searchActive = normalizedQuery.length >= 2
  val searchPending = searchActive && normalizedQuery != state.directoryQuery.trim()
  val installedMatches =
    remember(state.installedSkills, normalizedQuery) {
      filterInstalledSkills(state.installedSkills, normalizedQuery)
    }
  val directory = remember(state.directorySkills) { skillDirectoryPresentation(state.directorySkills) }

  LaunchedEffect(normalizedQuery) {
    if (searchActive) {
      delay(300)
      onSearchClawHub(normalizedQuery)
    } else if (state.directoryQuery.isNotEmpty()) {
      onSearchClawHub("")
    }
  }

  LazyColumn(
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    item {
      val searchLabel = stringResource(R.string.extension_search_skills)
      ExtensionDirectorySearchField(
        value = query,
        onValueChange = { query = it },
        onClear = { query = "" },
        placeholder = searchLabel,
        enabled = true,
        modifier = Modifier.semantics { contentDescription = searchLabel },
      )
    }

    if (state.lifecycleErrorText != null || state.lifecycleMessageText != null) {
      item {
        SkillOperationNotice(
          errorText = state.lifecycleErrorText,
          messageText = state.lifecycleMessageText,
          onDismiss = onDismissNotice,
        )
      }
    }

    if (searchActive) {
      if (installedMatches.isNotEmpty()) {
        item {
          InstalledSkillsGroup(
            title = stringResource(R.string.extension_installed_shortcuts),
            skills = installedMatches,
            onOpenSkill = onOpenSkill,
          )
        }
      }

      when {
        searchPending || state.directorySearching -> {
          item { ExtensionDirectoryLoading(stringResource(R.string.extension_searching_skills)) }
        }
        state.directoryErrorText != null -> {
          item {
            ExtensionDirectoryState(
              title = stringResource(R.string.extension_plugin_search_failed_title),
              message = state.directoryErrorText,
              actionLabel = stringResource(R.string.extension_try_again),
              onAction = { onSearchClawHub(normalizedQuery) },
              warning = true,
            )
          }
        }
        state.directoryResults.isNotEmpty() -> {
          item {
            ClawHubSkillsGroup(
              title = stringResource(R.string.extension_from_clawhub),
              skills = state.directoryResults,
              canManageSkills = state.canManageSkills,
              methodsAvailable = state.installMethodsAvailable,
              onReview = onReviewClawHubSkill,
            )
          }
        }
        installedMatches.isEmpty() -> {
          item {
            ExtensionDirectoryState(
              title = stringResource(R.string.extension_no_skill_results_title),
              message = stringResource(R.string.extension_no_skill_results_message),
              actionLabel = stringResource(R.string.extension_clear_search),
              onAction = { query = "" },
            )
          }
        }
      }
    } else {
      state.inventoryErrorText?.let { errorText -> item { SkillInlineState(text = errorText, warning = true) } }
      when {
        state.catalogRefreshing && state.directorySkills.isEmpty() -> {
          item { ExtensionDirectoryLoading(stringResource(R.string.extension_loading_skills)) }
        }
        state.catalogErrorText != null && state.directorySkills.isEmpty() -> {
          item {
            ExtensionDirectoryState(
              title = stringResource(R.string.extension_plugin_search_failed_title),
              message = state.catalogErrorText,
              actionLabel = stringResource(R.string.extension_try_again),
              onAction = onRefresh,
              modifier = Modifier.heightIn(min = 360.dp),
              warning = true,
            )
          }
        }
        directory.recommended.isEmpty() -> {
          item {
            ExtensionDirectoryState(
              title = stringResource(R.string.extension_no_skills_title),
              message = stringResource(R.string.extension_no_skills_message),
              modifier = Modifier.heightIn(min = 360.dp),
            )
          }
        }
        else -> {
          if (state.installedSkills.isNotEmpty()) {
            item {
              InstalledSkillsGroup(
                title = stringResource(R.string.extension_installed_shortcuts),
                skills = state.installedSkills,
                onOpenSkill = onOpenSkill,
              )
            }
          }
          item {
            ClawHubSkillsGroup(
              title = nativeString("Recommended"),
              skills = directory.recommended,
              canManageSkills = state.canManageSkills,
              methodsAvailable = state.installMethodsAvailable,
              onReview = onReviewClawHubSkill,
            )
          }
          directory.categories.forEach { category ->
            item {
              ClawHubSkillsGroup(
                title = category.name,
                skills = category.skills,
                canManageSkills = state.canManageSkills,
                methodsAvailable = state.installMethodsAvailable,
                onReview = onReviewClawHubSkill,
              )
            }
          }
        }
      }
    }
  }

  state.installReview?.let { review ->
    SkillInstallReviewSheet(
      review = review,
      canInstall = state.connected && state.canManageSkills && state.installMethodsAvailable,
      onDismiss = onDismissInstallReview,
      onInstall = { onInstallReviewedSkill(review) },
    )
  }
}

@Composable
internal fun SkillDetailScreen(
  skill: InstalledSkillItem?,
  connected: Boolean,
  canManageSkills: Boolean,
  mutating: Boolean,
  contentPadding: PaddingValues,
  onRefresh: () -> Unit,
  onSetEnabled: (Boolean) -> Unit,
) {
  LazyColumn(
    contentPadding = contentPadding,
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    if (skill == null) {
      item {
        ExtensionDirectoryState(
          title = stringResource(R.string.extension_skill_unavailable_title),
          message =
            if (connected) {
              stringResource(R.string.extension_skill_unavailable_message)
            } else {
              stringResource(R.string.extension_skills_offline_message)
            },
          actionLabel = stringResource(R.string.extension_try_again),
          onAction = onRefresh,
        )
      }
      return@LazyColumn
    }

    item { SkillDetailHero(skill) }

    if (skill.status == InstalledSkillStatus.NeedsSetup) {
      item { SkillSetupSection(skill = skill, onRefresh = onRefresh) }
    }

    item {
      val enabledDescription = stringResource(R.string.extension_skill_enabled_description)
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ClawSectionHeader(stringResource(R.string.extension_skill_management))
        ClawListPanel(items = listOf(skill)) {
          ClawDetailRow(
            title = stringResource(R.string.extension_plugin_enabled),
            subtitle = enabledDescription,
            leading = { ClawTextBadge(skill.badge) },
            trailing = {
              Switch(
                checked = skill.status != InstalledSkillStatus.Disabled,
                onCheckedChange = onSetEnabled,
                enabled = connected && canManageSkills && !mutating,
                modifier = Modifier.semantics { contentDescription = enabledDescription },
              )
            },
          )
        }
        if (connected && !canManageSkills) {
          Text(
            text = stringResource(R.string.extension_skill_admin_required),
            style = ClawTheme.type.caption,
            color = ClawTheme.colors.warning,
          )
        }
      }
    }

    item {
      SkillFactsPanel(
        title = stringResource(R.string.extension_skill_information),
        facts =
          listOf(
            stringResource(R.string.extension_plugin_source) to skill.sourceLabel,
            stringResource(R.string.extension_skill_missing_requirements) to skill.missingCount.toString(),
            stringResource(R.string.extension_skill_setup_options) to skill.installCount.toString(),
            stringResource(R.string.extension_plugin_identifier) to skill.skillKey,
          ),
      )
    }
  }
}

@Composable
private fun SkillDetailHero(skill: InstalledSkillItem) {
  Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Surface(
      modifier = Modifier.size(56.dp),
      shape = RoundedCornerShape(16.dp),
      color = ClawTheme.colors.surface,
    ) {
      Box(contentAlignment = Alignment.Center) { ClawTextBadge(skill.badge) }
    }
    Text(text = skill.displayName, style = ClawTheme.type.display, color = ClawTheme.colors.text)
    skill.summary?.let { summary ->
      Text(text = summary, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
    ClawStatusPill(
      text = installedSkillStatusLabel(skill.status),
      status = installedSkillStatusColor(skill.status),
    )
  }
}

@Composable
private fun SkillSetupSection(
  skill: InstalledSkillItem,
  onRefresh: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    ClawSectionHeader(stringResource(R.string.extension_skill_needs_setup))
    ClawPanel {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = stringResource(R.string.extension_skill_needs_setup_message, skill.missingCount),
          style = ClawTheme.type.body,
          color = ClawTheme.colors.textMuted,
        )
        ClawTextButton(
          text = stringResource(R.string.extension_skill_check_again),
          onClick = onRefresh,
        )
      }
    }
  }
}

@Composable
private fun InstalledSkillsGroup(
  title: String,
  skills: List<InstalledSkillItem>,
  onOpenSkill: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    ClawSectionHeader(title)
    skills.forEach { skill -> InstalledSkillRow(skill = skill, onOpen = { onOpenSkill(skill.skillKey) }) }
  }
}

@Composable
private fun InstalledSkillRow(
  skill: InstalledSkillItem,
  onOpen: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .clickable(onClickLabel = stringResource(R.string.extension_open_skill, skill.displayName), onClick = onOpen)
        .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    ClawTextBadge(skill.badge)
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = skill.displayName,
        style = ClawTheme.type.body,
        color = ClawTheme.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = skill.summary ?: skill.sourceLabel,
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    ClawStatusPill(
      text = installedSkillStatusLabel(skill.status),
      status = installedSkillStatusColor(skill.status),
    )
  }
}

@Composable
private fun ClawHubSkillsGroup(
  title: String,
  skills: List<SkillDirectoryItem>,
  canManageSkills: Boolean,
  methodsAvailable: Boolean,
  onReview: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    ClawSectionHeader(title)
    skills.forEach { skill ->
      ClawHubSkillRow(
        skill = skill,
        canManageSkills = canManageSkills,
        methodsAvailable = methodsAvailable,
        onReview = { onReview(skill.reference) },
      )
    }
  }
}

@Composable
private fun ClawHubSkillRow(
  skill: SkillDirectoryItem,
  canManageSkills: Boolean,
  methodsAvailable: Boolean,
  onReview: () -> Unit,
) {
  val actionEnabled =
    methodsAvailable &&
      !skill.installed &&
      !skill.reviewing &&
      !skill.installing &&
      canManageSkills
  val description = listOfNotNull(skill.summary, skill.version?.let { "v$it" }).joinToString(" · ")
  val installDescription = stringResource(R.string.extension_install_skill, skill.displayName)

  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    ClawTextBadge(skillBadge(skill.displayName))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = skill.displayName,
        style = ClawTheme.type.body,
        color = ClawTheme.colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = description,
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    when {
      skill.installed ->
        ClawStatusPill(
          text = stringResource(R.string.extension_plugin_installed),
          status = ClawStatus.Neutral,
        )
      skill.reviewing || skill.installing ->
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = ClawTheme.colors.textMuted,
            strokeWidth = 2.dp,
          )
        }
      else ->
        Surface(
          onClick = onReview,
          enabled = actionEnabled,
          modifier =
            Modifier
              .size(48.dp)
              .semantics { contentDescription = installDescription },
          shape = CircleShape,
          color = androidx.compose.ui.graphics.Color.Transparent,
          contentColor = if (actionEnabled) ClawTheme.colors.text else ClawTheme.colors.textSubtle,
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(24.dp))
          }
        }
    }
  }
}

@Composable
private fun SkillOperationNotice(
  errorText: String?,
  messageText: String?,
  onDismiss: () -> Unit,
) {
  val warning = errorText != null
  val rawText = errorText ?: messageText.orEmpty()
  val summary = rawText.substringBefore("\n\n").trim()
  val details = rawText.substringAfter("\n\n", missingDelimiterValue = "").trim().takeIf(String::isNotEmpty)
  var expanded by rememberSaveable(rawText) { mutableStateOf(false) }
  val accent = if (warning) ClawTheme.colors.danger else ClawTheme.colors.success
  val background = if (warning) ClawTheme.colors.dangerSoft else ClawTheme.colors.successSoft

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(ClawTheme.radii.panel),
    color = background,
    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        text = if (warning) stringResource(R.string.extension_skill_blocked) else stringResource(R.string.extension_plugin_installed),
        style = ClawTheme.type.section,
        color = ClawTheme.colors.text,
      )
      Text(text = summary, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
      if (expanded && details != null) {
        Text(text = details, style = ClawTheme.type.mono, color = ClawTheme.colors.textMuted)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (details != null && !expanded) {
          ClawTextButton(text = stringResource(R.string.extension_skill_review), onClick = { expanded = true })
        }
        ClawTextButton(text = stringResource(R.string.extension_skill_dismiss), onClick = onDismiss)
      }
    }
  }
}

@Composable
private fun SkillInstallReviewSheet(
  review: SkillInstallReviewItem,
  canInstall: Boolean,
  onDismiss: () -> Unit,
  onInstall: () -> Unit,
) {
  ExtensionReviewSheet(
    title = stringResource(R.string.extension_skill_review_title, review.displayName),
    intro = stringResource(R.string.extension_skill_review_intro),
    confirmLabel = stringResource(R.string.extension_skill_verify_install),
    confirmEnabled = canInstall,
    onConfirm = onInstall,
    onDismiss = onDismiss,
  ) {
    review.summary?.let { summary ->
      Text(summary, style = ClawTheme.type.body, color = ClawTheme.colors.textMuted)
    }
    SkillFactsPanel(
      facts =
        listOf(
          stringResource(R.string.extension_plugin_version) to review.version,
          stringResource(R.string.extension_skill_publisher) to review.publisher,
        ),
    )
    if (review.isUnscannedSource) {
      SkillInlineState(
        text = stringResource(R.string.extension_skill_unscanned_review),
        warning = true,
      )
    }
  }
}

@Composable
private fun SkillFactsPanel(
  title: String? = null,
  facts: List<Pair<String, String>>,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    title?.let { ClawSectionHeader(it) }
    ClawPanel(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
      facts.forEachIndexed { index, (label, value) ->
        if (index > 0) HorizontalDivider(color = ClawTheme.colors.border)
        SkillDetailValue(label, value)
      }
    }
  }
}

@Composable
private fun SkillDetailValue(
  label: String,
  value: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = ClawTheme.type.body, color = ClawTheme.colors.text, modifier = Modifier.weight(1f))
    Text(value, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted)
  }
}

@Composable
private fun SkillInlineState(
  text: String,
  warning: Boolean = false,
) {
  ClawPanel {
    Text(
      text = text,
      style = ClawTheme.type.body,
      color = if (warning) ClawTheme.colors.warning else ClawTheme.colors.textMuted,
    )
  }
}

private fun filterInstalledSkills(
  skills: List<InstalledSkillItem>,
  query: String,
): List<InstalledSkillItem> {
  val normalized = query.trim()
  if (normalized.isEmpty()) return skills
  return skills.filter { skill ->
    skill.displayName.contains(normalized, ignoreCase = true) ||
      skill.skillKey.contains(normalized, ignoreCase = true) ||
      skill.summary?.contains(normalized, ignoreCase = true) == true
  }
}

internal fun installedSkillStatusLabel(status: InstalledSkillStatus): String =
  when (status) {
    InstalledSkillStatus.Ready -> nativeString("Ready")
    InstalledSkillStatus.NeedsSetup -> nativeString("Needs setup")
    InstalledSkillStatus.Disabled -> nativeString("Disabled")
  }

private fun installedSkillStatusColor(status: InstalledSkillStatus): ClawStatus =
  when (status) {
    InstalledSkillStatus.Ready -> ClawStatus.Success
    InstalledSkillStatus.NeedsSetup -> ClawStatus.Warning
    InstalledSkillStatus.Disabled -> ClawStatus.Neutral
  }

internal fun skillBadge(name: String): String =
  name
    .split(' ', '-', '_')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull { token -> token.firstOrNull()?.uppercase() }
    .joinToString("")
    .ifBlank { "S" }
