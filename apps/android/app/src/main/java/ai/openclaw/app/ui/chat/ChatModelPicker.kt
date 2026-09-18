package ai.openclaw.app.ui.chat

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.chat.providerQualifiedRef

internal data class ChatModelPickerSections(
  val current: AiModel?,
  val pinned: List<AiModel>,
  val recent: List<AiModel>,
  val remaining: List<AiModel>,
)

internal fun chatModelPickerSections(
  catalog: List<AiModel>,
  selectedModelRef: String?,
  favorites: List<String>,
  recents: List<String>,
): ChatModelPickerSections {
  val selected = selectedModelRef?.trim()?.takeIf(String::isNotEmpty)
  val modelsByRef = catalog.associateBy { it.providerQualifiedRef() }
  val includedRefs = mutableSetOf<String>()
  val current = selected?.let(modelsByRef::get)?.takeIf { includedRefs.add(it.providerQualifiedRef()) }
  val pinned =
    favorites.mapNotNull { ref ->
      modelsByRef[ref]?.takeIf { includedRefs.add(ref) }
    }
  val recent =
    recents.mapNotNull { ref ->
      modelsByRef[ref]?.takeIf { includedRefs.add(ref) }
    }
  val remaining = catalog.filter { model -> includedRefs.add(model.providerQualifiedRef()) }
  return ChatModelPickerSections(current = current, pinned = pinned, recent = recent, remaining = remaining)
}
