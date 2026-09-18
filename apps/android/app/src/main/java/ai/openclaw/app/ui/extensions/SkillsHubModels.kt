package ai.openclaw.app.ui.extensions

internal enum class InstalledSkillStatus {
  Ready,
  NeedsSetup,
  Disabled,
}

/** Presentation projection of one installed Skill. */
internal data class InstalledSkillItem(
  val skillKey: String,
  val displayName: String,
  val summary: String?,
  val sourceLabel: String,
  val badge: String,
  val status: InstalledSkillStatus,
  val missingCount: Int,
  val installCount: Int,
  /** True when OpenClaw, rather than the user or workspace, owns this Skill. */
  val isBuiltIn: Boolean,
)

/** Official ClawHub candidate with Gateway lifecycle state overlaid. */
internal data class SkillDirectoryItem(
  val reference: String,
  val displayName: String,
  val summary: String?,
  val version: String?,
  val categories: List<String>,
  val installed: Boolean,
  val installedSkillKey: String?,
  val reviewing: Boolean,
  val installing: Boolean,
)

internal data class SkillInstallReviewItem(
  val reference: String,
  val displayName: String,
  val summary: String?,
  val version: String,
  val publisher: String,
  val isUnscannedSource: Boolean,
)

internal data class SkillsHubUiState(
  val connected: Boolean,
  val canManageSkills: Boolean,
  val installMethodsAvailable: Boolean,
  val inventoryRefreshing: Boolean,
  val inventoryErrorText: String?,
  val catalogRefreshing: Boolean,
  val catalogErrorText: String?,
  val installedSkills: List<InstalledSkillItem>,
  val mutatingSkillKeys: Set<String>,
  val directorySkills: List<SkillDirectoryItem>,
  val directoryQuery: String,
  val directorySearching: Boolean,
  val directoryResults: List<SkillDirectoryItem>,
  val directoryErrorText: String?,
  val lifecycleErrorText: String?,
  val lifecycleMessageText: String?,
  val installReview: SkillInstallReviewItem?,
)

internal data class SkillDirectoryPresentation(
  val recommended: List<SkillDirectoryItem>,
  val categories: List<SkillCategoryPresentation>,
)

internal data class SkillCategoryPresentation(
  val name: String,
  val skills: List<SkillDirectoryItem>,
)

internal fun skillDirectoryPresentation(skills: List<SkillDirectoryItem>): SkillDirectoryPresentation {
  val categoryNames = skills.flatMap(SkillDirectoryItem::categories).distinct()
  return SkillDirectoryPresentation(
    recommended = skills,
    categories =
      categoryNames.map { category ->
        SkillCategoryPresentation(
          name = category,
          skills = skills.filter { category in it.categories },
        )
      },
  )
}
