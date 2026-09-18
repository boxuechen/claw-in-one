package ai.openclaw.app.chat

import ai.openclaw.app.ai.AiModel

internal fun AiModel.providerQualifiedRef(): String {
  val trimmedProvider = provider.trim()
  if (trimmedProvider.isEmpty()) return id
  val providerPrefix = "$trimmedProvider/"
  return if (id.startsWith(providerPrefix)) id else "$providerPrefix$id"
}

internal fun thinkingSupportedForAiSelection(
  selectedModelRef: String?,
  catalog: List<AiModel>,
): Boolean {
  val selected = selectedModelRef?.trim()?.takeIf(String::isNotEmpty) ?: return true
  return catalog.firstOrNull { it.providerQualifiedRef() == selected }?.supportsReasoning != false
}
