package ai.openclaw.app.ui.chat

import ai.openclaw.app.GatewaySkillSummary
import ai.openclaw.app.devkit.DeveloperCapabilityCatalogState
import ai.openclaw.app.devkit.DeveloperCapabilityStatus
import ai.openclaw.app.devkit.devKitChatSkillDefinitions
import ai.openclaw.app.skill.isEligibleForSelection
import java.util.Locale

internal const val CHAT_COMPOSER_MAX_SKILL_REFERENCES = 8

internal data class ChatSkillMentionOption(
  val reference: String,
  val label: String,
  val description: String?,
  val emoji: String?,
)

internal data class ChatSkillMentionQuery(
  val start: Int,
  val query: String,
)

/** Intersects the fixed product catalog with Gateway eligibility and owner-backed DevKit readiness. */
internal fun eligibleChatSkillMentions(
  skills: List<GatewaySkillSummary>,
  capabilities: DeveloperCapabilityCatalogState?,
): List<ChatSkillMentionOption> {
  val readyCapabilityIds =
    capabilities
      ?.capabilities
      ?.asSequence()
      ?.filter { capability -> capability.status == DeveloperCapabilityStatus.Ready }
      ?.mapTo(hashSetOf()) { capability -> capability.id }
      .orEmpty()
  val eligibleSkills =
    skills
      .asSequence()
      .filter(GatewaySkillSummary::isEligibleForSelection)
      .mapNotNull { skill ->
        val reference = normalizeChatSkillReference(skill.name) ?: return@mapNotNull null
        reference to skill
      }.toMap()

  return devKitChatSkillDefinitions.mapNotNull { definition ->
    if (definition.capabilityId !in readyCapabilityIds) return@mapNotNull null
    val skill = eligibleSkills[definition.skillReference] ?: return@mapNotNull null
    ChatSkillMentionOption(
      reference = definition.skillReference,
      label = definition.chatLabel,
      description = skill.description,
      emoji = skill.emoji,
    )
  }
}

/** A mention is offered only for the active trailing @token; the token stays presentation-only. */
internal fun chatSkillMentionQuery(value: String): ChatSkillMentionQuery? {
  val marker = value.lastIndexOf('@')
  if (marker < 0 || (marker > 0 && !value[marker - 1].isWhitespace())) return null
  val query = value.substring(marker + 1)
  if (query.any(Char::isWhitespace)) return null
  return ChatSkillMentionQuery(marker, query)
}

internal fun matchingChatSkillMentions(
  value: String,
  options: List<ChatSkillMentionOption>,
): List<ChatSkillMentionOption> {
  val query = chatSkillMentionQuery(value)?.query?.trim()?.lowercase(Locale.US) ?: return emptyList()
  return options.filter { option ->
    option.reference.lowercase(Locale.US).contains(query) ||
      option.label.lowercase(Locale.US).contains(query) ||
      option.description?.lowercase(Locale.US)?.contains(query) == true
  }
}

internal fun staleChatSkillReferences(
  loaded: Boolean,
  selectedReferences: List<String>,
  options: List<ChatSkillMentionOption>,
): Set<String> {
  if (!loaded) return emptySet()
  val eligible = options.mapTo(hashSetOf(), ChatSkillMentionOption::reference)
  return selectedReferences.filterNotTo(linkedSetOf(), eligible::contains)
}

internal fun removeChatSkillMentionQuery(value: String): String {
  val query = chatSkillMentionQuery(value) ?: return value
  return value.removeRange(query.start, value.length).trimEnd()
}

internal fun normalizeChatSkillReferences(references: List<String>): List<String> =
  references
    .mapNotNull(::normalizeChatSkillReference)
    .distinct()
    .take(CHAT_COMPOSER_MAX_SKILL_REFERENCES)

internal fun serializeExplicitSkillPrompt(
  references: List<String>,
  prompt: String,
): String {
  val normalized = normalizeChatSkillReferences(references)
  val body = prompt.trim()
  if (normalized.isEmpty()) return body
  val selectors = normalized.joinToString(" ") { reference -> "\$$reference" }
  return if (body.isEmpty()) selectors else "$selectors\n\n$body"
}

private fun normalizeChatSkillReference(value: String): String? =
  value
    .trim()
    .takeIf { reference ->
      reference.isNotEmpty() &&
        reference.length <= 128 &&
        reference.none { char -> char.isWhitespace() || char.isISOControl() || char == '$' || char == '@' }
    }
