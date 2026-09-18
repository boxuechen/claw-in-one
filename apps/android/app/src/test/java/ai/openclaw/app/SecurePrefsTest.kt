package ai.openclaw.app

import ai.openclaw.app.gateway.LocalGatewayPairing
import ai.openclaw.app.onboarding.OnboardingReceipt
import ai.openclaw.app.onboarding.RequiredSetupEvidence
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SecurePrefsTest {
  private fun testPrefs(context: android.app.Application): SecurePrefs =
    SecurePrefs(
      context,
      context.getSharedPreferences("secure-prefs-test-${UUID.randomUUID()}", Context.MODE_PRIVATE),
    )

  @Test
  fun onboardingUsesOnlyTheCurrentStructuredReceipt() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs
      .edit()
      .clear()
      .putBoolean("first_run.completed.v1", true)
      .putString("onboarding.receipt.v2", "{\"schemaVersion\":2}")
      .putString("onboarding.receipt.v4", "{\"schemaVersion\":8}")
      .commit()
    val securePrefs = context.getSharedPreferences("secure-prefs-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    val prefs = SecurePrefs(context, securePrefs)

    assertNull(prefs.onboardingReceipt.value)

    val receipt =
      OnboardingReceipt.create(
        evidence =
          RequiredSetupEvidence(
            supervisorId = "b".repeat(32),
            planId = "a".repeat(32),
            eventSequence = 8,
          ),
        completedAtEpochSeconds = 1_788_400_000,
        completionId = "11111111-1111-4111-8111-111111111111",
      )
    prefs.completeOnboarding(receipt)

    assertEquals(receipt, SecurePrefs(context, securePrefs).onboardingReceipt.value)
    prefs.clearOnboarding()
    assertNull(SecurePrefs(context, securePrefs).onboardingReceipt.value)
  }

  @Test
  fun androidUseConsentRequiresExplicitGrantAndPersistsUntilDisabled() {
    val context = RuntimeEnvironment.getApplication()
    context
      .getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    val prefs = testPrefs(context)
    assertFalse(prefs.androidUseConsent.value)
    prefs.setAndroidUseConsent(true)
    assertEquals(true, testPrefs(context).androidUseConsent.value)
    prefs.setAndroidUseConsent(false)
    assertFalse(testPrefs(context).androidUseConsent.value)
  }

  @Test
  fun appearanceThemeMode_defaultsDarkForExistingInstalls() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val prefs = testPrefs(context)

    assertEquals(AppearanceThemeMode.Dark, prefs.appearanceThemeMode.value)
    assertFalse(plainPrefs.contains("appearance.themeMode"))
  }

  @Test
  fun setAppearanceThemeMode_persistsSelectedMode() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val securePrefs = context.getSharedPreferences("secure-prefs-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    val prefs = SecurePrefs(context, securePrefs)

    prefs.setAppearanceThemeMode(AppearanceThemeMode.Light)

    assertEquals(AppearanceThemeMode.Light, prefs.appearanceThemeMode.value)
    assertEquals("light", plainPrefs.getString("appearance.themeMode", null))
    assertEquals(AppearanceThemeMode.Light, SecurePrefs(context, securePrefs).appearanceThemeMode.value)
  }

  @Test
  fun gatewayCredentials_belongOnlyToTheInstalledPairing() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)
    prefs.localGatewayPairing.replace(
      localPairing(
        credentials = GatewayCredentials(token = "shared-token", bootstrapToken = "bootstrap-token"),
      ),
    )

    prefs.saveGatewayCredentials("gateway-b", password = "password-token")

    assertEquals(GatewayCredentials(token = "shared-token", bootstrapToken = "bootstrap-token"), prefs.loadGatewayCredentials("gateway-a"))
    assertEquals(GatewayCredentials(), prefs.loadGatewayCredentials("gateway-b"))
  }

  @Test
  fun clearGatewayCredentials_clearsTheInstalledPairingOnly() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test.clear", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)
    prefs.localGatewayPairing.replace(
      localPairing(
        credentials = GatewayCredentials(token = "shared-token", bootstrapToken = "bootstrap-token"),
      ),
    )

    prefs.clearGatewayCredentials("gateway-a")

    assertEquals(GatewayCredentials(), prefs.loadGatewayCredentials("gateway-a"))
    assertEquals(GatewayCredentials(), prefs.loadGatewayCredentials("gateway-b"))
  }

  @Test
  fun modelFavorites_togglePersistsPinOrder() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val prefs = SecurePrefs(context)

    prefs.toggleModelFavorite(" anthropic/claude-opus-4 ")
    prefs.toggleModelFavorite("openai/gpt-5")
    prefs.toggleModelFavorite("anthropic/claude-opus-4")
    prefs.toggleModelFavorite("anthropic/claude-opus-4")
    prefs.toggleModelFavorite("  ")

    assertEquals(
      listOf("openai/gpt-5", "anthropic/claude-opus-4"),
      prefs.modelFavorites.value,
    )
    assertEquals(prefs.modelFavorites.value, SecurePrefs(context).modelFavorites.value)
  }

  @Test
  fun modelRecents_dedupesToFrontAndCapsAtFive() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val prefs = SecurePrefs(context)

    (1..6).forEach { index -> prefs.recordModelRecent("provider/model-$index") }
    prefs.recordModelRecent(" provider/model-3 ")
    prefs.recordModelRecent(" ")

    assertEquals(
      listOf(
        "provider/model-3",
        "provider/model-6",
        "provider/model-5",
        "provider/model-4",
        "provider/model-2",
      ),
      prefs.modelRecents.value,
    )
    assertEquals(prefs.modelRecents.value, SecurePrefs(context).modelRecents.value)
  }

  @Test
  fun chatConversationSelection_roundTripsForTheInstalledPairing() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val securePrefs = context.getSharedPreferences("secure-prefs-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)
    prefs.localGatewayPairing.replace(localPairing())

    prefs.setChatConversationSelection("gateway-a", " agent:main:dashboard:first ", " main ")
    prefs.setChatConversationSelection("gateway-b", "agent:ops:dashboard:second", null)

    val reloaded = SecurePrefs(context, securePrefsOverride = securePrefs)
    assertEquals(
      ChatConversationSelection("agent:main:dashboard:first", "main"),
      reloaded.loadChatConversationSelection("gateway-a"),
    )
    assertNull(reloaded.loadChatConversationSelection("gateway-b"))

    reloaded.clearChatConversationSelection("gateway-a")
    assertNull(reloaded.loadChatConversationSelection("gateway-a"))
    assertNull(reloaded.loadChatConversationSelection("gateway-b"))
  }

  private fun localPairing(
    credentials: GatewayCredentials = GatewayCredentials(),
  ): LocalGatewayPairing =
    LocalGatewayPairing(
      stableId = "gateway-a",
      host = "127.0.0.1",
      port = 18789,
      tls = false,
      credentials = credentials,
    )
}
