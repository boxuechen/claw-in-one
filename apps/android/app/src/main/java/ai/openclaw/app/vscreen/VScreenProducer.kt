package ai.openclaw.app.vscreen

/**
 * Native adapter boundary for producer-owned workload metadata.
 */
internal interface VScreenProducer {
  val id: String

  fun accepts(workload: VScreenWorkloadEvent): Boolean
}

internal class VScreenProducerRegistry(
  producers: List<VScreenProducer>,
) {
  private val producersById = producers.associateBy(VScreenProducer::id)

  init {
    require(producers.all { it.id.matches(Regex("[a-z0-9][a-z0-9.-]{0,127}")) }) { "Invalid VScreen producer ID" }
    require(producersById.size == producers.size) { "VScreen producer IDs must be unique" }
  }

  fun resolve(workload: VScreenWorkloadEvent): VScreenProducer? = producersById[workload.producerId]?.takeIf { it.accepts(workload) }
}
