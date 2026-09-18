package ai.openclaw.app.runtime

import android.content.Context
import androidx.core.content.edit

/** Persists only opaque runtime generations; no Supervisor auth or Gateway setup data lives here. */
internal class RuntimeAcknowledgementRepository(
  context: Context,
) : RuntimeAcknowledgementStore {
  private val preferences =
    context.applicationContext.getSharedPreferences("runtime_startup_v1", Context.MODE_PRIVATE)

  override fun load(): RuntimeGeneration? {
    val bootId = preferences.getString(KEY_BOOT_ID, null) ?: return null
    val gatewayGeneration = preferences.getString(KEY_GATEWAY_GENERATION, null) ?: return null
    if (!bootId.isRuntimeId() || !gatewayGeneration.isRuntimeId()) return null
    return RuntimeGeneration(bootId, gatewayGeneration)
  }

  override fun save(generation: RuntimeGeneration) {
    preferences.edit {
      putString(KEY_BOOT_ID, generation.supervisorBootId)
      putString(KEY_GATEWAY_GENERATION, generation.gatewayGeneration)
    }
  }

  private fun String.isRuntimeId(): Boolean = length == 32 && all { it in '0'..'9' || it in 'a'..'f' }

  private companion object {
    const val KEY_BOOT_ID = "supervisor_boot_id"
    const val KEY_GATEWAY_GENERATION = "gateway_generation"
  }
}
