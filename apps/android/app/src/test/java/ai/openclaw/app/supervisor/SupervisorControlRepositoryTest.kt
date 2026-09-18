package ai.openclaw.app.supervisor

import ai.openclaw.app.SecurePrefs
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupervisorControlRepositoryTest {
  private val app get() = RuntimeEnvironment.getApplication()
  private val supervisorId = "a".repeat(32)
  private val supervisorBootId = "d".repeat(32)
  private val secret = "b".repeat(64)
  private val now = 1_788_400_000L

  @Test
  fun activationCommandAndStatusSurviveRepositoryRecreation() {
    val backing =
      app.getSharedPreferences(
        "supervisor-control-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = backing)
    val commandUri = createDownload("supervisor.command")
    val statusUri = createDownload("supervisor.status")
    val repository = MediaStoreSupervisorControlRepository(app, prefs)

    assertTrue(repository.activate(activation(commandUri.toString(), statusUri.toString())))
    assertEquals(
      SupervisorCommandWriteResult.Written(1),
      repository.writeCommand(SupervisorCommand.Probe, now),
    )
    val command =
      app.contentResolver
        .openInputStream(commandUri)
        ?.bufferedReader()
        ?.use { it.readText() }
    assertTrue(command?.startsWith("$SUPERVISOR_CONTROL_PROTOCOL_VERSION|$supervisorId|1|$now|probe|-|") == true)
    assertFalse(command.orEmpty().contains(secret))

    app.contentResolver.openOutputStream(statusUri, "wt")?.use { output ->
      output.write(statusLine(eventSequence = 4, commandSequence = 1).toByteArray())
    }
    assertEquals(
      SupervisorStatusReadResult.Updated(
        SupervisorStatus(supervisorBootId, 4, 1, now, SupervisorStatusStage.CapabilitiesRequired, exitCode = 0),
      ),
      repository.readStatus(now),
    )

    val restored = MediaStoreSupervisorControlRepository(app, prefs)
    assertEquals(1L, restored.load()?.lastCommandSequence)
    assertEquals(4L, restored.load()?.lastStatusSequence)
    assertEquals(
      SupervisorStatusReadResult.Unchanged(
        SupervisorStatus(supervisorBootId, 4, 1, now, SupervisorStatusStage.CapabilitiesRequired, exitCode = 0),
      ),
      restored.readStatus(now),
    )
    assertFalse(restored.load().toString().contains(secret))

    restored.clear()
    assertNull(repository.load())
  }

  @Test
  fun aStatusCannotClaimAnUnissuedCommand() {
    val backing =
      app.getSharedPreferences(
        "supervisor-control-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val repository =
      MediaStoreSupervisorControlRepository(
        app,
        SecurePrefs(app, securePrefsOverride = backing),
      )
    val commandUri = createDownload("supervisor.command")
    val statusUri = createDownload("supervisor.status")
    assertTrue(repository.activate(activation(commandUri.toString(), statusUri.toString())))
    app.contentResolver.openOutputStream(statusUri, "wt")?.use { output ->
      output.write(statusLine(eventSequence = 1, commandSequence = 1).toByteArray())
    }

    assertEquals(SupervisorStatusReadResult.SequenceViolation, repository.readStatus(now))
    assertNotNull(repository.load())
    repository.clear()
  }

  @Test
  fun aStaleSnapshotWaitsForTheFreshProbeResponse() {
    val backing =
      app.getSharedPreferences(
        "supervisor-control-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val repository =
      MediaStoreSupervisorControlRepository(
        app,
        SecurePrefs(app, securePrefsOverride = backing),
      )
    val commandUri = createDownload("supervisor.command")
    val statusUri = createDownload("supervisor.status")
    assertTrue(repository.activate(activation(commandUri.toString(), statusUri.toString())))
    app.contentResolver.openOutputStream(statusUri, "wt")?.use { output ->
      output.write(statusLine(eventSequence = 1, commandSequence = 0).toByteArray())
    }

    assertEquals(
      SupervisorStatusReadResult.Stale(null),
      repository.readStatus(now + SUPERVISOR_CONTROL_MAX_AGE_SECONDS + 1),
    )
    repository.clear()
  }

  @Test
  fun unchangedPairingStatusRecoversTransientSetupCodeWithoutPersistingIt() {
    val backing =
      app.getSharedPreferences(
        "supervisor-control-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = backing)
    val commandUri = createDownload("supervisor.command")
    val statusUri = createDownload("supervisor.status")
    val repository = MediaStoreSupervisorControlRepository(app, prefs)
    val setupCode = "a_valid_gateway_setup_code"
    assertTrue(repository.activate(activation(commandUri.toString(), statusUri.toString())))
    assertEquals(
      SupervisorCommandWriteResult.Written(1),
      repository.writeCommand(SupervisorCommand.RequestGatewayPairing, now),
    )
    app.contentResolver.openOutputStream(statusUri, "wt")?.use { output ->
      output.write(
        statusLine(
          eventSequence = 1,
          commandSequence = 1,
          stage = SupervisorStatusStage.GatewayPairingReady,
          setupCode = setupCode,
        ).toByteArray(),
      )
    }

    val expected =
      SupervisorStatus(
        supervisorBootId = supervisorBootId,
        eventSequence = 1,
        commandSequence = 1,
        timestampEpochSeconds = now,
        stage = SupervisorStatusStage.GatewayPairingReady,
        gatewayGeneration = "e".repeat(32),
        ensureAttemptId = 1,
        exitCode = 0,
        planId = "c".repeat(32),
        selectedCapabilities = emptyList(),
        resolvedComponents = resolveComponents(emptyList()),
        readyCapabilities = emptyList(),
        setupCode = setupCode,
      )
    assertEquals(SupervisorStatusReadResult.Updated(expected), repository.readStatus(now))

    val restored = MediaStoreSupervisorControlRepository(app, prefs)
    assertEquals(SupervisorStatusReadResult.Unchanged(expected), restored.readStatus(now))
    assertFalse(restored.load().toString().contains(setupCode))
    restored.clear()
  }

  @Test
  fun pendingInstallSurvivesProgressAndClearsOnlyAtReady() {
    val backing =
      app.getSharedPreferences(
        "supervisor-control-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = backing)
    val commandUri = createDownload("supervisor.command")
    val statusUri = createDownload("supervisor.status")
    val repository = MediaStoreSupervisorControlRepository(app, prefs)
    assertTrue(repository.activate(activation(commandUri.toString(), statusUri.toString())))
    assertEquals(
      SupervisorCommandWriteResult.Written(1),
      repository.writeCommand(
        SupervisorCommand.applyCapabilities("c".repeat(32), emptySet()),
        now,
      ),
    )
    app.contentResolver.openOutputStream(statusUri, "wt")?.use { output ->
      output.write(
        statusLine(
          eventSequence = 1,
          commandSequence = 1,
          stage = SupervisorStatusStage.DownloadingNode,
          completedBytes = 25,
          totalBytes = 100,
        ).toByteArray(),
      )
    }

    val expected =
      SupervisorStatus(
        supervisorBootId = supervisorBootId,
        eventSequence = 1,
        commandSequence = 1,
        timestampEpochSeconds = now,
        stage = SupervisorStatusStage.DownloadingNode,
        exitCode = 0,
        planId = "c".repeat(32),
        selectedCapabilities = emptyList(),
        resolvedComponents = resolveComponents(emptyList()),
        currentComponent = CapabilityComponent.OpenClaw,
        completedBytes = 25,
        totalBytes = 100,
      )
    assertEquals(SupervisorStatusReadResult.Updated(expected), repository.readStatus(now))
    assertEquals(1L, repository.load()?.pendingCommandSequence)
    assertEquals(SupervisorOperation.ApplyCapabilities, repository.load()?.pendingCommand?.operation)
    val restored = MediaStoreSupervisorControlRepository(app, prefs)
    assertEquals(SupervisorStatusReadResult.Unchanged(expected), restored.readStatus(now))
    app.contentResolver.openOutputStream(statusUri, "wt")?.use { output ->
      output.write(
        statusLine(
          eventSequence = 2,
          commandSequence = 1,
          stage = SupervisorStatusStage.CapabilitiesReady,
        ).toByteArray(),
      )
    }
    assertEquals(
      SupervisorStatusReadResult.Updated(
        SupervisorStatus(
          supervisorBootId = supervisorBootId,
          eventSequence = 2,
          commandSequence = 1,
          timestampEpochSeconds = now,
          stage = SupervisorStatusStage.CapabilitiesReady,
          exitCode = 0,
          planId = "c".repeat(32),
          selectedCapabilities = emptyList(),
          resolvedComponents = resolveComponents(emptyList()),
          readyCapabilities = emptyList(),
        ),
      ),
      restored.readStatus(now),
    )
    assertNull(restored.load()?.pendingCommandSequence)
    assertNull(restored.load()?.pendingCommand)
    restored.clear()
  }

  private fun createDownload(displayName: String) =
    checkNotNull(
      app.contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, "${UUID.randomUUID()}-$displayName")
          put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
          put(MediaStore.Downloads.RELATIVE_PATH, "Download/ClawInOne/test-${UUID.randomUUID()}/")
        },
      ),
    )

  private fun activation(
    commandUri: String,
    statusUri: String,
  ) = SupervisorActivation(
    supervisorId = supervisorId,
    secretHex = secret,
    commandUri = commandUri,
    statusUri = statusUri,
  )

  private fun statusLine(
    eventSequence: Long,
    commandSequence: Long,
    stage: SupervisorStatusStage = SupervisorStatusStage.CapabilitiesRequired,
    setupCode: String = "-",
    completedBytes: Long? = null,
    totalBytes: Long? = null,
  ): String {
    val hasPlan =
      stage != SupervisorStatusStage.SupervisorReady &&
        stage != SupervisorStatusStage.CapabilitiesRequired
    val currentComponent =
      when (stage) {
        SupervisorStatusStage.DownloadingNode,
        SupervisorStatusStage.VerifyingNode,
        SupervisorStatusStage.InstallingNode,
        SupervisorStatusStage.DownloadingOpenClaw,
        SupervisorStatusStage.VerifyingOpenClaw,
        SupervisorStatusStage.InstallingOpenClaw,
        SupervisorStatusStage.VerifyingOpenClawInstallation,
        SupervisorStatusStage.OpenClawReady,
        -> CapabilityComponent.OpenClaw.wireName
        else -> "-"
      }
    val fields =
      listOf(
        SUPERVISOR_CONTROL_PROTOCOL_VERSION.toString(),
        supervisorId,
        supervisorBootId,
        eventSequence.toString(),
        commandSequence.toString(),
        now.toString(),
        stage.wireName,
        if (stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted) "e".repeat(32) else "-",
        if (stage.name.startsWith("Gateway") && stage != SupervisorStatusStage.GatewayNotStarted) "1" else "-",
        "0",
        if (hasPlan) "c".repeat(32) else "-",
        "-",
        if (hasPlan) resolveComponents(emptyList()).joinToString(",", transform = CapabilityComponent::wireName) else "-",
        "-",
        currentComponent,
        completedBytes?.toString() ?: "-",
        totalBytes?.toString() ?: "-",
        setupCode,
      )
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), "HmacSHA256"))
    val signature =
      mac
        .doFinal(fields.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return (fields + signature).joinToString("|") + "\n"
  }
}
