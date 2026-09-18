@file:Suppress("DEPRECATION")

package ai.openclaw.app

import ai.openclaw.app.gateway.LocalGatewayPairingStore
import ai.openclaw.app.onboarding.OnboardingReceipt
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

@Serializable
data class GatewayCredentials(
  val token: String? = null,
  val bootstrapToken: String? = null,
  val password: String? = null,
) {
  internal fun normalized(): GatewayCredentials =
    copy(
      token = token?.trim()?.takeIf { it.isNotEmpty() },
      bootstrapToken = bootstrapToken?.trim()?.takeIf { it.isNotEmpty() },
      password = password?.trim()?.takeIf { it.isNotEmpty() },
    )
}

@Serializable
internal data class ChatConversationSelection(
  val sessionKey: String,
  val ownerAgentId: String? = null,
) {
  fun normalized(): ChatConversationSelection? {
    val normalizedSessionKey = sessionKey.trim().takeIf { it.isNotEmpty() } ?: return null
    return copy(
      sessionKey = normalizedSessionKey,
      ownerAgentId = ownerAgentId?.trim()?.takeIf { it.isNotEmpty() },
    )
  }
}

/**
 * Reactive settings facade for Android node preferences and encrypted gateway credentials.
 */
class SecurePrefs(
  context: Context,
  private val securePrefsOverride: SharedPreferences? = null,
) {
  companion object {
    private const val displayNameKey = "node.displayName"
    private const val plainPrefsName = "openclaw.node"
    private const val securePrefsName = "openclaw.node.secure"
    private const val appearanceThemeModeKey = "appearance.themeMode"
    private const val chatModelFavoritesKey = "chat.modelFavorites"
    private const val chatModelRecentsKey = "chat.modelRecents"
    private const val chatConversationSelectionKey = "chat.conversationSelection.local"
    private const val onboardingReceiptKey = "onboarding.receipt.v5"
    private const val maxChatModelRecents = 5
  }

  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = true }

  // Non-secret UI/runtime preferences stay readable for migration and backup behavior.
  private val plainPrefs: SharedPreferences =
    appContext.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE)

  // Gateway credentials and arbitrary secret strings are isolated behind EncryptedSharedPreferences.
  private val masterKey by lazy {
    MasterKey
      .Builder(appContext)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
  }
  private val securePrefs: SharedPreferences by lazy { securePrefsOverride ?: createSecurePrefs(appContext, securePrefsName) }

  private val _instanceId = MutableStateFlow(loadOrCreateInstanceId())
  val instanceId: StateFlow<String> = _instanceId

  // Lazy so plain-preference reads never touch the encrypted store in Robolectric.
  internal val localGatewayPairing: LocalGatewayPairingStore by lazy { LocalGatewayPairingStore(this) }

  private val _displayName =
    MutableStateFlow(loadOrMigrateDisplayName(context = context))
  val displayName: StateFlow<String> = _displayName

  private val _preventSleep = MutableStateFlow(plainPrefs.getBoolean("screen.preventSleep", true))
  val preventSleep: StateFlow<Boolean> = _preventSleep

  private val _androidUseConsent = MutableStateFlow(plainPrefs.getBoolean("android_use.consent.v1", false))
  val androidUseConsent: StateFlow<Boolean> = _androidUseConsent

  fun setAndroidUseConsent(enabled: Boolean) {
    plainPrefs.edit { putBoolean("android_use.consent.v1", enabled) }
    _androidUseConsent.value = enabled
  }

  private val onboardingReceiptMutable = MutableStateFlow(loadOnboardingReceipt())
  internal val onboardingReceipt: StateFlow<OnboardingReceipt?> = onboardingReceiptMutable

  private val _appearanceThemeMode =
    MutableStateFlow(AppearanceThemeMode.fromRawValue(plainPrefs.getString(appearanceThemeModeKey, null)))
  val appearanceThemeMode: StateFlow<AppearanceThemeMode> = _appearanceThemeMode

  private val _modelFavorites = MutableStateFlow(loadChatModelRefs(chatModelFavoritesKey))
  val modelFavorites: StateFlow<List<String>> = _modelFavorites

  private val _modelRecents = MutableStateFlow(loadChatModelRefs(chatModelRecentsKey))
  val modelRecents: StateFlow<List<String>> = _modelRecents

  fun setDisplayName(value: String) {
    val trimmed = value.trim()
    plainPrefs.edit { putString(displayNameKey, trimmed) }
    _displayName.value = trimmed
  }

  fun setPreventSleep(value: Boolean) {
    plainPrefs.edit { putBoolean("screen.preventSleep", value) }
    _preventSleep.value = value
  }

  internal fun completeOnboarding(receipt: OnboardingReceipt) {
    val normalized = requireNotNull(receipt.normalized())
    plainPrefs.edit { putString(onboardingReceiptKey, json.encodeToString(normalized)) }
    onboardingReceiptMutable.value = normalized
  }

  internal fun clearOnboarding() {
    plainPrefs.edit { remove(onboardingReceiptKey) }
    onboardingReceiptMutable.value = null
  }

  private fun loadOnboardingReceipt(): OnboardingReceipt? =
    plainPrefs
      .getString(onboardingReceiptKey, null)
      ?.let { encoded -> runCatching { json.decodeFromString<OnboardingReceipt>(encoded).normalized() }.getOrNull() }

  fun loadGatewayCredentials(stableId: String): GatewayCredentials {
    val pairing = localGatewayPairing.pairing.value ?: return GatewayCredentials()
    return pairing.credentials.takeIf { pairing.stableId == stableId.trim() } ?: GatewayCredentials()
  }

  fun saveGatewayCredentials(
    stableId: String,
    credentials: GatewayCredentials,
  ) {
    localGatewayPairing.updateCredentials(stableId, credentials)
  }

  fun saveGatewayCredentials(
    stableId: String,
    token: String? = null,
    bootstrapToken: String? = null,
    password: String? = null,
  ) {
    saveGatewayCredentials(stableId, GatewayCredentials(token, bootstrapToken, password))
  }

  fun clearGatewayCredentials(stableId: String) {
    localGatewayPairing.updateCredentials(stableId, GatewayCredentials())
  }

  /** Restores the last product conversation independently for each paired gateway. */
  internal fun loadChatConversationSelection(stableId: String): ChatConversationSelection? {
    if (localGatewayPairing.stableId.value != stableId.trim()) return null
    val raw = plainPrefs.getString(chatConversationSelectionKey, null) ?: return null
    return runCatching { json.decodeFromString<ChatConversationSelection>(raw).normalized() }.getOrNull()
  }

  /** Persists product navigation state only; conversation content remains Gateway-owned. */
  internal fun setChatConversationSelection(
    stableId: String,
    sessionKey: String,
    ownerAgentId: String?,
  ) {
    val selection = ChatConversationSelection(sessionKey, ownerAgentId).normalized()
    if (localGatewayPairing.stableId.value != stableId.trim()) return
    plainPrefs.edit {
      if (selection == null) {
        remove(chatConversationSelectionKey)
      } else {
        putString(chatConversationSelectionKey, json.encodeToString(selection))
      }
    }
  }

  internal fun clearChatConversationSelection(stableId: String) {
    if (localGatewayPairing.stableId.value == stableId.trim()) {
      plainPrefs.edit { remove(chatConversationSelectionKey) }
    }
  }

  fun getString(key: String): String? = securePrefs.getString(key, null)

  fun putString(
    key: String,
    value: String,
  ) {
    securePrefs.edit { putString(key, value) }
  }

  // KTX edit(commit = true) discards commit's Boolean; durable pairing/identity stores fail closed on it.
  @Suppress("UseKtx")
  internal fun putStringSynchronously(
    key: String,
    value: String,
  ): Boolean = securePrefs.edit().putString(key, value).commit()

  /** Durably commits one logical secret record without exposing its storage backend. */
  @Suppress("UseKtx")
  internal fun commitSecureStrings(values: Map<String, String>): Boolean {
    val editor = securePrefs.edit()
    for ((key, value) in values) editor.putString(key, value)
    return editor.commit()
  }

  fun remove(key: String) {
    securePrefs.edit { remove(key) }
  }

  @Suppress("UseKtx")
  internal fun removeSynchronously(key: String): Boolean = securePrefs.edit().remove(key).commit()

  private fun createSecurePrefs(
    context: Context,
    name: String,
  ): SharedPreferences =
    EncryptedSharedPreferences.create(
      context,
      name,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

  private fun loadOrCreateInstanceId(): String {
    val existing = plainPrefs.getString("node.instanceId", null)?.trim()
    if (!existing.isNullOrBlank()) return existing
    // Instance id is not secret; it scopes local credentials and survives display-name changes.
    val fresh = UUID.randomUUID().toString()
    plainPrefs.edit { putString("node.instanceId", fresh) }
    return fresh
  }

  private fun loadOrMigrateDisplayName(context: Context): String {
    val existing = plainPrefs.getString(displayNameKey, null)?.trim().orEmpty()
    if (existing.isNotEmpty() && existing != "Android Node") return existing

    // Replace the historical generic name with a device-specific default once.
    val candidate = DeviceNames.bestDefaultNodeName(context).trim()
    val resolved = candidate.ifEmpty { "Android Node" }

    plainPrefs.edit { putString(displayNameKey, resolved) }
    return resolved
  }

  fun setAppearanceThemeMode(mode: AppearanceThemeMode) {
    plainPrefs.edit { putString(appearanceThemeModeKey, mode.rawValue) }
    _appearanceThemeMode.value = mode
  }

  fun toggleModelFavorite(ref: String) {
    val trimmed = ref.trim()
    if (trimmed.isEmpty()) return
    val next =
      if (trimmed in _modelFavorites.value) {
        _modelFavorites.value - trimmed
      } else {
        _modelFavorites.value + trimmed
      }
    persistChatModelRefs(chatModelFavoritesKey, next)
    _modelFavorites.value = next
  }

  fun recordModelRecent(ref: String) {
    val trimmed = ref.trim()
    if (trimmed.isEmpty()) return
    val next = (listOf(trimmed) + _modelRecents.value.filterNot { it == trimmed }).take(maxChatModelRecents)
    persistChatModelRefs(chatModelRecentsKey, next)
    _modelRecents.value = next
  }

  private fun persistChatModelRefs(
    key: String,
    refs: List<String>,
  ) {
    val encoded = JsonArray(refs.map(::JsonPrimitive)).toString()
    plainPrefs.edit { putString(key, encoded) }
  }

  private fun loadChatModelRefs(key: String): List<String> {
    val raw = plainPrefs.getString(key, null)?.trim()
    if (raw.isNullOrEmpty()) return emptyList()
    return try {
      val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
      array
        .mapNotNull { item ->
          when (item) {
            is JsonNull -> null
            is JsonPrimitive -> item.content.trim().takeIf { it.isNotEmpty() }
            else -> null
          }
        }.distinct()
    } catch (_: Throwable) {
      emptyList()
    }
  }
}
