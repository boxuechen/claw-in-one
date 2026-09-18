package ai.openclaw.app.vscreen.producer

import ai.openclaw.app.vscreen.VScreenProducer
import ai.openclaw.app.vscreen.VScreenTarget
import ai.openclaw.app.vscreen.VScreenWorkloadEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val ANDROID_VSCREEN_PRODUCER_ID = "claw-in-one-android-vscreen-producer"

internal data class AndroidVScreenWorkload(
  val targetPackage: String,
)

/** Android-specific interpretation stays behind the VScreen producer boundary. */
internal object AndroidVScreenProducer : VScreenProducer {
  override val id: String = ANDROID_VSCREEN_PRODUCER_ID

  override fun accepts(workload: VScreenWorkloadEvent): Boolean = parse(workload) != null

  fun parse(workload: VScreenWorkloadEvent): AndroidVScreenWorkload? {
    if (workload.producerId != id) return null
    val targetPackage =
      workload.producerPayload
        .string("targetPackage")
        ?.takeIf(ANDROID_PACKAGE_PATTERN::matches)
        ?: return null
    if (workload.producerPayload.keys != setOf("targetPackage")) return null
    return AndroidVScreenWorkload(targetPackage)
  }

  private val ANDROID_PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
}

/** Process-local mapping for the one accepted VScreen workload; it is not delivery history. */
internal class AndroidVScreenWorkloadRegistry {
  private val lock = Any()
  private var current: Pair<String, String>? = null

  fun accept(workload: VScreenWorkloadEvent): Boolean {
    val parsed = AndroidVScreenProducer.parse(workload) ?: return false
    synchronized(lock) {
      current = workload.workloadRequestId to parsed.targetPackage
    }
    return true
  }

  fun resolve(target: VScreenTarget): String? {
    if (target.producerId != ANDROID_VSCREEN_PRODUCER_ID) return null
    val workloadRequestId = target.workloadRequestId ?: return null
    return synchronized(lock) { current?.takeIf { it.first == workloadRequestId }?.second }
  }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
