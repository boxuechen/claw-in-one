package ai.openclaw.app.supervisor

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.eligibility.DeviceEligibility
import ai.openclaw.app.eligibility.DeviceEligibilityFeature
import ai.openclaw.app.eligibility.DeviceEligibilitySnapshot
import ai.openclaw.app.runtime.RuntimeAction
import ai.openclaw.app.runtime.RuntimeCoordinator
import ai.openclaw.app.runtime.resolveRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SupervisorControlControllerTest {
  private val now = 1_788_400_000L

  @Test
  fun environmentRetryUsesTheExistingPlanAndNeverAppliesANewOne() {
    val repository = FakeSupervisorRepository(session())
    repository.reads += SupervisorStatusReadResult.Updated(status(1, 1, SupervisorStatusStage.CapabilityFailed))
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      controller.resume()
      assertTrue(waitUntil { !controller.isMonitoring.value })
      val environment =
        RuntimeCoordinator(
          scope,
          controller,
          MutableStateFlow(GatewayConnectionDisplay(false, "Offline", null)),
          MutableStateFlow(null),
          readyEligibilityFeature(),
          reconnect = {},
          nowEpochSeconds = { now },
        )
      repository.reads += SupervisorStatusReadResult.Updated(status(2, 2, SupervisorStatusStage.CapabilitiesReady))
      environment.perform(RuntimeAction.RetryCapabilityPlan)
      assertTrue(waitUntil { !controller.isMonitoring.value })
      assertEquals(listOf(SupervisorCommand.Probe, SupervisorCommand.retryCapabilities("c".repeat(32))), repository.writes)
      environment.perform(RuntimeAction.RetryCapabilityPlan)
      assertEquals(2, repository.writes.size)
    } finally {
      controller.stop()
      scope.cancel()
    }
  }

  @Test
  fun explicitEnvironmentRepairReappliesTheCurrentPlanSelection() {
    val repository = FakeSupervisorRepository(session())
    val selected = listOf(DevelopmentCapability.Flutter)
    repository.reads +=
      SupervisorStatusReadResult.Updated(
        status(1, 1, SupervisorStatusStage.GatewayReady, selectedCapabilities = selected),
      )
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      controller.resume()
      assertTrue(waitUntil { !controller.isMonitoring.value })
      val environment =
        RuntimeCoordinator(
          scope,
          controller,
          MutableStateFlow(GatewayConnectionDisplay(true, "Connected", null)),
          MutableStateFlow(null),
          readyEligibilityFeature(),
          reconnect = {},
          nowEpochSeconds = { now },
        )
      repository.reads +=
        SupervisorStatusReadResult.Updated(
          status(2, 2, SupervisorStatusStage.CapabilitiesReady, selectedCapabilities = selected),
        )

      environment.perform(RuntimeAction.RepairCapabilityPlan)

      assertTrue(waitUntil { !controller.isMonitoring.value })
      val repair = repository.writes.last()
      assertEquals(SupervisorOperation.ApplyCapabilities, repair.operation)
      assertEquals(selected, repair.selectedCapabilities())
    } finally {
      controller.stop()
      scope.cancel()
    }
  }

  @Test
  fun environmentObservationDoesNotCancelActiveInstallOrWriteAProbe() {
    val repository = FakeSupervisorRepository(session())
    val progress = status(1, 1, SupervisorStatusStage.DownloadingNode).copy(completedBytes = 128, totalBytes = 1024)
    repository.reads += SupervisorStatusReadResult.Updated(progress)
    repository.reads += SupervisorStatusReadResult.Updated(status(2, 1, SupervisorStatusStage.CapabilitiesReady))
    val monitoring = CountDownLatch(1)
    val release = CountDownLatch(1)
    val controller =
      SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {
        monitoring.countDown()
        release.await()
      })
    try {
      controller.applyCapabilities(emptySet())
      assertTrue(monitoring.await(2, TimeUnit.SECONDS))
      controller.observe()
      assertTrue(controller.isMonitoring.value)
      assertFalse(controller.recover(SupervisorCommand.EnsureGateway))
      assertEquals(listOf(SupervisorOperation.ApplyCapabilities), repository.writes.map { it.operation })
      val environment =
        resolveRuntimeState(
          controller.state.value,
          controller.isMonitoring.value,
          GatewayConnectionDisplay(false, "Offline", null),
          null,
          null,
          false,
          now,
          0,
          DeviceEligibility.Ready,
        )
      assertEquals(progress.planId, environment.setup?.planId)
      assertEquals(128L, environment.setup?.completedBytes)
      assertEquals(1024L, environment.setup?.totalBytes)
      release.countDown()
      assertTrue(waitUntil { !controller.isMonitoring.value })
      assertEquals(SupervisorStatusStage.CapabilitiesReady, (controller.state.value as SupervisorControlState.Status).value.stage)
      assertEquals(listOf(SupervisorOperation.ApplyCapabilities), repository.writes.map { it.operation })
    } finally {
      controller.stop()
      release.countDown()
    }
  }

  @Test
  fun environmentObservationEnsuresACompletedGatewaySnapshotBeforeProbingAgain() {
    val repository = FakeSupervisorRepository(session())
    repository.reads += SupervisorStatusReadResult.Updated(status(1, 1, SupervisorStatusStage.GatewayNotStarted))
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      controller.resume()
      assertTrue(waitUntil { !controller.isMonitoring.value })
      repository.reads += SupervisorStatusReadResult.Updated(status(2, 2, SupervisorStatusStage.GatewayReady))
      val environment =
        RuntimeCoordinator(
          scope,
          controller,
          MutableStateFlow(GatewayConnectionDisplay(false, "Offline", null)),
          MutableStateFlow(null),
          readyEligibilityFeature(),
          reconnect = {},
          nowEpochSeconds = { now },
        )

      val observation = scope.launch { environment.observe() }

      assertTrue(waitUntil { repository.writes.size == 2 })
      assertEquals(
        listOf(SupervisorOperation.Probe, SupervisorOperation.EnsureGateway),
        repository.writes.map { it.operation },
      )
      observation.cancel()
    } finally {
      controller.stop()
      scope.cancel()
    }
  }

  @Test
  fun environmentObservationKeepsATerminalSupervisorFailureUntilExplicitRefresh() {
    val repository = FakeSupervisorRepository(session())
    repository.reads += SupervisorStatusReadResult.Missing
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      controller.resume()
      assertTrue(waitUntil { controller.state.value is SupervisorControlState.Failed })
      val environment =
        RuntimeCoordinator(
          scope,
          controller,
          MutableStateFlow(GatewayConnectionDisplay(false, "Offline", null)),
          MutableStateFlow(null),
          readyEligibilityFeature(),
          reconnect = {},
          nowEpochSeconds = { now },
        )

      val observation = scope.launch { environment.observe() }
      Thread.sleep(50)

      assertEquals(listOf(SupervisorOperation.Probe), repository.writes.map { it.operation })
      repository.reads += SupervisorStatusReadResult.Missing
      environment.perform(RuntimeAction.Refresh)
      assertTrue(waitUntil { repository.writes.size == 2 })
      assertEquals(
        listOf(SupervisorOperation.Probe, SupervisorOperation.Probe),
        repository.writes.map { it.operation },
      )
      observation.cancel()
    } finally {
      controller.stop()
      scope.cancel()
    }
  }

  @Test
  fun recoveryDoesNotOverwritePersistedMutationAfterMonitorStops() {
    val repository = FakeSupervisorRepository(session().copy(lastCommandSequence = 3, pendingCommandSequence = 3, pendingCommand = SupervisorCommand.retryCapabilities("c".repeat(32))))
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now })
    assertFalse(controller.recover(SupervisorCommand.EnsureGateway))
    assertTrue(repository.writes.isEmpty())
  }

  @Test
  fun setupPublishesProgressAndCompletesAtReady() {
    val repository = FakeSupervisorRepository(session())
    repository.reads +=
      SupervisorStatusReadResult.Updated(
        status(1, 1, SupervisorStatusStage.DownloadingNode),
      )
    repository.reads +=
      SupervisorStatusReadResult.Updated(
        status(2, 1, SupervisorStatusStage.CapabilitiesReady),
      )
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})

    controller.applyCapabilities(emptySet())

    assertTrue(
      waitUntil {
        (controller.state.value as? SupervisorControlState.Status)?.value?.stage ==
          SupervisorStatusStage.CapabilitiesReady
      },
    )
    assertEquals(
      SupervisorControlState.Status(status(2, 1, SupervisorStatusStage.CapabilitiesReady)),
      controller.state.value,
    )
    assertEquals(SupervisorOperation.ApplyCapabilities, repository.writes.single().operation)
  }

  @Test
  fun resumeWaitsForPersistedInstallInsteadOfOverwritingItWithAProbe() {
    val repository =
      FakeSupervisorRepository(
        session().copy(
          lastCommandSequence = 3,
          pendingCommandSequence = 3,
          pendingCommand = SupervisorCommand.retryCapabilities("c".repeat(32)),
        ),
      )
    repository.reads +=
      SupervisorStatusReadResult.Updated(
        status(8, 3, SupervisorStatusStage.CapabilitiesReady),
      )
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})

    controller.resume()

    assertTrue(waitUntil { controller.state.value is SupervisorControlState.Status })
    assertEquals(emptyList<SupervisorCommand>(), repository.writes)
    assertEquals(
      SupervisorControlState.Status(status(8, 3, SupervisorStatusStage.CapabilitiesReady)),
      controller.state.value,
    )
  }

  @Test
  fun authenticationFailureIsExposedWithoutRetryingAMutation() {
    val repository = FakeSupervisorRepository(session())
    repository.reads +=
      SupervisorStatusReadResult.VerificationFailed(
        SupervisorControlVerificationError.AuthenticationFailed,
      )
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})

    controller.resume()

    assertTrue(waitUntil { controller.state.value is SupervisorControlState.Failed })
    assertEquals(
      SupervisorControlState.Failed(
        SupervisorControlError.StatusVerificationFailed(
          SupervisorControlVerificationError.AuthenticationFailed,
        ),
      ),
      controller.state.value,
    )
    assertEquals(listOf(SupervisorCommand.Probe), repository.writes)
  }

  @Test
  fun gatewayPairingCompletesOnlyAtThePairingReadyStage() {
    val repository = FakeSupervisorRepository(session())
    val setupCode = "a_valid_gateway_setup_code"
    repository.reads +=
      SupervisorStatusReadResult.Updated(
        status(1, 1, SupervisorStatusStage.GatewayHealthy),
      )
    repository.reads +=
      SupervisorStatusReadResult.Updated(
        status(2, 1, SupervisorStatusStage.GatewayPairingReady, setupCode),
      )
    val controller = SupervisorControlController(repository, nowEpochSeconds = { now }, sleep = {})

    controller.requestGatewayPairing()

    assertTrue(
      waitUntil {
        (controller.state.value as? SupervisorControlState.Status)?.value?.stage ==
          SupervisorStatusStage.GatewayPairingReady
      },
    )
    assertEquals(
      SupervisorControlState.Status(
        status(2, 1, SupervisorStatusStage.GatewayPairingReady, setupCode),
      ),
      controller.state.value,
    )
    assertEquals(listOf(SupervisorCommand.RequestGatewayPairing), repository.writes)
  }

  private fun waitUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + 2_000_000_000L
    while (System.nanoTime() < deadline) {
      if (condition()) return true
      Thread.sleep(5)
    }
    return false
  }

  private fun session() =
    SupervisorControlSession(
      supervisorId = "a".repeat(32),
      secretHex = "b".repeat(64),
      commandUri = "content://downloads/command",
      statusUri = "content://downloads/status",
    )

  private fun status(
    eventSequence: Long,
    commandSequence: Long,
    stage: SupervisorStatusStage,
    setupCode: String? = null,
    selectedCapabilities: List<DevelopmentCapability> = emptyList(),
  ) = SupervisorStatus(
    supervisorBootId = "d".repeat(32),
    eventSequence = eventSequence,
    commandSequence = commandSequence,
    timestampEpochSeconds = now,
    stage = stage,
    gatewayGeneration = if (stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted) "e".repeat(32) else null,
    ensureAttemptId = if (stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted) 1 else null,
    exitCode = 0,
    planId = "c".repeat(32),
    selectedCapabilities = selectedCapabilities,
    resolvedComponents = resolveComponents(selectedCapabilities),
    readyCapabilities = emptyList(),
    currentComponent =
      when (stage) {
        SupervisorStatusStage.DownloadingNode -> CapabilityComponent.OpenClaw
        SupervisorStatusStage.CapabilityFailed -> resolveComponents(emptyList()).first()
        else -> null
      },
    setupCode = setupCode,
  )
}

