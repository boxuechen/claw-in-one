package ai.openclaw.app.bootstrap

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapDeliveryTest {
  private val command =
    "bash /mnt/shared/Download/ClawInOne/bootstrap-test/bootstrap.sh"

  @Test
  fun successfulPublicationExposesTheFixedOfflineCommand() =
    runTest {
      val copied = mutableListOf<String>()
      var publishCount = 0
      val controller =
        BootstrapDeliveryController(
          publisher =
            BootstrapPublisher {
              publishCount += 1
              BootstrapPublishResult.Published("content://downloads/bootstrap", command)
            },
          copyCommand = copied::add,
        )

      controller.prepare()
      controller.prepare()

      assertEquals(1, publishCount)
      assertTrue(copied.isEmpty())
      assertEquals(
        BootstrapDeliveryState.Ready("content://downloads/bootstrap", command),
        controller.state.value,
      )
      assertTrue(controller.copyReadyCommand())
      assertEquals(listOf(command), copied)
    }

  @Test
  fun failedPublicationCanBeRetried() =
    runTest {
      var shouldFail = true
      val controller =
        BootstrapDeliveryController(
          publisher =
            BootstrapPublisher {
              if (shouldFail) {
                BootstrapPublishResult.Failed(BootstrapDeliveryError.StorageUnavailable)
              } else {
                BootstrapPublishResult.Published("content://downloads/bootstrap", command)
              }
            },
          copyCommand = {},
        )

      controller.prepare()
      assertEquals(
        BootstrapDeliveryState.Failed(BootstrapDeliveryError.StorageUnavailable),
        controller.state.value,
      )
      assertFalse(controller.copyReadyCommand())

      shouldFail = false
      controller.prepare()
      assertTrue(controller.state.value is BootstrapDeliveryState.Ready)
    }

  @Test
  fun clipboardFailureDoesNotBlockPublishedBootstrap() =
    runTest {
      val controller =
        BootstrapDeliveryController(
          publisher =
            BootstrapPublisher {
              BootstrapPublishResult.Published("content://downloads/bootstrap", command)
            },
          copyCommand = { error("Clipboard unavailable") },
        )

      controller.prepare()

      assertEquals(
        BootstrapDeliveryState.Ready("content://downloads/bootstrap", command),
        controller.state.value,
      )
      assertFalse(controller.copyReadyCommand())
    }

  @Test
  fun failedHandoffIsDiscardedBeforePublishingAFreshCommand() =
    runTest {
      var publishCount = 0
      var discardCount = 0
      val controller =
        BootstrapDeliveryController(
          publisher =
            BootstrapPublisher {
              publishCount += 1
              BootstrapPublishResult.Published(
                uri = "content://downloads/bootstrap-$publishCount",
                command = "$command-$publishCount",
              )
            },
          copyCommand = {},
          discardActive = { discardCount += 1 },
        )

      controller.prepare()
      controller.resetAndPrepare()

      assertEquals(2, publishCount)
      assertEquals(1, discardCount)
      assertEquals(
        BootstrapDeliveryState.Ready(
          uri = "content://downloads/bootstrap-2",
          command = "$command-2",
        ),
        controller.state.value,
      )
    }

  @Test
  fun publicationUsesContentAddressedBundleAndOneTimeHandoff() {
    val sources =
      mapOf(
        "bootstrap.sh" to "bootstrap-v1".toByteArray(),
        "claw-in-one-supervisor" to "supervisor-v1".toByteArray(),
      )
    val first = bootstrapPublication(sources, handoffId = "a".repeat(32))
    val repeated = bootstrapPublication(sources.toList().reversed().toMap(), handoffId = "a".repeat(32))
    val secondHandoff = bootstrapPublication(sources, handoffId = "b".repeat(32))
    val changedScript =
      bootstrapPublication(
        sources + ("claw-in-one-supervisor" to "supervisor-v2".toByteArray()),
        handoffId = "a".repeat(32),
      )
    val changedManifest =
      bootstrapPublication(
        sources,
        handoffId = "a".repeat(32),
        release = PINNED_OPENCLAW_RELEASE.copy(nodeArchiveSha256 = "f".repeat(64)),
      )
    val changedArtifactSize =
      bootstrapPublication(
        sources,
        handoffId = "a".repeat(32),
        release =
          PINNED_OPENCLAW_RELEASE.copy(
            packageSizeBytes = PINNED_OPENCLAW_RELEASE.packageSizeBytes + 1,
          ),
      )

    assertEquals(first, repeated)
    assertTrue(first.relativePath.matches(Regex("Download/ClawInOne/bootstrap-[0-9a-f]{32}/")))
    assertEquals(first.relativePath, secondHandoff.relativePath)
    assertTrue(first.handoffRelativePath != secondHandoff.handoffRelativePath)
    assertTrue(first.relativePath != changedScript.relativePath)
    assertTrue(first.relativePath != changedManifest.relativePath)
    assertTrue(first.relativePath != changedArtifactSize.relativePath)
    assertEquals("/mnt/shared/${first.relativePath.removeSuffix("/")}", first.linuxSharedDirectory)
    assertEquals("/mnt/shared/${first.handoffRelativePath.removeSuffix("/")}", first.linuxHandoffDirectory)
    assertEquals("${first.linuxHandoffDirectory}/handoff.events", first.linuxEventsFile)
    assertEquals(
      "Download/ClawInOne/supervisor-${"a".repeat(32)}/",
      first.supervisorRelativePath,
    )
    assertEquals(
      "/mnt/shared/${first.supervisorRelativePath.removeSuffix("/")}/supervisor.command",
      first.linuxSupervisorCommandFile,
    )
    assertEquals(
      "/mnt/shared/${first.supervisorRelativePath.removeSuffix("/")}/supervisor.status",
      first.linuxSupervisorStatusFile,
    )
    assertEquals(
      "CLAW_IN_ONE_CONFIG_FILE='${first.linuxHandoffDirectory}/bootstrap.env' " +
        "bash '${first.linuxSharedDirectory}/bootstrap.sh'",
      first.command,
    )
  }
}
