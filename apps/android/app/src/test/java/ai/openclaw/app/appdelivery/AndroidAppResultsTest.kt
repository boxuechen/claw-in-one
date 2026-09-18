package ai.openclaw.app.appdelivery

import ai.openclaw.app.vscreen.VScreenRunReference
import ai.openclaw.app.vscreen.VScreenTarget
import ai.openclaw.app.vscreen.VScreenWorkloadEvent
import ai.openclaw.app.vscreen.producer.AndroidVScreenWorkloadRegistry
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidAppResultsTest {
  @Test
  fun durableResultOpensOnlyAfterExactInstalledVerification() =
    runTest {
      var saved = emptyList<AndroidAppResult>()
      var opened = 0
      val controller =
        AndroidAppResultsController(this, saved, { saved = it }, { true }, {
          opened += 1
          true
        })
      controller.record("gateway", install())
      controller.feature.open(saved.single())
      runCurrent()
      assertEquals(1, opened)

      val restored =
        AndroidAppResultsController(this, saved, {}, { false }, {
          opened += 1
          true
        })
      restored.feature.open(saved.single())
      runCurrent()
      assertEquals(1, opened)
      assertNotNull(restored.feature.state.value.error)
    }

  @Test
  fun installedEventIsParsedWithoutAnyVScreenPayload() {
    val parsed =
      parseAndroidAppInstallGatewayEvent(
        "agent",
        """{"runId":"run-1","sessionKey":"agent:main:demo","stream":"claw-in-one-android-app.installed","data":{"protocolVersion":1,"phase":"installed","executionSessionId":"session-1","toolCallId":"install-1","packageName":"com.example.demo","versionCode":"1","sha256":"${"a".repeat(64)}"}}""",
        Json,
      )
    assertEquals(install(), parsed)
    assertNull(
      parseAndroidAppInstallGatewayEvent(
        "agent",
        """{"runId":"run-1","sessionKey":"agent:main:demo","stream":"claw-in-one-android-app.installed","data":{"protocolVersion":1,"phase":"installed","executionSessionId":"session-1","toolCallId":"install-1","packageName":"com.example.demo","versionCode":"1","sha256":"bad"}}""",
        Json,
      ),
    )
  }

  @Test
  fun targetResolverUsesOnlyAcceptedVScreenWorkloadIdentity() {
    val resolver = AndroidVScreenWorkloadRegistry()
    assertNull(resolver.resolve(target()))
    assertEquals(true, resolver.accept(workload()))
    assertEquals("com.example.demo", resolver.resolve(target()))
    assertNull(resolver.resolve(target().copy(workloadRequestId = "other-workload")))
  }

  private fun install() =
    AndroidAppInstallEvent(
      sessionKey = "agent:main:demo",
      sessionId = "session-1",
      runId = "run-1",
      toolCallId = "install-1",
      packageName = "com.example.demo",
      versionCode = 1,
      sha256 = "a".repeat(64),
    )

  private fun workload() =
    VScreenWorkloadEvent(
      VScreenRunReference("agent:main:demo", "session-1", "run-1"),
      PRODUCER,
      WORKLOAD,
      "tool-1",
      buildJsonObject {
        put("targetPackage", "com.example.demo")
      },
    )

  private fun target() =
    VScreenTarget(
      revision = 1,
      producerId = PRODUCER,
      workloadRequestId = WORKLOAD,
      attachmentId = "82345678-1234-4123-8123-123456789abc",
      targetRef = "target:one",
      targetGeneration = 1,
      sourceGeneration = 1,
      displayId = 7,
      width = 720,
      height = 1560,
      dpi = 320,
      rotation = 0,
    )

  private companion object {
    const val PRODUCER = "claw-in-one-android-vscreen-producer"
    const val WORKLOAD = "92345678-1234-4123-8123-123456789abc"
  }
}
