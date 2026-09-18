package ai.openclaw.app.appdelivery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Durable installed result, not an execution receipt or authority to run AI actions. */
@Serializable
internal data class AndroidAppResult(
  val gatewayId: String,
  val sessionKey: String,
  val sessionId: String,
  val runId: String,
  val toolCallId: String,
  val packageName: String,
  val versionCode: Long,
  val sha256: String,
  val displayName: String = packageName,
) {
  val id: String get() = "$gatewayId:$sessionId:$toolCallId"
}

internal data class AndroidAppResultsState(
  val results: List<AndroidAppResult> = emptyList(),
  val openingId: String? = null,
  val errorId: String? = null,
  val error: String? = null,
)

internal class AndroidAppResultsFeature(
  val state: kotlinx.coroutines.flow.StateFlow<AndroidAppResultsState>,
  val open: (AndroidAppResult) -> Unit,
)

/** Receives only canonical, native-validated install events; model prose never enters this store. */
internal class AndroidAppResultsController(
  private val scope: CoroutineScope,
  initial: List<AndroidAppResult>,
  private val save: (List<AndroidAppResult>) -> Unit,
  private val verifyInstalled: suspend (AndroidAppResult) -> Boolean,
  private val handoffAndOpen: suspend (AndroidAppResult) -> Boolean,
) {
  private val lock = Any()
  private val mutableState = MutableStateFlow(AndroidAppResultsState(initial.takeLast(50)))
  val feature = AndroidAppResultsFeature(mutableState.asStateFlow(), ::open)

  fun record(
    gatewayId: String,
    install: AndroidAppInstallEvent,
    displayName: (String) -> String = { it },
  ) {
    val result =
      AndroidAppResult(
        gatewayId = gatewayId,
        sessionKey = install.sessionKey,
        sessionId = install.sessionId,
        runId = install.runId,
        toolCallId = install.toolCallId,
        packageName = install.packageName,
        versionCode = install.versionCode,
        sha256 = install.sha256,
        displayName = displayName(install.packageName),
      )
    synchronized(lock) {
      val previous = mutableState.value
      val results = (previous.results.filterNot { it.gatewayId == gatewayId && it.sessionId == result.sessionId && it.packageName == result.packageName } + result).takeLast(50)
      save(results)
      mutableState.value = previous.copy(results = results, errorId = null, error = null)
    }
  }

  private fun open(result: AndroidAppResult) {
    synchronized(lock) {
      val previous = mutableState.value
      if (previous.openingId != null || result !in previous.results) return
      mutableState.value = previous.copy(openingId = result.id, errorId = null, error = null)
    }
    scope.launch {
      var error: String? = null
      try {
        if (!verifyInstalled(result)) {
          error = "This app changed or is no longer installed. Ask Chat to verify the latest build."
        } else if (result !in mutableState.value.results || !handoffAndOpen(result)) {
          error = "The phone handoff is busy. Finish the active handoff, then open the app."
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        error = "Could not open the app. Check the installed app and try again."
      } finally {
        synchronized(lock) {
          mutableState.value = mutableState.value.copy(openingId = null, errorId = result.id.takeIf { error != null }, error = error)
        }
      }
    }
  }
}
