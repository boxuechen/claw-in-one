package ai.openclaw.app.vscreen

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VScreenProtocolTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun descriptorKeepsDisplaySourceAndWorkloadGenerationsSeparate() {
    val descriptor = parseVScreenDescriptor(descriptorJson(), PRODUCER, WORKLOAD, json, 1_000)
    assertEquals(ATTACHMENT, descriptor?.attachmentId)
    assertEquals(4L, descriptor?.targetGeneration)
    assertEquals(9L, descriptor?.sourceGeneration)
    assertEquals(3L, descriptor?.workloadGeneration)
    assertEquals(VScreenForeground.Workload, descriptor?.foreground)
    assertTrue(descriptor?.capabilities?.contains("pointer-v1") == true)
    assertNull(parseVScreenDescriptor(descriptorJson(), PRODUCER, "another-workload", json, 1_000))
  }

  @Test
  fun workloadEventRetainsOriginWithoutOwningDisplayLifetime() {
    val event = parseVScreenGatewayEvent("agent", eventJson(), json)
    assertEquals(VScreenRunReference("agent:main:demo", "session-1", "run-1"), event?.run)
    assertEquals(WORKLOAD, event?.workloadRequestId)
    assertEquals(
      "com.example.demo",
      event
        ?.producerPayload
        ?.get("targetPackage")
        ?.toString()
        ?.trim('"'),
    )
    assertNull(parseVScreenGatewayEvent("chat", eventJson(), json))
  }

  @Test
  fun requestPayloadsUseOnlyVScreenV3Identities() {
    assertEquals("""{"protocolVersion":3,"producerId":"$PRODUCER"}""", vscreenEnsureParams(PRODUCER))
    assertEquals(
      """{"protocolVersion":3,"attachmentId":"$ATTACHMENT","targetGeneration":4,"sourceGeneration":9}""",
      vscreenCloseParams(ATTACHMENT, 4, 9),
    )
    assertTrue(
      parseVScreenClosed(
        """{"protocolVersion":3,"status":"closed","attachmentId":"$ATTACHMENT","targetGeneration":4,"sourceGeneration":9}""",
        ATTACHMENT,
        4,
        9,
        json,
      ),
    )
    assertEquals(
      """{"protocolVersion":3,"attachmentId":"$ATTACHMENT","workloadRequestId":"$WORKLOAD"}""",
      vscreenFramePresentedParams(ATTACHMENT, WORKLOAD),
    )
    assertTrue(
      parseVScreenFramePresented(
        """{"protocolVersion":3,"status":"recorded","attachmentId":"$ATTACHMENT","workloadRequestId":"$WORKLOAD"}""",
        ATTACHMENT,
        WORKLOAD,
        json,
      ),
    )
    assertTrue(
      parseVScreenFramePresented(
        """{"protocolVersion":3,"status":"recorded","attachmentId":"$ATTACHMENT","foreground":"home"}""",
        ATTACHMENT,
        null,
        json,
      ),
    )
    assertEquals(
      false,
      parseVScreenFramePresented(
        """{"protocolVersion":3,"status":"recorded","attachmentId":"$ATTACHMENT","workloadRequestId":"other-workload"}""",
        ATTACHMENT,
        WORKLOAD,
        json,
      ),
    )
    assertTrue(parseVScreenUnavailable("""{"protocolVersion":3,"status":"unavailable"}""", json))
  }

  private fun descriptorJson() = """{"protocolVersion":3,"status":"ready","kind":"display","attachmentId":"$ATTACHMENT","producerId":"$PRODUCER","targetRef":"target:one","targetGeneration":4,"sourceGeneration":9,"foreground":"workload","streamPath":"/claw-in-one/vscreen","codec":"h264","token":"${"a".repeat(64)}","expiresAtMs":2000,"display":{"id":7,"width":720,"height":1560,"dpi":320,"rotation":0},"capabilities":["video/h264","pointer-v1"],"workload":{"generation":3,"requestId":"$WORKLOAD"}}"""

  private fun eventJson() = """{"sessionKey":"agent:main:demo","runId":"run-1","stream":"claw-in-one-vscreen.workload","data":{"protocolVersion":3,"phase":"offered","producerId":"$PRODUCER","workloadRequestId":"$WORKLOAD","executionSessionId":"session-1","toolCallId":"tool-1","producerPayload":{"targetPackage":"com.example.demo"}}}"""

  private companion object {
    const val PRODUCER = "claw-in-one-android-vscreen-producer"
    const val WORKLOAD = "92345678-1234-4123-8123-123456789abc"
    const val ATTACHMENT = "82345678-1234-4123-8123-123456789abc"
  }
}
