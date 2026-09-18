package ai.openclaw.app.skill.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SkillDirectoryState(
  val items: List<OfficialSkillSummary> = emptyList(),
  val nextCursor: String? = null,
  val refreshing: Boolean = false,
  val loadingNextPage: Boolean = false,
  val error: SkillCatalogFailure? = null,
  val query: String = "",
  val searching: Boolean = false,
  val searchResults: List<OfficialSkillSearchMatch> = emptyList(),
  val searchError: SkillCatalogFailure? = null,
)

internal data class SkillDirectoryActions(
  val refresh: () -> Unit,
  val search: (String) -> Unit,
  val loadNextPage: () -> Unit,
)

/** Process-owned, read-only ClawHub Skill directory contract. */
internal class SkillDirectoryFeature(
  val state: StateFlow<SkillDirectoryState>,
  val actions: SkillDirectoryActions,
)

/** Owns read-only Skill discovery. Gateway lifecycle state is deliberately not stored here. */
internal class SkillDirectoryController(
  private val scope: CoroutineScope,
  private val repository: SkillCatalogRepository,
) {
  private val _state = MutableStateFlow(SkillDirectoryState())
  val state: StateFlow<SkillDirectoryState> = _state.asStateFlow()

  private var refreshJob: Job? = null
  private var searchJob: Job? = null
  private var refreshGeneration = 0L
  private var searchGeneration = 0L

  fun refresh() {
    val generation = ++refreshGeneration
    refreshJob?.cancel()
    refreshJob =
      scope.launch {
        _state.update { it.copy(refreshing = true, loadingNextPage = false, error = null) }
        when (val result = repository.recommended()) {
          is SkillCatalogResult.Success -> {
            if (generation != refreshGeneration) return@launch
            _state.update {
              it.copy(
                items = result.value.items,
                nextCursor = result.value.nextCursor,
                refreshing = false,
                error = null,
              )
            }
          }
          is SkillCatalogResult.Failure -> {
            if (generation != refreshGeneration) return@launch
            _state.update { it.copy(refreshing = false, error = result.reason) }
          }
        }
      }
  }

  fun loadNextPage() {
    val cursor = state.value.nextCursor ?: return
    if (state.value.refreshing || state.value.loadingNextPage) return
    val generation = refreshGeneration
    refreshJob =
      scope.launch {
        _state.update { it.copy(loadingNextPage = true, error = null) }
        when (val result = repository.recommended(cursor = cursor)) {
          is SkillCatalogResult.Success -> {
            if (generation != refreshGeneration) return@launch
            _state.update { current ->
              current.copy(
                items = (current.items + result.value.items).distinctBy { it.identity },
                nextCursor = result.value.nextCursor,
                loadingNextPage = false,
                error = null,
              )
            }
          }
          is SkillCatalogResult.Failure -> {
            if (generation != refreshGeneration) return@launch
            _state.update { it.copy(loadingNextPage = false, error = result.reason) }
          }
        }
      }
  }

  fun search(query: String) {
    val normalized = query.trim()
    val generation = ++searchGeneration
    searchJob?.cancel()
    if (normalized.isEmpty()) {
      _state.update {
        it.copy(query = "", searching = false, searchResults = emptyList(), searchError = null)
      }
      return
    }
    searchJob =
      scope.launch {
        _state.update {
          it.copy(query = normalized, searching = true, searchResults = emptyList(), searchError = null)
        }
        when (val result = repository.search(normalized)) {
          is SkillCatalogResult.Success -> {
            if (generation != searchGeneration) return@launch
            _state.update { it.copy(searching = false, searchResults = result.value, searchError = null) }
          }
          is SkillCatalogResult.Failure -> {
            if (generation != searchGeneration) return@launch
            _state.update { it.copy(searching = false, searchError = result.reason) }
          }
        }
      }
  }
}
