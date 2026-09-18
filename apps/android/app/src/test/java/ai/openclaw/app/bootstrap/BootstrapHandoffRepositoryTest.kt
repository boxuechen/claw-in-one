package ai.openclaw.app.bootstrap

import ai.openclaw.app.SecurePrefs
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootstrapHandoffRepositoryTest {
  private val app get() = RuntimeEnvironment.getApplication()

  @Test
  fun activeSessionAndProgressCheckpointSurviveRepositoryRecreation() {
    val backing =
      app.getSharedPreferences(
        "bootstrap-handoff-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = backing)
    val initial = session()
    val first = MediaStoreBootstrapHandoffRepository(app, prefs)

    assertTrue(first.saveActive(initial))
    assertTrue(first.checkpoint(initial.requestId, 1, SupervisorProgress.BootstrapExecuted))

    val restored = MediaStoreBootstrapHandoffRepository(app, prefs).loadActive()
    assertEquals(1L, restored?.lastSequence)
    assertEquals(SupervisorProgress.BootstrapExecuted.ordinal, restored?.lastProgressOrdinal)
    assertFalse(restored.toString().contains(initial.handoffSecretHex))
    assertFalse(restored.toString().contains(initial.supervisorSecretHex))

    first.discardActive(initial.requestId)
    assertNull(MediaStoreBootstrapHandoffRepository(app, prefs).loadActive())
  }

  @Test
  fun incompleteTrailingLineIsIgnoredUntilItsNewlineArrives() {
    assertEquals(
      BootstrapHandoffReadResult.Content(listOf("first", "second")),
      decodeCompleteBootstrapHandoffLines("first\nsecond\npartial".toByteArray()),
    )
    assertEquals(
      BootstrapHandoffReadResult.Content(emptyList()),
      decodeCompleteBootstrapHandoffLines("partial".toByteArray()),
    )
  }

  @Test
  fun oversizedAndInvalidUtf8LogsFailClosed() {
    assertEquals(
      BootstrapHandoffReadResult.TooLarge,
      decodeCompleteBootstrapHandoffLines(ByteArray(BOOTSTRAP_HANDOFF_MAX_BYTES + 1)),
    )
    assertEquals(
      BootstrapHandoffReadResult.InvalidEncoding,
      decodeCompleteBootstrapHandoffLines(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte())),
    )
  }

  @Test
  fun malformedSessionIsNeverPersisted() {
    val backing =
      app.getSharedPreferences(
        "bootstrap-handoff-${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val repository =
      MediaStoreBootstrapHandoffRepository(
        app,
        SecurePrefs(app, securePrefsOverride = backing),
      )

    assertFalse(repository.saveActive(session().copy(handoffSecretHex = "not-a-secret")))
    assertNull(repository.loadActive())
  }

  private fun session(): BootstrapHandoffSession =
    BootstrapHandoffSession(
      requestId = "a".repeat(32),
      handoffSecretHex = "b".repeat(64),
      supervisorSecretHex = "c".repeat(64),
      bootstrapUri = "content://media/external/downloads/1",
      environmentUri = "content://media/external/downloads/2",
      eventsUri = "content://media/external/downloads/3",
      supervisorCommandUri = "content://media/external/downloads/4",
      supervisorStatusUri = "content://media/external/downloads/5",
      command = "CLAW_IN_ONE_CONFIG_FILE='bootstrap.env' bash 'bootstrap.sh'",
      createdAtEpochSeconds = 1_788_400_000L,
    )
}
