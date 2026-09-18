package ai.openclaw.app.onboarding

import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.requiredSetupComponents
import kotlinx.serialization.Serializable
import java.util.UUID

internal data class RequiredSetupEvidence(
  val supervisorId: String,
  val planId: String,
  val eventSequence: Long,
) {
  init {
    require(supervisorId.matches(IDENTITY_PATTERN))
    require(planId.matches(IDENTITY_PATTERN))
    require(eventSequence > 0)
  }
}

internal fun requiredSetupEvidence(
  supervisorId: String?,
  status: SupervisorStatus,
): RequiredSetupEvidence? {
  val settled =
    status.stage in
      setOf(
        SupervisorStatusStage.CapabilitiesReady,
        SupervisorStatusStage.GatewayNotStarted,
        SupervisorStatusStage.GatewayConfiguring,
        SupervisorStatusStage.GatewayStarting,
        SupervisorStatusStage.GatewayHealthy,
        SupervisorStatusStage.GatewayReady,
        SupervisorStatusStage.GatewayPairingReady,
        SupervisorStatusStage.GatewayPairingFailed,
        SupervisorStatusStage.GatewayFailed,
      )
  val planId = status.planId
  return if (
    settled &&
    supervisorId?.matches(IDENTITY_PATTERN) == true &&
    planId?.matches(IDENTITY_PATTERN) == true &&
    status.eventSequence > 0 &&
    status.resolvedComponents.containsAll(requiredSetupComponents())
  ) {
    RequiredSetupEvidence(supervisorId, planId, status.eventSequence)
  } else {
    null
  }
}

@Serializable
internal data class OnboardingReceipt(
  val schemaVersion: Int,
  val completionId: String,
  val completedAtEpochSeconds: Long,
  val supervisorId: String,
  val setupPlanId: String,
  val setupEventSequence: Long,
) {
  fun normalized(): OnboardingReceipt? =
    takeIf {
      schemaVersion == CURRENT_ONBOARDING_SCHEMA_VERSION &&
        completedAtEpochSeconds > 0 &&
        runCatching { UUID.fromString(completionId) }.isSuccess &&
        supervisorId.matches(IDENTITY_PATTERN) &&
        setupPlanId.matches(IDENTITY_PATTERN) &&
        setupEventSequence > 0
    }

  companion object {
    fun create(
      evidence: RequiredSetupEvidence,
      completedAtEpochSeconds: Long = System.currentTimeMillis() / 1_000,
      completionId: String = UUID.randomUUID().toString(),
    ): OnboardingReceipt =
      OnboardingReceipt(
        schemaVersion = CURRENT_ONBOARDING_SCHEMA_VERSION,
        completionId = completionId,
        completedAtEpochSeconds = completedAtEpochSeconds,
        supervisorId = evidence.supervisorId,
        setupPlanId = evidence.planId,
        setupEventSequence = evidence.eventSequence,
      ).also { requireNotNull(it.normalized()) }

    /** Screenshot fixtures skip live onboarding but still use the current receipt shape. */
    fun screenshotFixture(): OnboardingReceipt =
      create(
        evidence =
          RequiredSetupEvidence(
            supervisorId = "f".repeat(32),
            planId = "e".repeat(32),
            eventSequence = 1,
          ),
        completedAtEpochSeconds = 1,
        completionId = "11111111-1111-4111-8111-111111111111",
      )
  }
}

private val IDENTITY_PATTERN = Regex("[0-9a-f]{32}")

internal const val CURRENT_ONBOARDING_SCHEMA_VERSION = 9
