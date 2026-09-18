package ai.openclaw.app.onboarding

import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.requiredSetupComponents
import ai.openclaw.app.supervisor.resolveComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingReceiptTest {
  @Test
  fun settledRequiredEnvironmentProducesSetupEvidence() {
    val evidence = requiredSetupEvidence(SUPERVISOR_ID, status())

    assertEquals(
      RequiredSetupEvidence(SUPERVISOR_ID, PLAN_ID, eventSequence = 7),
      evidence,
    )
  }

  @Test
  fun evidenceRequiresSettledStageExactOwnerAndAllRequiredComponents() {
    assertNull(
      requiredSetupEvidence(
        SUPERVISOR_ID,
        status(stage = SupervisorStatusStage.InstallingComponent),
      ),
    )
    assertNull(requiredSetupEvidence("wrong-owner", status()))
    assertNull(requiredSetupEvidence(SUPERVISOR_ID, status(planId = "wrong-plan")))
    assertNull(
      requiredSetupEvidence(
        SUPERVISOR_ID,
        status(resolvedComponents = requiredSetupComponents().dropLast(1)),
      ),
    )
  }

  @Test
  fun optionalExtensionsDoNotChangeReceiptShape() {
    val evidence =
      requiredSetupEvidence(
        SUPERVISOR_ID,
        status(
          selectedCapabilities = listOf(DevelopmentCapability.Flutter),
          resolvedComponents = resolveComponents(listOf(DevelopmentCapability.Flutter)),
        ),
      )
    val receipt = OnboardingReceipt.create(checkNotNull(evidence), completedAtEpochSeconds = 8)

    assertEquals(SUPERVISOR_ID, receipt.supervisorId)
    assertEquals(PLAN_ID, receipt.setupPlanId)
    assertEquals(7, receipt.setupEventSequence)
    assertNotNull(receipt.normalized())
  }

  @Test
  fun receiptAcceptsOnlyCurrentValidMilestone() {
    val valid =
      OnboardingReceipt.create(
        RequiredSetupEvidence(SUPERVISOR_ID, PLAN_ID, eventSequence = 7),
        completedAtEpochSeconds = 8,
        completionId = "11111111-1111-4111-8111-111111111111",
      )

    assertEquals(CURRENT_ONBOARDING_SCHEMA_VERSION, valid.schemaVersion)
    assertNotNull(valid.normalized())
    assertNull(valid.copy(schemaVersion = CURRENT_ONBOARDING_SCHEMA_VERSION - 1).normalized())
    assertNull(valid.copy(completionId = "not-a-uuid").normalized())
    assertNull(valid.copy(supervisorId = "wrong-owner").normalized())
    assertNull(valid.copy(setupPlanId = "wrong-plan").normalized())
    assertNull(valid.copy(setupEventSequence = 0).normalized())
  }

  private fun status(
    stage: SupervisorStatusStage = SupervisorStatusStage.CapabilitiesReady,
    planId: String = PLAN_ID,
    selectedCapabilities: List<DevelopmentCapability> = emptyList(),
    resolvedComponents: List<ai.openclaw.app.supervisor.CapabilityComponent> = requiredSetupComponents(),
  ) = SupervisorStatus(
    supervisorBootId = "d".repeat(32),
    eventSequence = 7,
    commandSequence = 2,
    timestampEpochSeconds = 8,
    stage = stage,
    exitCode = 0,
    planId = planId,
    selectedCapabilities = selectedCapabilities,
    resolvedComponents = resolvedComponents,
  )

  private companion object {
    const val SUPERVISOR_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val PLAN_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
