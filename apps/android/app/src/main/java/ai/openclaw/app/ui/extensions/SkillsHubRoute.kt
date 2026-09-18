package ai.openclaw.app.ui.extensions

import ai.openclaw.app.GatewayClawHubInstallReview
import ai.openclaw.app.GatewayClawHubSkillSearchState
import ai.openclaw.app.GatewayClawHubSkillSummary
import ai.openclaw.app.GatewaySkillSummary
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.isClawHubSkillOperationActive
import ai.openclaw.app.skill.SkillFeature
import ai.openclaw.app.skill.SkillState
import ai.openclaw.app.skill.catalog.OfficialSkillSearchMatch
import ai.openclaw.app.skill.catalog.OfficialSkillSummary
import ai.openclaw.app.skill.catalog.SkillCatalogFailure
import ai.openclaw.app.skill.catalog.SkillCatalogSnapshot
import ai.openclaw.app.skill.catalog.SkillDirectoryFeature
import ai.openclaw.app.skill.catalog.SkillLocalState
import ai.openclaw.app.skill.catalog.overlaySkillLocalState
import ai.openclaw.app.uppercaseFirstGraphemeOrNull
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Reconciles the read-only ClawHub Skill directory with Gateway lifecycle state. */
@Composable
internal fun SkillsHubRoute(
  skill: SkillFeature?,
  directory: SkillDirectoryFeature,
  destination: ExtensionCenterDestination,
  contentPadding: PaddingValues,
  onOpenSkill: (String) -> Unit,
) {
  val skillState = skill?.state?.collectAsState()?.value ?: SkillState()
  val skillsSummary = skillState.summary
  val refreshing = skillState.refreshing
  val errorText = skillState.errorText
  val mutationKeys = skillState.mutationKeys
  val lifecycleState = skillState.clawHub
  val directoryState by directory.state.collectAsState()
  val methodsAvailable = skillState.installMethodsAvailable
  val connected = skillState.connected
  val operatorAdminScopeAvailable = skillState.adminScope
  val installedSkills =
    skillsSummary.skills
      .map { skill -> skill.toInstalledSkillItem() }
      .filterNot(InstalledSkillItem::isBuiltIn)
  val directorySkills =
    overlaySkillLocalState(directoryState.items, skillsSummary.skills)
      .map { it.toDirectoryItem(lifecycleState) }
  val searchedSkills =
    directoryState.searchResults.map { it.toDirectoryItem(skillsSummary.skills, lifecycleState) }
  val officialSkillsByReference =
    (directoryState.items + directoryState.searchResults.map(OfficialSkillSearchMatch::skill))
      .associateBy { it.identity.reference }
  val state =
    SkillsHubUiState(
      connected = connected,
      canManageSkills = connected && operatorAdminScopeAvailable,
      installMethodsAvailable = methodsAvailable,
      inventoryRefreshing = refreshing,
      inventoryErrorText = errorText,
      catalogRefreshing = directoryState.refreshing,
      catalogErrorText = directoryState.error?.toPresentationText(),
      installedSkills = installedSkills,
      mutatingSkillKeys = mutationKeys,
      directorySkills = directorySkills,
      directoryQuery = directoryState.query,
      directorySearching = directoryState.searching,
      directoryResults = searchedSkills,
      directoryErrorText = directoryState.searchError?.toPresentationText(),
      lifecycleErrorText = lifecycleState.errorText,
      lifecycleMessageText = lifecycleState.messageText,
      installReview = lifecycleState.installReview?.toPresentationItem(),
    )

  LaunchedEffect(Unit) { directory.actions.refresh() }
  LaunchedEffect(connected) {
    if (connected) skill?.actions?.refresh?.invoke()
  }

  when (destination) {
    is ExtensionCenterDestination.SkillDetail -> {
      val installedSkill = installedSkills.firstOrNull { it.skillKey == destination.skillKey }
      SkillDetailScreen(
        skill = installedSkill,
        connected = connected,
        canManageSkills = state.canManageSkills,
        mutating = destination.skillKey in mutationKeys,
        contentPadding = contentPadding,
        onRefresh = { skill?.actions?.refresh?.invoke() },
        onSetEnabled = { enabled -> skill?.actions?.setEnabled?.invoke(destination.skillKey, enabled) },
      )
    }
    else -> {
      SkillsHubScreen(
        state = state,
        contentPadding = contentPadding,
        onRefresh = {
          directory.actions.refresh()
          if (connected) skill?.actions?.refresh?.invoke()
        },
        onSearchClawHub = directory.actions.search,
        onOpenSkill = onOpenSkill,
        onReviewClawHubSkill = { reference ->
          officialSkillsByReference[reference]
            ?.toGatewayInstallCandidate()
            ?.let { candidate -> skill?.actions?.reviewClawHubInstall?.invoke(candidate) }
        },
        onDismissNotice = { skill?.actions?.clearClawHubNotice?.invoke() },
        onDismissInstallReview = { skill?.actions?.dismissInstallReview?.invoke() },
        onInstallReviewedSkill = { review ->
          skill?.actions?.dismissInstallReview?.invoke()
          skill?.actions?.installClawHub?.invoke(review.reference, review.version)
        },
      )
    }
  }
}

