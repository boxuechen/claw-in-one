package ai.openclaw.app.vscreen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Exact App-global display generation available to optional consumers. */
internal data class VScreenTarget(
  val revision: Long,
  val producerId: String,
  val workloadRequestId: String?,
  val attachmentId: String,
  val targetRef: String,
  val targetGeneration: Long,
  val sourceGeneration: Long,
  val displayId: Int,
  val width: Int,
  val height: Int,
  val dpi: Int,
  val rotation: Int,
)

internal data class VScreenTargetCandidate(
  val producerId: String,
  val workloadRequestId: String?,
  val attachmentId: String,
  val targetRef: String,
  val targetGeneration: Long,
  val sourceGeneration: Long,
  val displayId: Int,
  val width: Int,
  val height: Int,
  val dpi: Int,
  val rotation: Int = 0,
)

internal interface VScreenTargetReader {
  val state: StateFlow<VScreenTarget?>

  fun current(): VScreenTarget? = state.value

  suspend fun awaitCurrent(timeoutMs: Long): VScreenTarget? = withTimeoutOrNull(timeoutMs) { state.first { it != null } }

  suspend fun awaitAssignment(
    assignmentId: String,
    timeoutMs: Long,
  ): VScreenTarget? = withTimeoutOrNull(timeoutMs) { state.first { it?.workloadRequestId == assignmentId } }
}

internal interface VScreenTargetWriter {
  fun publish(candidate: VScreenTargetCandidate): VScreenTarget

  fun clear(
    attachmentId: String,
    targetGeneration: Long,
  )
}

/** Process-owned publication point; presentation and workload origins are not stored here. */
internal class VScreenTargetRegistry(
  private val onTargetRetired: (VScreenTarget) -> Unit = {},
) : VScreenTargetReader,
  VScreenTargetWriter {
  private val lock = Any()
  private var revision = 0L
  private var target: VScreenTarget? = null
  private val mutableState = MutableStateFlow<VScreenTarget?>(null)
  override val state: StateFlow<VScreenTarget?> = mutableState

  override fun current(): VScreenTarget? = synchronized(lock) { target }

  override fun publish(candidate: VScreenTargetCandidate): VScreenTarget {
    val (previous, published) =
      synchronized(lock) {
        require(candidate.producerId.matches(PRODUCER_ID_PATTERN)) { "Invalid VScreen producer ID" }
        require(candidate.workloadRequestId == null || candidate.workloadRequestId.matches(REQUEST_ID_PATTERN)) {
          "Invalid VScreen workload request ID"
        }
        require(candidate.attachmentId.matches(ATTACHMENT_ID_PATTERN)) { "Invalid VScreen attachment ID" }
        require(candidate.targetRef.isNotEmpty() && candidate.targetRef.length <= 512) { "Invalid VScreen target reference" }
        require(candidate.targetGeneration > 0 && candidate.sourceGeneration > 0) { "Invalid VScreen generation" }
        require(candidate.displayId > 0) { "VScreen display must not be the main display" }
        require(candidate.width in 1..4096 && candidate.height in 1..4096) { "Invalid VScreen dimensions" }
        require(candidate.dpi in 72..960) { "Invalid VScreen density" }
        require(candidate.rotation in 0..3) { "Invalid VScreen rotation" }
        val previous = target
        val sameBinding =
          previous?.let {
            it.producerId == candidate.producerId &&
              it.workloadRequestId == candidate.workloadRequestId &&
              it.attachmentId == candidate.attachmentId &&
              it.targetRef == candidate.targetRef &&
              it.targetGeneration == candidate.targetGeneration &&
              it.sourceGeneration == candidate.sourceGeneration &&
              it.displayId == candidate.displayId
          } == true
        if (!sameBinding) revision += 1
        val next =
          VScreenTarget(
            revision = revision,
            producerId = candidate.producerId,
            workloadRequestId = candidate.workloadRequestId,
            attachmentId = candidate.attachmentId,
            targetRef = candidate.targetRef,
            targetGeneration = candidate.targetGeneration,
            sourceGeneration = candidate.sourceGeneration,
            displayId = candidate.displayId,
            width = candidate.width,
            height = candidate.height,
            dpi = candidate.dpi,
            rotation = candidate.rotation,
          ).also {
            target = it
            mutableState.value = it
          }
        previous?.takeUnless { sameBinding } to next
      }
    previous?.let(onTargetRetired)
    return published
  }

  override fun clear(
    attachmentId: String,
    targetGeneration: Long,
  ) {
    val retired =
      synchronized(lock) {
        val previous =
          target?.takeIf {
            it.attachmentId == attachmentId && it.targetGeneration == targetGeneration
          } ?: return@synchronized null
        revision += 1
        target = null
        mutableState.value = null
        previous
      }
    retired?.let(onTargetRetired)
  }

  private companion object {
    val PRODUCER_ID_PATTERN = Regex("[a-z0-9][a-z0-9.-]{0,127}")
    val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
    val ATTACHMENT_ID_PATTERN =
      Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
  }
}
