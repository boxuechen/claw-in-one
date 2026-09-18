package ai.openclaw.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Selected-Chat evidence, captured by the selection and delivery owners. */
internal data class ChatRunSnapshot(
  val sessionKey: String,
  val localRunIds: Set<String>,
  val advertisedRunIds: List<String>,
  val hasAdvertisedRun: Boolean,
  val startedAt: Long?,
  val localClockKeys: Map<String, String>,
)

/** Owns telemetry and presentation only; it never admits, cancels, or retries work. */
internal class ChatRunState {
  private data class Telemetry(
    val highestSequence: Long,
    val outputTokens: Long? = null,
    val terminal: Boolean = false,
  )

  private val lock = Any()
  private val telemetry = mutableMapOf<String, Telemetry>()
  private val pendingCountMutable = MutableStateFlow(0)
  val pendingCount: StateFlow<Int> = pendingCountMutable.asStateFlow()
  private val presentationMutable = MutableStateFlow(ChatActiveRunPresentation())
  val presentation: StateFlow<ChatActiveRunPresentation> = presentationMutable.asStateFlow()

  fun publish(snapshot: ChatRunSnapshot) {
    synchronized(lock) {
      val local = snapshot.localRunIds.filterTo(mutableSetOf()) { telemetry[it]?.terminal != true }
      val advertised = snapshot.advertisedRunIds.filter { telemetry[it]?.terminal != true }
      val selected = resolvePreferredActiveRunId(local, advertised)
      val activeCount =
        resolveSelectedActiveRunCount(
          localRunIds = local,
          advertisedRunIds = advertised,
          hasAdvertisedRun =
            snapshot.hasAdvertisedRun &&
              (snapshot.advertisedRunIds.isEmpty() || advertised.isNotEmpty()),
        )
      val clockKey =
        when {
          selected != null && selected in local -> snapshot.localClockKeys[selected] ?: selected
          selected != null -> selected
          activeCount > 0 -> snapshot.startedAt?.let { "${snapshot.sessionKey}:active:$it" } ?: "${snapshot.sessionKey}:active"
          else -> null
        }
      pendingCountMutable.value = snapshot.localRunIds.size
      presentationMutable.value =
        ChatActiveRunPresentation(
          count = activeCount,
          runId = selected,
          clockKey = clockKey,
          outputTokens = selected?.let { telemetry[it]?.outputTokens },
        )
    }
  }

  fun prune(ownedRunIds: Set<String>) {
    synchronized(lock) { telemetry.keys.retainAll(ownedRunIds) }
  }

  fun clearUnownedNonterminal(
    runId: String,
    advertised: Boolean,
  ) {
    synchronized(lock) {
      if (!advertised && telemetry[runId]?.terminal != true) telemetry.remove(runId)
    }
  }

  fun clear() {
    synchronized(lock) { telemetry.clear() }
  }

  fun recordUsage(
    runId: String,
    sequence: Long,
    outputTokens: Long,
  ): Boolean =
    synchronized(lock) {
      val current = telemetry[runId]
      if (current?.terminal == true || sequence <= (current?.highestSequence ?: 0L)) return@synchronized false
      val nextOutputTokens = maxOf(outputTokens, current?.outputTokens ?: 0L)
      telemetry[runId] = Telemetry(highestSequence = sequence, outputTokens = nextOutputTokens)
      nextOutputTokens != current?.outputTokens
    }

  fun applyLifecycle(
    runId: String,
    sequence: Long,
    terminal: Boolean,
  ): Boolean =
    synchronized(lock) {
      val current = telemetry[runId]
      if (current?.terminal == true || sequence <= (current?.highestSequence ?: 0L)) return@synchronized false
      telemetry[runId] = Telemetry(sequence, current?.outputTokens, terminal)
      true
    }

  fun retire(runId: String) {
    synchronized(lock) {
      val current = telemetry[runId]
      telemetry[runId] = Telemetry(current?.highestSequence ?: 0L, current?.outputTokens, terminal = true)
    }
  }

  fun invalidateIncomplete() {
    synchronized(lock) {
      telemetry.toMap().forEach { (runId, state) ->
        if (!state.terminal && state.outputTokens != null) telemetry[runId] = state.copy(outputTokens = null)
      }
    }
  }

  fun transfer(
    oldRunId: String,
    newRunId: String,
  ) {
    synchronized(lock) {
      val old = telemetry.remove(oldRunId) ?: return@synchronized
      val existing = telemetry[newRunId]
      telemetry[newRunId] =
        if (existing == null) {
          old
        } else {
          Telemetry(
            highestSequence = maxOf(old.highestSequence, existing.highestSequence),
            outputTokens = listOfNotNull(old.outputTokens, existing.outputTokens).maxOrNull(),
            terminal = old.terminal || existing.terminal,
          )
        }
    }
  }
}

internal fun resolvePreferredActiveRunId(
  localRunIds: Collection<String>,
  advertisedRunIds: List<String>,
): String? =
  advertisedRunIds.firstOrNull(localRunIds::contains)
    ?: localRunIds.minOrNull()
    ?: advertisedRunIds.firstOrNull()

internal fun resolveSelectedActiveRunCount(
  localRunIds: Collection<String>,
  advertisedRunIds: Collection<String>,
  hasAdvertisedRun: Boolean,
): Int =
  maxOf(
    buildSet {
      addAll(localRunIds)
      addAll(advertisedRunIds)
    }.size,
    if (hasAdvertisedRun) 1 else 0,
  )
