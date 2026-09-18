package ai.openclaw.app.plugin.catalog

import ai.openclaw.app.clawhub.ClawHubArtworkPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PluginDirectoryState(
  val items: List<OfficialPluginSummary> = emptyList(),
  val nextCursor: String? = null,
  val refreshing: Boolean = false,
  val loadingNextPage: Boolean = false,
  val error: PluginCatalogFailure? = null,
  val query: String = "",
  val searching: Boolean = false,
  val searchResults: List<OfficialPluginSearchMatch> = emptyList(),
  val searchError: PluginCatalogFailure? = null,
)

internal data class PluginDirectoryActions(
  val refresh: () -> Unit,
  val search: (String) -> Unit,
  val loadNextPage: () -> Unit,
)

/** Process-owned, read-only ClawHub Plugin directory contract. */
internal class PluginDirectoryFeature(
  val state: StateFlow<PluginDirectoryState>,
  val actions: PluginDirectoryActions,
  val loadArtwork: suspend (String) -> ClawHubArtworkPayload?,
)

/** Owns read-only Plugin discovery. Gateway lifecycle state is deliberately not stored here. */
internal class PluginDirectoryController(
  private val scope: CoroutineScope,
  private val repository: PluginCatalogRepository,
) {
  private val _state = MutableStateFlow(PluginDirectoryState())
  val state: StateFlow<PluginDirectoryState> = _state.asStateFlow()

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
          is PluginCatalogResult.Success -> {
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
          is PluginCatalogResult.Failure -> {
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
          is PluginCatalogResult.Success -> {
            if (generation != refreshGeneration) return@launch
            _state.update { current ->
              current.copy(
                items = (current.items + result.value.items).distinctBy { it.packageName },
                nextCursor = result.value.nextCursor,
                loadingNextPage = false,
                error = null,
              )
            }
          }
          is PluginCatalogResult.Failure -> {
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
          is PluginCatalogResult.Success -> {
            if (generation != searchGeneration) return@launch
            _state.update { it.copy(searching = false, searchResults = result.value, searchError = null) }
          }
          is PluginCatalogResult.Failure -> {
            if (generation != searchGeneration) return@launch
            _state.update { it.copy(searching = false, searchError = result.reason) }
          }
        }
      }
  }
}
