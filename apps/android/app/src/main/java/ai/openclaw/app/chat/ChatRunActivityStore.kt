package ai.openclaw.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns bounded, ephemeral commentary for the selected Chat's authoritative active run. */
internal class ChatRunActivityStore(
  private val commentaryLimit: Int = 5,
) {
  private val lock = Any()
  private val mutableActivity = MutableStateFlow<ChatRunActivity?>(null)
  val activity = mutableActivity.asStateFlow()

  init {
    require(commentaryLimit > 0)
  }

  fun accept(segment: ChatCommentarySegment): Boolean =
    synchronized(lock) {
      val runId = segment.runId?.trim()?.takeIf(String::isNotEmpty) ?: return@synchronized false
      val current = mutableActivity.value?.takeIf { it.runId == runId }
      val rows = merge(current?.commentary.orEmpty(), segment) ?: return@synchronized false
      mutableActivity.value = ChatRunActivity(runId, rows)
      true
    }

  /** Rebuilds only activity that carries the exact authoritative in-flight run identity. */
  fun restore(
    runId: String,
    commentary: List<ChatCommentarySegment>,
  ) = synchronized(lock) {
    val normalizedRunId = runId.trim().takeIf(String::isNotEmpty) ?: return@synchronized
    val rows =
      commentary
        .filter { it.runId == normalizedRunId }
        .fold(emptyList<ChatCommentarySegment>()) { current, segment -> merge(current, segment) ?: current }
    mutableActivity.value = ChatRunActivity(normalizedRunId, rows)
  }

  fun clear(runId: String? = null) =
    synchronized(lock) {
      if (runId == null || mutableActivity.value?.runId == runId) mutableActivity.value = null
    }

  private fun merge(
    current: List<ChatCommentarySegment>,
    segment: ChatCommentarySegment,
  ): List<ChatCommentarySegment>? {
    val rows = current.toMutableList()
    val existingIndex = rows.indexOfFirst { it.itemId == segment.itemId }
    if (existingIndex >= 0) {
      val existing = rows[existingIndex]
      if (existing.sequence != null && segment.sequence != null && segment.sequence < existing.sequence) return null
      rows[existingIndex] = segment
    } else {
      rows += segment
    }
    return rows.takeLast(commentaryLimit)
  }
}
