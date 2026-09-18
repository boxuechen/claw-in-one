package ai.openclaw.app.ai

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class AiSetupWizardPurpose { Auth, Activation }

@Serializable
internal data class AiSetupSessionCheckpoint(
  val gatewayStableId: String,
  val sessionId: String,
  val label: String,
  val purpose: AiSetupWizardPurpose,
  val providerId: String? = null,
  val targetModelRef: String? = null,
) {
  fun normalized(): AiSetupSessionCheckpoint? {
    val gateway = gatewayStableId.trim().takeIf(String::isNotEmpty) ?: return null
    val session = sessionId.trim().takeIf(String::isNotEmpty) ?: return null
    val displayLabel = label.trim().takeIf(String::isNotEmpty) ?: return null
    return copy(
      gatewayStableId = gateway,
      sessionId = session,
      label = displayLabel,
      providerId = providerId?.trim()?.takeIf(String::isNotEmpty),
      targetModelRef = targetModelRef?.trim()?.takeIf(String::isNotEmpty),
    )
  }
}

/** Stores only safe recovery metadata; Provider credentials and device codes stay Gateway-owned. */
internal interface AiSetupSessionStore {
  fun load(): AiSetupSessionCheckpoint?

  fun save(checkpoint: AiSetupSessionCheckpoint): Boolean

  fun clear(sessionId: String? = null)

  data object None : AiSetupSessionStore {
    override fun load(): AiSetupSessionCheckpoint? = null

    override fun save(checkpoint: AiSetupSessionCheckpoint): Boolean = true

    override fun clear(sessionId: String?) = Unit
  }
}

internal class AndroidAiSetupSessionStore(
  context: Context,
) : AiSetupSessionStore {
  private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = false }

  override fun load(): AiSetupSessionCheckpoint? {
    val encoded = preferences.getString(ACTIVE_SESSION_KEY, null) ?: return null
    val checkpoint =
      runCatching { json.decodeFromString<AiSetupSessionCheckpoint>(encoded).normalized() }
        .getOrNull()
    if (checkpoint == null) preferences.edit { remove(ACTIVE_SESSION_KEY) }
    return checkpoint
  }

  override fun save(checkpoint: AiSetupSessionCheckpoint): Boolean {
    val normalized = checkpoint.normalized() ?: return false
    preferences.edit { putString(ACTIVE_SESSION_KEY, json.encodeToString(normalized)) }
    return true
  }

  override fun clear(sessionId: String?) {
    if (sessionId != null && load()?.sessionId != sessionId) return
    preferences.edit { remove(ACTIVE_SESSION_KEY) }
  }

  private companion object {
    const val PREFERENCES_NAME = "clawinone.ai-setup-session.v2"
    const val ACTIVE_SESSION_KEY = "active"
  }
}