private fun readyEligibilityFeature() =
  DeviceEligibilityFeature(
    state = MutableStateFlow(DeviceEligibilitySnapshot(eligibility = DeviceEligibility.Ready)),
    refresh = {},
  )

private class FakeSupervisorRepository(
  private var session: SupervisorControlSession?,
) : SupervisorControlRepository {
  val writes = mutableListOf<SupervisorCommand>()
  val reads = ArrayDeque<SupervisorStatusReadResult>()

  override fun load(): SupervisorControlSession? = session

  override fun activate(activation: SupervisorActivation): Boolean = false

  override fun writeCommand(
    command: SupervisorCommand,
    nowEpochSeconds: Long,
  ): SupervisorCommandWriteResult {
    val current = session ?: return SupervisorCommandWriteResult.MissingSession
    val sequence = current.lastCommandSequence + 1
    writes += command
    session =
      current.copy(
        lastCommandSequence = sequence,
        pendingCommandSequence = sequence,
        pendingCommand = command,
      )
    return SupervisorCommandWriteResult.Written(sequence)
  }

  override fun readStatus(nowEpochSeconds: Long): SupervisorStatusReadResult {
    val result =
      if (reads.isEmpty()) {
        SupervisorStatusReadResult.Unchanged(session?.lastStatus())
      } else {
        reads.removeFirst()
      }
    // Model the repository's durable acknowledgement of terminal command results.
    val current = session
    val status = (result as? SupervisorStatusReadResult.Updated)?.status
    if (current != null &&
      status != null &&
      current.pendingCommandSequence != null &&
      status.commandSequence >= current.pendingCommandSequence &&
      current.pendingCommand?.isTerminalAt(status.stage) == true
    ) {
      session = current.copy(pendingCommandSequence = null, pendingCommand = null)
    }
    return result
  }

  override fun clear() {
    session = null
  }
}
