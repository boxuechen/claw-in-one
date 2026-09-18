package ai.openclaw.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAuthSurfacePolicyTest {
  @Test
  fun openAiSetupUsesAdvertisedDevicePairingWithoutLoopbackOAuthFallback() {
    val detection =
      detection(
        authOptions =
          listOf(
            authOption("openai", AiSetupAuthKind.OAuth, featured = true),
            authOption("openai-device-code", AiSetupAuthKind.DeviceCode),
            authOption("other-oauth", AiSetupAuthKind.OAuth, brandId = "other", featured = true),
          ),
      )

    val result = AndroidAuthSurfacePolicy.setupDetection(detection)

    assertEquals(listOf("openai-device-code", "other-oauth"), result.authOptions.map { it.id })
    assertTrue(result.authOptions.single { it.id == "openai-device-code" }.featured)
    assertFalse(result.authOptions.single { it.id == "other-oauth" }.featured)
  }

  @Test
  fun installCustomAndNonKeyManualChoicesAreNotAndroidProductSurfaces() {
    val result =
      AndroidAuthSurfacePolicy.setupDetection(
        detection(
          authOptions =
            listOf(
              authOption("safe", AiSetupAuthKind.OAuth, brandId = "other"),
              authOption("install", AiSetupAuthKind.Install, brandId = "other"),
              authOption("custom", AiSetupAuthKind.Custom, brandId = "other"),
            ),
        ).copy(
          candidates = listOf(candidate("provider-auto:deepseek")),
          unavailableCandidates = listOf(candidate("install:other")),
          manualProviders =
            listOf(
              manual("deepseek"),
              manual("setup-token"),
              manual("lmstudio"),
              manual("openai"),
            ),
        ),
      )

    assertEquals(listOf("safe"), result.authOptions.map { it.id })
    assertEquals(listOf("deepseek", "openai"), result.manualProviders.map { it.id })
    assertTrue(result.candidates.isEmpty())
    assertTrue(result.unavailableCandidates.isEmpty())
  }

  private fun authOption(
    id: String,
    kind: AiSetupAuthKind,
    brandId: String = "openai",
    featured: Boolean = false,
  ) = AiSetupAuthOption(
    id = id,
    brandId = brandId,
    label = id,
    hint = null,
    groupLabel = null,
    iconUrl = null,
    websiteUrl = null,
    kind = kind,
    featured = featured,
  )

  private fun detection(authOptions: List<AiSetupAuthOption>) =
    AiSetupDetection(
      candidates = emptyList(),
      unavailableCandidates = emptyList(),
      manualProviders = emptyList(),
      authOptions = authOptions,
      workspace = "/workspace",
      configuredModel = null,
      setupComplete = false,
    )

  private fun manual(id: String) = AiManualProvider(id, id, null, id, null, null, null)

  private fun candidate(id: String) = AiSetupCandidate(id, null, id, id, null, null, null, false, null, null, null)
}
