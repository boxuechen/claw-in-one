package ai.openclaw.app.chat

import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.takeUtf16Safe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val SESSION_TITLE_SOURCE_MAX_CHARS = 1_000
private const val SESSION_TITLE_MAX_CHARS = 60
private const val SESSION_TITLE_MIN_SOURCE_CHARS = 12
private const val SESSION_TITLE_PREPARE_DELAY_MS = 1_000L
private const val SESSION_TITLE_PREPARATION_LIMIT = 16

internal data class ChatSessionTitleCandidate(
  val target: SessionPermissionTarget,
  val catalogRevision: Long,
  val message: String,
  val modelRef: String? = null,
)

/** Presentation-facing staging actions; request and result ownership stay in the controller. */
internal class ChatSessionTitlePreparationFeature(
  private val stageAction: (ChatSessionTitleCandidate) -> Unit,
  private val clearAction: (SessionPermissionTarget) -> Unit,
) {
  fun stage(candidate: ChatSessionTitleCandidate) = stageAction(candidate)

  fun clear(target: SessionPermissionTarget) = clearAction(target)
}

/** Owns optional, disposable creation-only title inference for local Session drafts. */
internal class ChatSessionTitlePreparationController(
  private val scope: CoroutineScope,
  private val canPrepare: (SessionPermissionTarget) -> Boolean,
  private val request: suspend (SessionPermissionTarget, String) -> String,
  private val delayMs: Long = SESSION_TITLE_PREPARE_DELAY_MS,
) {
  private data class Preparation(
    val candidate: ChatSessionTitleCandidate,
    var job: Job? = null,
    var title: String? = null,
  )

  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()
  private val preparations = linkedMapOf<SessionPermissionTarget, Preparation>()
  val feature = ChatSessionTitlePreparationFeature(::stage, ::clear)

  fun stage(rawCandidate: ChatSessionTitleCandidate) {
    val candidate = normalize(rawCandidate)
    if (candidate == null || !canPrepare(rawCandidate.target)) {
      clear(rawCandidate.target)
      return
    }
    synchronized(lock) {
      if (preparations[candidate.target]?.candidate == candidate) return
      preparations.remove(candidate.target)?.job?.cancel()
      while (preparations.size >= SESSION_TITLE_PREPARATION_LIMIT) {
        preparations.remove(preparations.keys.firstOrNull() ?: break)?.job?.cancel()
      }
      val preparation = Preparation(candidate)
      preparations[candidate.target] = preparation
      preparation.job =
        scope.launch {
          delay(delayMs)
          val title = prepare(candidate)
          synchronized(lock) {
            if (preparations[candidate.target] === preparation) {
              preparation.job = null
              preparation.title = title
            }
          }
        }
    }
  }

  /** Takes only an already prepared exact title; speculative naming never blocks first Send. */
  fun take(rawCandidate: ChatSessionTitleCandidate): String? {
    val candidate = normalize(rawCandidate) ?: return null
    return synchronized(lock) {
      val preparation = preparations.remove(candidate.target) ?: return@synchronized null
      preparation.job?.cancel()
      preparation.title.takeIf { preparation.candidate == candidate }
    }
  }

  fun clear(target: SessionPermissionTarget) {
    synchronized(lock) { preparations.remove(target)?.job?.cancel() }
  }

  private suspend fun prepare(candidate: ChatSessionTitleCandidate): String? {
    return try {
      val params =
        buildJsonObject {
          put("agentId", JsonPrimitive(candidate.target.agentId))
          put("message", JsonPrimitive(candidate.message))
          candidate.modelRef?.let { put("model", JsonPrimitive(it)) }
        }
      val result = json.parseToJsonElement(request(candidate.target, params.toString())) as? JsonObject ?: return null
      val rawTitle = result["title"]
      if (rawTitle == null || rawTitle == JsonNull) return null
      rawTitle.jsonPrimitive.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= SESSION_TITLE_MAX_CHARS }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Throwable) {
      null
    }
  }

  private fun normalize(candidate: ChatSessionTitleCandidate): ChatSessionTitleCandidate? {
    val message =
      candidate.message
        .trim()
        .takeUtf16Safe(SESSION_TITLE_SOURCE_MAX_CHARS)
        .trimEnd()
    if (message.length < SESSION_TITLE_MIN_SOURCE_CHARS || message.startsWith('/')) return null
    return candidate.copy(
      message = message,
      modelRef = candidate.modelRef?.trim()?.takeIf(String::isNotEmpty),
    )
  }
}
