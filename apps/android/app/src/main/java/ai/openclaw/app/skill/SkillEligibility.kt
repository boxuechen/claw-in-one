package ai.openclaw.app.skill

import ai.openclaw.app.GatewaySkillSummary

/** One shared projection for every Android surface that offers an explicit Skill. */
internal fun GatewaySkillSummary.isEligibleForSelection(): Boolean =
  eligible &&
    !disabled &&
    !blockedByAllowlist &&
    !blockedByAgentFilter &&
    missingCount == 0

internal fun List<GatewaySkillSummary>.eligibleSkillReferences(): Set<String> =
  asSequence()
    .filter(GatewaySkillSummary::isEligibleForSelection)
    .mapTo(linkedSetOf(), GatewaySkillSummary::name)