internal fun GatewaySkillSummary.toInstalledSkillItem(): InstalledSkillItem =
  InstalledSkillItem(
    skillKey = skillKey,
    displayName = name,
    summary = description,
    sourceLabel = skillSourceLabel(this),
    badge = skillBadge(this),
    status = installedSkillStatus(this),
    missingCount = missingCount,
    installCount = installCount,
    isBuiltIn = isOpenClawOwnedSkill(),
  )

/** Mirrors OpenClaw's own grouping rule instead of inferring ownership from a Skill path. */
internal fun GatewaySkillSummary.isOpenClawOwnedSkill(): Boolean = bundled || source == "openclaw-bundled"

private fun SkillCatalogSnapshot.toDirectoryItem(lifecycle: GatewayClawHubSkillSearchState): SkillDirectoryItem {
  val installed = local as? SkillLocalState.Installed
  return SkillDirectoryItem(
    reference = catalog.identity.reference,
    displayName = catalog.displayName,
    summary = catalog.summary,
    version = installed?.version ?: catalog.latestVersion,
    categories = catalog.categories,
    installed = installed != null,
    installedSkillKey = installed?.skillKey,
    reviewing = lifecycle.reviewingSlug == catalog.identity.reference,
    installing = isClawHubSkillOperationActive(lifecycle.installingSlugs, catalog.identity.reference),
  )
}

private fun OfficialSkillSearchMatch.toDirectoryItem(
  installedSkills: List<GatewaySkillSummary>,
  lifecycle: GatewayClawHubSkillSearchState,
): SkillDirectoryItem = overlaySkillLocalState(listOf(skill), installedSkills).single().toDirectoryItem(lifecycle)

private fun OfficialSkillSummary.toGatewayInstallCandidate(): GatewayClawHubSkillSummary =
  GatewayClawHubSkillSummary(
    slug = identity.slug,
    installRef = identity.reference,
    installOnly = false,
    trustState = null,
    displayName = displayName,
    summary = summary,
    version = latestVersion,
  )

private fun SkillCatalogFailure.toPresentationText(): String =
  when (this) {
    is SkillCatalogFailure.RateLimited ->
      retryAfterSeconds?.let { nativeString("ClawHub is busy. Try again in \$count seconds.", it) }
        ?: nativeString("ClawHub is busy. Try again shortly.")
    is SkillCatalogFailure.Http -> nativeString("ClawHub is unavailable (HTTP \$count).", statusCode)
    SkillCatalogFailure.Network -> nativeString("Can't reach ClawHub. Check your connection and try again.")
    SkillCatalogFailure.InvalidResponse -> nativeString("ClawHub returned an invalid response.")
    SkillCatalogFailure.NotOfficial -> nativeString("This item is not in the official catalog.")
  }

private fun GatewayClawHubInstallReview.toPresentationItem(): SkillInstallReviewItem =
  SkillInstallReviewItem(
    reference = slug,
    displayName = displayName,
    summary = summary,
    version = version,
    publisher = author,
    isUnscannedSource = trustState == ai.openclaw.app.CLAWHUB_UNSCANNED_TRUST_STATE,
  )

private fun installedSkillStatus(skill: GatewaySkillSummary): InstalledSkillStatus =
  when {
    skill.disabled -> InstalledSkillStatus.Disabled
    !skill.eligible || skill.blockedByAllowlist || skill.blockedByAgentFilter || skill.missingCount > 0 ->
      InstalledSkillStatus.NeedsSetup
    else -> InstalledSkillStatus.Ready
  }

private fun skillSourceLabel(skill: GatewaySkillSummary): String =
  when (skill.source) {
    "openclaw-bundled" -> if (skill.bundled) nativeString("Built-in") else nativeString("Bundled")
    "openclaw-managed" -> nativeString("Installed")
    "openclaw-workspace" -> nativeString("Workspace")
    "openclaw-extra" -> nativeString("Extra")
    else -> nativeString("Skill")
  }

private fun skillBadge(skill: GatewaySkillSummary): String =
  skill.emoji
    ?: skill.name
      .split(' ', '-', '_')
      .filter(String::isNotBlank)
      .take(2)
      .mapNotNull(String::uppercaseFirstGraphemeOrNull)
      .joinToString("")
      .ifBlank { "S" }
