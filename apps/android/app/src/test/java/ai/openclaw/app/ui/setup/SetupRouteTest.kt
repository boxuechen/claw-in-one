package ai.openclaw.app.ui.setup

import ai.openclaw.app.onboarding.EnvironmentSetupStep
import ai.openclaw.app.onboarding.FirstRunFailure
import ai.openclaw.app.onboarding.FirstRunState
import ai.openclaw.app.onboarding.LinuxProvisioningStep
import ai.openclaw.app.onboarding.RequiredSetupEvidence
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.ComponentKey
import ai.openclaw.app.supervisor.SupervisorStatus
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.requiredSetupComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRouteTest {
  @Test
  fun `finalizing names the OpenClaw milestone`() {
    val copy =
      firstRunCopy(
        FirstRunState.Finalizing(
          RequiredSetupEvidence("b".repeat(32), "a".repeat(32), eventSequence = 7),
        ),
      )

    assertEquals(4, copy.phase)
    assertEquals("ClawInOne is ready", copy.title)
    assertEquals(SetupPrimaryAction.None, copy.action)
  }

  @Test
  fun `terminal handoff is the only supervisor setup action`() {
    val copy =
      firstRunCopy(
        FirstRunState.LinuxProvisioning(
          LinuxProvisioningStep.SupervisorRequired(commandReady = true),
        ),
      )

    assertEquals(2, copy.phase)
    assertEquals(SetupPrimaryAction.None, copy.action)
    assertNull(copy.primaryLabel)
    assertEquals("Run this one-time command in Terminal.", copy.body)
  }

  @Test
  fun `Bootstrap source uses an exact build commit and safely falls back to main`() {
    val commit = "a".repeat(40)

    assertEquals(
      "https://github.com/boxuechen/claw-in-one/tree/$commit/bootstrap",
      bootstrapSourceUrl(commit),
    )
    assertEquals(
      "https://github.com/boxuechen/claw-in-one/tree/main/bootstrap",
      bootstrapSourceUrl("unknown"),
    )
  }

  @Test
  fun `required environment has one explicit install action`() {
    val copy =
      firstRunCopy(
        FirstRunState.EnvironmentSetup(EnvironmentSetupStep.InstallReady),
      )

    assertEquals(3, copy.phase)
    assertEquals("Prepare OpenClaw", copy.title)
    assertEquals("Install Chromium, ADB, VScreen support, Node, and OpenClaw in Linux.", copy.body)
    assertEquals(SetupPrimaryAction.InstallEnvironment, copy.action)
    assertEquals("Install environment", copy.primaryLabel)
  }

  @Test
  fun `blocking setup progress has status text but no primary button`() {
    val status =
      setupStatus(
        stage = SupervisorStatusStage.VerifyingOpenClawInstallation,
        current = CapabilityComponent.OpenClaw,
      )
    val copy =
      firstRunCopy(
        FirstRunState.EnvironmentSetup(
          EnvironmentSetupStep.Installing(
            status = status,
            resolvedComponents = status.resolvedComponents,
          ),
        ),
      )

    assertEquals(3, copy.phase)
    assertEquals("Verifying the installation", copy.statusLabel)
    assertEquals(SetupPrimaryAction.None, copy.action)
    assertNull(copy.primaryLabel)
  }

  @Test
  fun `download progress is determinate and byte based`() {
    val copy =
      firstRunCopy(
        FirstRunState.EnvironmentSetup(
          EnvironmentSetupStep.Installing(
            setupStatus(
              stage = SupervisorStatusStage.DownloadingOpenClaw,
              current = CapabilityComponent.OpenClaw,
              completedBytes = 25,
              totalBytes = 100,
            ),
            resolvedComponents = requiredSetupComponents(),
          ),
        ),
      )

    assertEquals(0.25f, copy.progressFraction)
    assertEquals("0.0 of 0.0 MB · 25%", copy.progressDetail)
  }

  @Test
  fun `progress rows follow the fixed required component graph`() {
    val components = requiredSetupComponents()
    val currentComponent = components.first { it.key == ComponentKey.VScreenRuntimeAssets }
    val items =
      setupProgressItems(
        FirstRunState.EnvironmentSetup(
          EnvironmentSetupStep.Installing(
            status =
              setupStatus(
                stage = SupervisorStatusStage.InstallingComponent,
                current = currentComponent,
              ),
            resolvedComponents = components,
          ),
        ),
      )

    val current = items.indexOfFirst { it.component == currentComponent }
    assertTrue(current > 0)
    assertTrue(items.take(current).all { it.state == SetupProgressState.Complete })
    assertEquals(SetupProgressState.Active, items[current].state)
    assertTrue(items.drop(current + 1).all { it.state == SetupProgressState.Pending })
  }

  @Test
  fun `required component failure offers retry only`() {
    val copy =
      firstRunCopy(
        FirstRunState.Failed(
          FirstRunFailure.Setup(
            setupStatus(
              stage = SupervisorStatusStage.CapabilityFailed,
              current = CapabilityComponent.OpenClaw,
              exitCode = 23,
            ),
          ),
        ),
      )

    assertEquals(SetupPrimaryAction.Retry, copy.action)
    assertEquals("Try again", copy.primaryLabel)
  }

  private fun setupStatus(
    stage: SupervisorStatusStage,
    current: CapabilityComponent?,
    exitCode: Int = 0,
    completedBytes: Long? = null,
    totalBytes: Long? = null,
  ) = SupervisorStatus(
    supervisorBootId = "d".repeat(32),
    eventSequence = 1,
    commandSequence = 1,
    timestampEpochSeconds = 1,
    stage = stage,
    exitCode = exitCode,
    planId = "c".repeat(32),
    selectedCapabilities = emptyList(),
    resolvedComponents = requiredSetupComponents(),
    currentComponent = current,
    completedBytes = completedBytes,
    totalBytes = totalBytes,
  )
}
