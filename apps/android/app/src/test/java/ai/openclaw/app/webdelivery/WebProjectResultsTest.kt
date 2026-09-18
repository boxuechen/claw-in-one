package ai.openclaw.app.webdelivery

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebProjectResultsTest {
  @Test
  fun exactReadyEventReplacesOnlyTheSameProjectAndOpensAfterGatewayValidation() =
    runTest {
      var saved = emptyList<WebProjectResult>()
      var opened = 0
      val controller =
        WebProjectResultsController(this, saved, { saved = it }) {
          opened += 1
          true
        }
      controller.record("gateway-1", ready())
      controller.record("gateway-1", ready().copy(resultId = "result-2", generation = 2))
      assertEquals(1, saved.size)
      assertEquals("result-2", saved.single().resultId)
      controller.feature.open(saved.single())
      runCurrent()
      assertEquals(1, opened)
      assertNull(controller.feature.state.value.error)

      val stale = WebProjectResultsController(this, saved, {}, { false })
      stale.feature.open(saved.single())
      runCurrent()
      assertNotNull(stale.feature.state.value.error)

      controller.record("gateway-1", stopped(generation = 1))
      assertEquals(1, saved.size)
      controller.record("gateway-1", stopped(generation = 2))
      assertEquals(emptyList<WebProjectResult>(), saved)
      assertEquals(emptyList<WebProjectResult>(), controller.feature.state.value.results)
    }

  @Test
  fun parserAcceptsOnlyTheBoundedLoopbackReverseResult() {
    val payload =
      """{"runId":"run-1","sessionKey":"agent:main:web","stream":"claw-in-one-web-project.result","data":{"protocolVersion":1,"phase":"ready","executionSessionId":"session-1","toolCallId":"serve-1","resultId":"result-1","projectId":"project-1","generation":1,"targetId":"${"a".repeat(64)}","url":"http://127.0.0.1:38444/","appName":"Web Lab"}}"""
    assertEquals(ready(), parseWebProjectGatewayEvent("agent", payload, Json))
    assertNull(parseWebProjectGatewayEvent("agent", payload.replace("38444", "5173"), Json))
    assertNull(parseWebProjectGatewayEvent("agent", payload.replace("127.0.0.1", "0.0.0.0"), Json))
    val stoppedPayload =
      """{"runId":"run-1","sessionKey":"agent:main:web","stream":"claw-in-one-web-project.result","data":{"protocolVersion":1,"phase":"stopped","executionSessionId":"session-1","toolCallId":"serve-1","projectId":"project-1","generation":1}}"""
    assertEquals(
      stopped(),
      parseWebProjectGatewayEvent("agent", stoppedPayload, Json),
    )
  }

  private fun ready() =
    WebProjectGatewayEvent.Ready(
      sessionKey = "agent:main:web",
      sessionId = "session-1",
      runId = "run-1",
      toolCallId = "serve-1",
      resultId = "result-1",
      projectId = "project-1",
      generation = 1,
      targetId = "a".repeat(64),
      url = "http://127.0.0.1:38444/",
      appName = "Web Lab",
    )

  private fun stopped(generation: Int = 1) =
    WebProjectGatewayEvent.Stopped(
      sessionKey = "agent:main:web",
      sessionId = "session-1",
      runId = "run-1",
      toolCallId = "serve-1",
      projectId = "project-1",
      generation = generation,
    )
}
