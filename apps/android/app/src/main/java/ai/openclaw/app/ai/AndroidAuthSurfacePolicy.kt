package ai.openclaw.app.ai

/**
 * Narrows Gateway-advertised authentication methods to flows that can be completed from an
 * Android-hosted browser. Gateway remains the authority for method availability and credentials.
 */
internal object AndroidAuthSurfacePolicy {
  private const val OPENAI_PROVIDER_ID = "openai"
  private val unsupportedManualProviderIds =
    setOf("setup-token", "github-copilot", "llama-cpp-existing-server", "lmstudio")

  fun setupDetection(detection: AiSetupDetection): AiSetupDetection {
    val compatibleOptions =
      detection.authOptions
        .filter { it.kind == AiSetupAuthKind.OAuth || it.kind == AiSetupAuthKind.DeviceCode }
        .filterNot { option ->
          option.brandId == OPENAI_PROVIDER_ID && option.kind == AiSetupAuthKind.OAuth
        }
    val hasOpenAiDevicePairing =
      compatibleOptions.any { it.brandId == OPENAI_PROVIDER_ID && it.kind == AiSetupAuthKind.DeviceCode }
    val options =
      compatibleOptions.map { option ->
        if (hasOpenAiDevicePairing) {
          option.copy(featured = option.brandId == OPENAI_PROVIDER_ID && option.kind == AiSetupAuthKind.DeviceCode)
        } else {
          option
        }
      }
    return detection.copy(
      candidates = emptyList(),
      unavailableCandidates = emptyList(),
      authOptions = options,
      manualProviders =
        detection.manualProviders.filter { provider ->
          provider.id !in unsupportedManualProviderIds
        },
    )
  }
}
