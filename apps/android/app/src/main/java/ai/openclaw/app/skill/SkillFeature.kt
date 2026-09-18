package ai.openclaw.app.skill

import ai.openclaw.app.GatewayClawHubSkillSearchState
import ai.openclaw.app.GatewayClawHubSkillSummary
import ai.openclaw.app.GatewaySkillsSummary
import kotlinx.coroutines.flow.StateFlow

/** Immutable Gateway Skill lifecycle projection. ClawHub directory data is a separate owner. */
internal data class SkillState(
  val connected: Boolean = false,
  val loaded: Boolean = false,
  val adminScope: Boolean = false,
  val installMethodsAvailable: Boolean = false,
  val summary: GatewaySkillsSummary = GatewaySkillsSummary(skills = emptyList()),
  val refreshing: Boolean = false,
  val errorText: String? = null,
  val mutationKeys: Set<String> = emptySet(),
  val clawHub: GatewayClawHubSkillSearchState = GatewayClawHubSkillSearchState(),
)

internal data class SkillActions(
  val refresh: () -> Unit,
  val setEnabled: (String, Boolean) -> Unit,
  val searchClawHub: (String) -> Unit,
  val reviewClawHubInstall: (GatewayClawHubSkillSummary) -> Unit,
  val dismissInstallReview: () -> Unit,
  val installClawHub: (String, String?) -> Unit,
  val clearClawHubNotice: () -> Unit,
)

internal class SkillFeature(
  val state: StateFlow<SkillState>,
  val actions: SkillActions,
)
