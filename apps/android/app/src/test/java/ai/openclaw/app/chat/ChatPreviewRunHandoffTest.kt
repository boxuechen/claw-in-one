package ai.openclaw.app.chat

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPreviewRunHandoffTest {
  @Test
  fun canonicalCatalogEntryProducesOneExactStopTarget() =
    runTest {
      val connection = ChatCacheScope("gateway", 7)
      val controller =
        ChatController(
          scope = backgroundScope,
          json = Json { ignoreUnknownKeys = true },
          requestGateway = { _, _ -> error("No Gateway request expected") },
          cacheScope = { connection },
          currentDefaultAgentId = { "main" },
        )
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"reason":"patch","session":{"key":"agent:main:preview","sessionId":"session-1","agentId":"main","hasActiveRun":true,"activeRunIds":["run-1"]}}""",
      )

      val target =
        requireNotNull(
          controller.captureRunStopTarget(
            gatewayStableId = "gateway",
            sessionKey = "agent:main:preview",
            runId = "run-1",
          ),
        )

      assertEquals(ChatComposerOwner("gateway", "main", "agent:main:preview"), target.owner)
      assertEquals("session-1", target.sessionId)
      assertEquals(connection, target.connection)
      assertEquals(setOf("run-1"), target.runIds)
      assertNull(controller.captureRunStopTarget("other-gateway", "agent:main:preview", "run-1"))
      assertNull(controller.captureRunStopTarget("gateway", "agent:main:missing", "run-1"))
    }
}
