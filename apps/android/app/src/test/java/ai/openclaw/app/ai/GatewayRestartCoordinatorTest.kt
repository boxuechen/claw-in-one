package ai.openclaw.app.ai

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayRestartCoordinatorTest {
  @Test
  fun safeRestartCompletesOnlyAfterBothGatewayGenerationsChange() =
    runTest {
      val transport = RestartTransport()
      var identity = identity(transport.connection)
      val coordinator = GatewayRestartCoordinator(this, transport, { identity }, Json, restartTimeoutMs = 60_000)

      coordinator.request("setup", "provider installed")
      runCurrent()
      assertTrue(coordinator.state.value is GatewayRestartState.Restarting)

      transport.connection = transport.connection.copy(generation = 2)
      identity = identity(transport.connection)
      coordinator.onRuntimeIdentityChanged()
      assertTrue(coordinator.state.value is GatewayRestartState.Restarting)

      identity = identity(transport.connection, supervisorGeneration = "generation-2")
      coordinator.onRuntimeIdentityChanged()
      assertTrue(coordinator.state.value is GatewayRestartState.Ready)
      assertEquals(listOf("gateway.restart.preflight", "gateway.restart.request"), transport.requests)
    }

  @Test
  fun deferredRestartReportsActiveWorkAndDoesNotBypassDeferral() =
    runTest {
      val transport = RestartTransport(deferred = true)
      val coordinator = GatewayRestartCoordinator(this, transport, { identity(transport.connection) }, Json, restartTimeoutMs = 60_000)

      coordinator.request("setup", "provider installed")
      runCurrent()

      val waiting = coordinator.state.value as GatewayRestartState.WaitingForSafeRestart
      assertEquals(2, waiting.activeWorkCount)
      assertTrue(transport.requestParams.last().contains("\"skipDeferral\":false"))
    }

  @Test
  fun unknownRestartOutcomeWaitsForReadbackWithoutSubmittingAgain() =
    runTest {
      val transport = RestartTransport(loseRestartReply = true)
      var identity = identity(transport.connection)
      val coordinator = GatewayRestartCoordinator(this, transport, { identity }, Json, restartTimeoutMs = 60_000)

      coordinator.request("setup", "provider installed")
      runCurrent()
      assertTrue(coordinator.state.value is GatewayRestartState.UnknownOutcome)

      coordinator.reconcile()
      coordinator.request("setup", "provider installed")
      runCurrent()
      assertEquals(1, transport.requests.count { it == "gateway.restart.request" })

      transport.connection = transport.connection.copy(generation = 2)
      identity = identity(transport.connection, supervisorGeneration = "generation-2")
      coordinator.onRuntimeIdentityChanged()
      assertTrue(coordinator.state.value is GatewayRestartState.Ready)
    }

  private class RestartTransport(
    private val deferred: Boolean = false,
    private val loseRestartReply: Boolean = false,
  ) : AiGatewayTransport {
    var connection = AiGatewayConnection("gateway", 1, 1, requiredAiGatewayMethods, "main", true)
    val requests = mutableListOf<String>()
    val requestParams = mutableListOf<String>()

    override fun capture(): AiGatewayConnection = connection

    override fun publish(
      connection: AiGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (connection != this.connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: AiGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      requests += method
      requestParams += params
      return when (method) {
        "gateway.restart.preflight" -> preflight()
        "gateway.restart.request" -> {
          if (loseRestartReply) throw GatewayRequestOutcomeUnknown("lost")
          val status = if (deferred) "deferred" else "scheduled"
          """{"ok":true,"status":"$status","preflight":${preflight()}}"""
        }
        else -> error("Unexpected method")
      }
    }

    private fun preflight() =
      if (deferred) {
        """{"safe":false,"counts":{"totalActive":2},"summary":"Waiting for 2 tasks"}"""
      } else {
        """{"safe":true,"counts":{"totalActive":0},"summary":"Ready"}"""
      }
  }

  companion object {
    private fun identity(
      connection: AiGatewayConnection,
      supervisorGeneration: String = "generation-1",
    ) = GatewayRuntimeIdentity("supervisor", supervisorGeneration, connection)
  }
}
