package ai.openclaw.app.webdelivery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal const val WEB_PROJECT_RESULT_METHOD = "claw.web.result.open"

@Serializable
internal data class WebProjectResult(
  val gatewayId: String,
  val sessionKey: String,
  val sessionId: String,
  val runId: String,
  val toolCallId: String,
  val resultId: String,
  val projectId: String,
  val generation: Int,
  val targetId: String,
  val url: String,
  val appName: String,
) {
  val id: String get() = "$gatewayId:$projectId:$resultId"
}

internal data class WebProjectResultsState(
  val results: List<WebProjectResult> = emptyList(),
  val openingId: String? = null,
  val errorId: String? = null,
  val error: String? = null,
)

internal class WebProjectResultsFeature(
  val state: StateFlow<WebProjectResultsState>,
  val open: (WebProjectResult) -> Unit,
)

internal class WebProjectResultsController(
  private val scope: CoroutineScope,
  initial: List<WebProjectResult>,
  private val save: (List<WebProjectResult>) -> Unit,
  private val resolveAndOpen: suspend (WebProjectResult) -> Boolean,
) {
  private val lock = Any()
  private val mutableState = MutableStateFlow(WebProjectResultsState(results = initial.takeLast(50)))
  val feature = WebProjectResultsFeature(mutableState.asStateFlow(), ::open)

  fun record(
    gatewayId: String,
    event: WebProjectGatewayEvent,
  ) {
    if (event is WebProjectGatewayEvent.Stopped) {
      synchronized(lock) {
        val previous = mutableState.value
        val results =
          previous.results.filterNot {
            it.gatewayId == gatewayId &&
              it.projectId == event.projectId &&
              it.generation == event.generation
          }
        if (results == previous.results) return
        save(results)
        val retainedIds = results.mapTo(mutableSetOf()) { it.id }
        mutableState.value =
          previous.copy(
            results = results,
            openingId = previous.openingId?.takeIf(retainedIds::contains),
            errorId = previous.errorId?.takeIf(retainedIds::contains),
            error = previous.error.takeIf { previous.errorId?.let(retainedIds::contains) == true },
          )
      }
      return
    }
    check(event is WebProjectGatewayEvent.Ready)
    val result =
      WebProjectResult(
        gatewayId = gatewayId,
        sessionKey = event.sessionKey,
        sessionId = event.sessionId,
        runId = event.runId,
        toolCallId = event.toolCallId,
        resultId = event.resultId,
        projectId = event.projectId,
        generation = event.generation,
        targetId = event.targetId,
        url = event.url,
        appName = event.appName,
      )
    synchronized(lock) {
      val previous = mutableState.value
      val results =
        (previous.results.filterNot { it.gatewayId == gatewayId && it.projectId == result.projectId } + result)
          .takeLast(50)
      save(results)
      mutableState.value = previous.copy(results = results, errorId = null, error = null)
    }
  }

  private fun open(result: WebProjectResult) {
    synchronized(lock) {
      val previous = mutableState.value
      if (previous.openingId != null || result !in previous.results) return
      mutableState.value = previous.copy(openingId = result.id, errorId = null, error = null)
    }
    scope.launch {
      var error: String? = null
      try {
        if (result !in mutableState.value.results || !resolveAndOpen(result)) {
          error = "This Web app is no longer running. Ask Chat to serve the latest build."
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        error = "Could not open the Web app in Android Chrome. Check the connection and try again."
      } finally {
        synchronized(lock) {
          mutableState.value =
            mutableState.value.copy(
              openingId = null,
              errorId = result.id.takeIf { error != null },
              error = error,
            )
        }
      }
    }
  }
}
