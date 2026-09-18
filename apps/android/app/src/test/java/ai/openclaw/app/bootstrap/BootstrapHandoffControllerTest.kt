package ai.openclaw.app.bootstrap

import ai.openclaw.app.supervisor.SUPERVISOR_CONTROL_PROTOCOL_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapHandoffControllerTest {
  private val now = 1_788_400_000L
  private val requestId = "a".repeat(32)
  private val handoffSecret = "b".repeat(64)
  private val supervisorSecret = "c".repeat(64)

  @Test
  fun orderedSignedEventsReachSupervisorReadyAndPersistProgress() {
    val lines =
      SupervisorProgress.entries.mapIndexed { index, progress ->
        eventLine(index + 1L, supervisorProgressToHandoffWire(progress), "{}")
      } +
        eventLine(
          4,
          SUPERVISOR_READY_STAGE,
          """{"protocolVersion":$SUPERVISOR_CONTROL_PROTOCOL_VERSION}""",
        )
    val repository = FakeHandoffRepository(session(), lines)
    val published = mutableListOf<BootstrapHandoffState>()
    var activated: BootstrapHandoffSession? = null

    val result =
      monitor(
        repository,
        published,
        activateSupervisor = { handoff ->
          activated = handoff
          true
        },
      )

    assertEquals(
      BootstrapHandoffState.Ready(requestId, SUPERVISOR_CONTROL_PROTOCOL_VERSION),
      result,
    )
    assertEquals(requestId, activated?.requestId)
    assertEquals(supervisorSecret, activated?.supervisorSecretHex)
    assertEquals(
      SupervisorProgress.entries.map(BootstrapHandoffState::Progress),
      published.filterIsInstance<BootstrapHandoffState.Progress>(),
    )
    assertEquals(null, repository.active)
  }

  @Test
  fun processRestartResumesFromCheckpointAndRereadsReady() {
    val checkpointed =
      session().copy(
        createdAtEpochSeconds = now - 2_000,
        lastSequence = SupervisorProgress.entries.size.toLong(),
        lastProgressOrdinal = SupervisorProgress.StartingSupervisor.ordinal,
      )
    val priorLines =
      SupervisorProgress.entries.mapIndexed { index, progress ->
        eventLine(index + 1L, supervisorProgressToHandoffWire(progress), "{}")
      }
    val readyLine =
      eventLine(
        4,
        SUPERVISOR_READY_STAGE,
        """{"protocolVersion":$SUPERVISOR_CONTROL_PROTOCOL_VERSION}""",
      )
    val repository = FakeHandoffRepository(checkpointed, priorLines + readyLine)

    assertTrue(monitor(repository, mutableListOf()) is BootstrapHandoffState.Ready)
    assertEquals(null, repository.active)
  }

  @Test
  fun skippedStageFailsClosed() {
    val repository =
      FakeHandoffRepository(
        session(),
        listOf(eventLine(1, "installing_supervisor", "{}")),
      )

    assertEquals(
      BootstrapHandoffState.Failed(BootstrapHandoffError.StageViolation),
      monitor(repository, mutableListOf()),
    )
  }

  @Test
  fun readyBeforeStartingFailsClosed() {
    val repository =
      FakeHandoffRepository(
        session(),
        listOf(
          eventLine(1, "bootstrap_executed", "{}"),
          eventLine(
            2,
            SUPERVISOR_READY_STAGE,
            """{"protocolVersion":$SUPERVISOR_CONTROL_PROTOCOL_VERSION}""",
          ),
        ),
      )

    assertEquals(
      BootstrapHandoffState.Failed(BootstrapHandoffError.StageViolation),
      monitor(repository, mutableListOf()),
    )
  }

  @Test
  fun tamperedEventFailsBeforeCheckpoint() {
    val valid = eventLine(1, "bootstrap_executed", "{}")
    val repository =
      FakeHandoffRepository(
        session(),
        listOf(valid.replace("bootstrap_executed", "starting_supervisor")),
      )

    assertEquals(
      BootstrapHandoffState.Failed(
        BootstrapHandoffError.VerificationFailed(
          BootstrapHandoffVerificationError.AuthenticationFailed,
        ),
      ),
      monitor(repository, mutableListOf()),
    )
    assertEquals(0L, repository.active?.lastSequence)
  }

  @Test
  fun sequenceGapFailsClosed() {
    val repository =
      FakeHandoffRepository(
        session(),
        listOf(eventLine(2, "bootstrap_executed", "{}")),
      )

    assertEquals(
      BootstrapHandoffState.Failed(BootstrapHandoffError.SequenceViolation),
      monitor(repository, mutableListOf()),
    )
  }

  @Test
  fun signedInstallerFailureCarriesTheLastVerifiedStage() {
    val repository =
      FakeHandoffRepository(
        session(),
        listOf(
          eventLine(1, "bootstrap_executed", "{}"),
          eventLine(2, FAILED_STAGE, """{"stage":"bootstrap_executed","exitCode":7}"""),
        ),
      )

    assertEquals(
      BootstrapHandoffState.Failed(
        BootstrapHandoffError.StageFailed(SupervisorProgress.BootstrapExecuted, 7),
      ),
      monitor(repository, mutableListOf()),
    )
  }

  @Test
  fun inactiveHandoffTimesOutWithoutFabricatingProgress() {
    val repository =
      FakeHandoffRepository(
        session().copy(createdAtEpochSeconds = now - 2_000),
        emptyList(),
      )

    assertEquals(
      BootstrapHandoffState.Failed(BootstrapHandoffError.TimedOut),
      monitor(repository, mutableListOf()),
    )
  }

  @Test
  fun handoffIsKeptWhenPersistentSupervisorActivationFails() {
    val lines =
      SupervisorProgress.entries.mapIndexed { index, progress ->
        eventLine(index + 1L, supervisorProgressToHandoffWire(progress), "{}")
      } +
        eventLine(
          4,
          SUPERVISOR_READY_STAGE,
          """{"protocolVersion":$SUPERVISOR_CONTROL_PROTOCOL_VERSION}""",
        )
    val repository = FakeHandoffRepository(session(), lines)

    assertEquals(
      BootstrapHandoffState.Failed(BootstrapHandoffError.SupervisorActivationFailed),
      monitor(repository, mutableListOf(), activateSupervisor = { false }),
    )
    assertEquals(3L, repository.active?.lastSequence)
  }

  @Test
  fun environmentSeparatesHandoffAndPersistentSupervisorSecrets() {
    val environment =
      supervisorEnvironment(
        requestId = requestId,
        handoffSecretHex = handoffSecret,
        supervisorSecretHex = supervisorSecret,
        linuxSharedDirectory = "/mnt/shared/Download/ClawInOne/bootstrap-${"d".repeat(32)}",
        linuxEventsFile = "/mnt/shared/Download/ClawInOne/handoff-$requestId/handoff.events",
        linuxSupervisorCommandFile =
          "/mnt/shared/Download/ClawInOne/supervisor-$requestId/supervisor.command",
        linuxSupervisorStatusFile =
          "/mnt/shared/Download/ClawInOne/supervisor-$requestId/supervisor.status",
        supervisorBinarySha256 = "e".repeat(64),
      )

    assertTrue(environment.contains("CLAW_IN_ONE_HANDOFF_PROTOCOL_VERSION='4'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_HANDOFF_SECRET='$handoffSecret'\n"))
    assertTrue(
      environment.contains(
        "CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION='$SUPERVISOR_CONTROL_PROTOCOL_VERSION'\n",
      ),
    )
    assertTrue(environment.contains("CLAW_IN_ONE_SUPERVISOR_ID='$requestId'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_SUPERVISOR_SECRET='$supervisorSecret'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_SUPERVISOR_BINARY_SHA256='${"e".repeat(64)}'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_NODE_SIZE_BYTES='57128466'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_OPENCLAW_SIZE_BYTES='67342399'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_DEBIAN_SERIES='13'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_PACKAGE_SET_GENERATION='1'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_GENERAL_NODE_VERSION='22.22.0'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_SCRCPY_SERVER_VERSION='4.1'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_SCRCPY_SERVER_SIZE_BYTES='733706'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_VERSION='16111833'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_ANDROID_SDK_CHANNEL='3'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_ANDROID_PLATFORM='37.0'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_ANDROID_GRADLE_VERSION='9.7.1'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_ANDROID_GRADLE_SIZE_BYTES='151433392'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_ANDROID_NATIVE_NDK='29.0.14206865'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_ANDROID_NATIVE_CMAKE='3.22.1'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_FLUTTER_VERSION='3.47.4'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_FLUTTER_DART_VERSION='3.13.3'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_FLUTTER_ANDROID_PLATFORM='36'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_FLUTTER_ANDROID_NDK='28.2.13676358'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_GODOT_VERSION='4.7.2'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_GODOT_BUILD='stable.official.ed1daf0bf'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_GODOT_ENGINE_SIZE_BYTES='77008699'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_SIZE_BYTES='1281349702'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_WEB_REACT_VERSION='19.3.0'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_WEB_VITE_VERSION='8.3.0'\n"))
    assertTrue(environment.contains("CLAW_IN_ONE_WEB_TYPESCRIPT_VERSION='7.0.2'\n"))
    assertTrue(!environment.contains("SUPERVISOR_PORT"))
    assertEquals(84, environment.lineSequence().filter { it.isNotEmpty() }.count())
  }

  private fun monitor(
    repository: BootstrapHandoffRepository,
    published: MutableList<BootstrapHandoffState>,
    activateSupervisor: (BootstrapHandoffSession) -> Boolean = { true },
  ): BootstrapHandoffState =
    runBootstrapHandoffMonitor(
      repository = repository,
      activateSupervisor = activateSupervisor,
      nowEpochSeconds = { now },
      sleep = { error("terminal event expected") },
      publish = published::add,
    )

  private fun session(): BootstrapHandoffSession =
    BootstrapHandoffSession(
      requestId = requestId,
      handoffSecretHex = handoffSecret,
      supervisorSecretHex = supervisorSecret,
      bootstrapUri = "content://downloads/bootstrap",
      environmentUri = "content://downloads/environment",
      eventsUri = "content://downloads/events",
      supervisorCommandUri = "content://downloads/supervisor-command",
      supervisorStatusUri = "content://downloads/supervisor-status",
      command = "CLAW_IN_ONE_CONFIG_FILE='bootstrap.env' bash 'bootstrap.sh'",
      createdAtEpochSeconds = now,
    )

  private fun eventLine(
    sequence: Long,
    stage: String,
    payload: String,
  ): String =
    signBootstrapHandoffEnvelope(
      secretHex = handoffSecret,
      requestId = requestId,
      sequence = sequence,
      timestampEpochSeconds = now,
      stage = stage,
      payloadJson = payload,
    )
}

private class FakeHandoffRepository(
  initial: BootstrapHandoffSession,
  private val lines: List<String>,
) : BootstrapHandoffRepository {
  var active: BootstrapHandoffSession? = initial

  override fun loadActive(): BootstrapHandoffSession? = active

  override fun saveActive(session: BootstrapHandoffSession): Boolean {
    active = session
    return true
  }

  override fun readEvents(session: BootstrapHandoffSession): BootstrapHandoffReadResult = BootstrapHandoffReadResult.Content(lines)

  override fun checkpoint(
    requestId: String,
    sequence: Long,
    progress: SupervisorProgress,
  ): Boolean {
    val current = active ?: return false
    active =
      current.copy(
        lastSequence = sequence,
        lastProgressOrdinal = progress.ordinal,
      )
    return true
  }

  override fun discardActive(requestId: String?) {
    if (requestId == null || requestId == active?.requestId) active = null
  }
}
