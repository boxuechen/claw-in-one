package ai.openclaw.app.approval

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalControllerTest {
  @Test
  fun featureSharesTheInboxAndKeepsFixtureRefreshInert() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      val fixture = controller.feature(refreshEnabled = false)
      assertSame(controller.state, fixture.state)
      fixture.actions.refresh()
      runCurrent()
      assertTrue(transport.requests.isEmpty())

      controller.feature(refreshEnabled = true).actions.refresh()
      runCurrent()
      assertEquals(
        "plugin:one",
        fixture.state.value.rows
          .single()
          .id,
      )
    }

  @Test
  fun featureDecisionCapturesBeforeDispatchAndSurvivesCallerCancellation() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      val feature = controller.feature(refreshEnabled = true)
      controller.refresh()
      val acknowledgement = CompletableDeferred<String>()
      transport.respond = { _, method, _ ->
        check(method == "approval.resolve")
        acknowledgement.await()
      }
      val before = transport.connection
      val caller =
        async(start = CoroutineStart.UNDISPATCHED) {
          feature.actions.resolve("plugin:one", ApprovalKind.Plugin, "deny")
          CompletableDeferred<Unit>().await()
        }
      assertEquals(
        "deny",
        feature.state.value.rows
          .single()
          .resolvingDecision,
      )
      feature.actions.resolve("plugin:one", ApprovalKind.Plugin, "deny")
      caller.cancel()
      runCurrent()
      assertEquals(1, transport.requests.count { it.method == "approval.resolve" })
      assertEquals(before, transport.requests.last().connection)
      acknowledgement.complete(resolve(pluginTerminal(), applied = true))
      runCurrent()
      val notice = checkNotNull(feature.state.value.notice)
      assertEquals(ApprovalStatus.Denied, notice.status)
      feature.actions.dismiss(notice.copy(publication = notice.publication + 1))
      assertEquals(notice, feature.state.value.notice)
      feature.actions.dismiss(notice)
      assertNull(feature.state.value.notice)
    }

  @Test
  fun retainedFeatureActionsNeverLookUpAReplacementOwner() =
    runTest {
      val firstTransport = pendingPluginTransport()
      val secondTransport = pendingPluginTransport()
      val first = ApprovalController(backgroundScope, firstTransport) { testScheduler.currentTime }
      val second = ApprovalController(backgroundScope, secondTransport) { testScheduler.currentTime }
      first.refresh()
      second.refresh()
      val firstFeature = first.feature(refreshEnabled = true)
      second.feature(refreshEnabled = true)
      firstTransport.respond = { _, method, _ ->
        check(method == "approval.resolve")
        resolve(pluginTerminal(), applied = true)
      }

      firstFeature.actions.resolve("plugin:one", ApprovalKind.Plugin, "deny")
      runCurrent()

      assertTrue(
        first.state.value.rows
          .isEmpty(),
      )
      assertEquals(
        "plugin:one",
        second.state.value.rows
          .single()
          .id,
      )
      assertTrue(secondTransport.requests.none { it.method == "approval.resolve" })
    }

  @Test
  fun refreshHydratesExecAndPluginThroughCanonicalGet() =
    runTest {
      val transport = FakeTransport()
      transport.respond = { _, method, params ->
        when (method) {
          "exec.approval.list" -> list("exec:one")
          "plugin.approval.list" -> list("plugin:one")
          "approval.get" -> if (params.contains("exec:one")) get(execRecord()) else get(pluginRecord())
          else -> error("unexpected $method")
        }
      }
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }

      controller.refresh()

      assertEquals(
        listOf(ApprovalKind.Exec, ApprovalKind.Plugin),
        controller.state.value.rows
          .map { it.kind },
      )
      assertTrue(
        controller.state.value.rows[0]
          .details is ApprovalDetails.Exec,
      )
      assertTrue(
        controller.state.value.rows[1]
          .details is ApprovalDetails.Plugin,
      )
      assertEquals(
        listOf("exec.approval.list", "plugin.approval.list", "approval.get", "approval.get"),
        transport.requests.map { it.method },
      )
    }

  @Test
  fun pluginResolutionUsesTypedCanonicalWriteAndRetiresTheCard() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()
      transport.respond = { _, method, _ ->
        when (method) {
          "approval.resolve" -> resolve(pluginTerminal(), applied = true)
          else -> error("unexpected $method")
        }
      }

      controller.resolve("plugin:one", ApprovalKind.Plugin, "deny")

      val write = transport.requests.last()
      assertEquals("approval.resolve", write.method)
      assertEquals("""{"id":"plugin:one","kind":"plugin","decision":"deny"}""", write.params)
      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
      assertEquals(
        ApprovalStatus.Denied,
        controller.state.value.notice
          ?.status,
      )
      assertTrue(
        controller.state.value.notice
          ?.appliedHere == true,
      )
    }

  @Test
  fun execResolutionPreservesExecKindAndAllowDecision() =
    runTest {
      val transport = FakeTransport()
      transport.respond = { _, method, params ->
        when (method) {
          "exec.approval.list" -> list("exec:one")
          "plugin.approval.list" -> "[]"
          "approval.get" -> get(execRecord())
          "approval.resolve" -> {
            assertEquals("""{"id":"exec:one","kind":"exec","decision":"allow-once"}""", params)
            resolve(execTerminal(), applied = true)
          }
          else -> error("unexpected $method")
        }
      }
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()

      controller.resolve("exec:one", ApprovalKind.Exec, "allow-once")

      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
      assertEquals(
        ApprovalStatus.Allowed,
        controller.state.value.notice
          ?.status,
      )
      assertEquals(
        "allow-once",
        controller.state.value.notice
          ?.decision,
      )
    }

  @Test
  fun ownAcknowledgementAfterTerminalEventCorrectsReviewerNoticeWithoutResurrectingCard() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()
      transport.respond = { _, method, _ ->
        check(method == "approval.resolve")
        controller.onEvent("session.approval", """{"sessionKey":"agent:main","sourceSessionKey":"agent:main","updatedAtMs":20,"phase":"terminal","approval":${pluginTerminal()}}""")
        assertTrue(
          controller.state.value.rows
            .isEmpty(),
        )
        assertFalse(
          controller.state.value.notice!!
            .appliedHere,
        )
        resolve(pluginTerminal(), applied = true)
      }
      controller.resolve("plugin:one", ApprovalKind.Plugin, "deny")
      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
      assertTrue(
        controller.state.value.notice!!
          .appliedHere,
      )
      assertEquals(1, transport.requests.count { it.method == "approval.resolve" })
    }

  @Test
  fun losingResolverPublishesTheRecordedWinner() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()
      transport.respond = { _, method, _ ->
        when (method) {
          "approval.resolve" -> resolve(pluginTerminal(), applied = false)
          else -> error("unexpected $method")
        }
      }

      controller.resolve("plugin:one", ApprovalKind.Plugin, "deny")

      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
      assertTrue(
        controller.state.value.notice
          ?.appliedHere == false,
      )
      assertEquals(1, transport.requests.count { it.method == "approval.resolve" })
    }

  @Test
  fun duplicateTapCannotEnqueueASecondWrite() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()
      val result = CompletableDeferred<String>()
      transport.respond = { _, method, _ ->
        when (method) {
          "approval.resolve" -> result.await()
          else -> error("unexpected $method")
        }
      }

      val first = async(start = CoroutineStart.UNDISPATCHED) { controller.resolve("plugin:one", ApprovalKind.Plugin, "deny") }
      controller.resolve("plugin:one", ApprovalKind.Plugin, "deny")

      assertEquals(1, transport.requests.count { it.method == "approval.resolve" })
      result.complete(resolve(pluginTerminal(), applied = true))
      first.await()
      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
    }

  @Test
  fun unknownWriteNeverReplaysAndStaysFrozenWhileReadbackIsPending() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()
      transport.respond = { _, method, _ ->
        when (method) {
          "approval.resolve" -> throw GatewayRequestOutcomeUnknown("lost response")
          "exec.approval.list" -> "[]"
          "plugin.approval.list" -> list("plugin:one")
          "approval.get" -> get(pluginRecord())
          else -> error("unexpected $method")
        }
      }

      controller.resolve("plugin:one", ApprovalKind.Plugin, "deny")
      runCurrent()

      assertEquals(1, transport.requests.count { it.method == "approval.resolve" })
      val row =
        controller.state.value.rows
          .single()
      assertEquals("deny", row.resolvingDecision)
      assertEquals(ApprovalFailure.OutcomeUnknown, row.failure)
    }

  @Test
  fun reconnectReadbackAcceptsDurableWinnerWithoutReplayingWrite() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()
      transport.respond = { _, method, _ ->
        when (method) {
          "approval.resolve" -> {
            transport.connection = transport.connection.copy(generation = 2)
            throw GatewayRequestOutcomeUnknown("socket replaced")
          }
          "exec.approval.list" -> "[]"
          "plugin.approval.list" -> "[]"
          "approval.get" -> get(pluginTerminal())
          else -> error("unexpected $method")
        }
      }

      controller.resolve("plugin:one", ApprovalKind.Plugin, "deny")
      runCurrent()

      assertEquals(1, transport.requests.count { it.method == "approval.resolve" })
      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
      assertEquals(
        ApprovalStatus.Denied,
        controller.state.value.notice
          ?.status,
      )
      assertTrue(
        controller.state.value.notice
          ?.appliedHere == false,
      )
    }

  @Test
  fun definitiveFailureReleasesTheCardForAnExplicitRetry() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()
      transport.respond = { _, method, _ ->
        when (method) {
          "approval.resolve" -> throw GatewayRequestNotEnqueued("not sent")
          else -> error("unexpected $method")
        }
      }

      controller.resolve("plugin:one", ApprovalKind.Plugin, "deny")

      val row =
        controller.state.value.rows
          .single()
      assertNull(row.resolvingDecision)
      assertEquals(ApprovalFailure.Resolve, row.failure)
    }

  @Test
  fun timeoutDisablesActionsAndRefreshesAuthoritativeState() =
    runTest {
      val transport = pendingPluginTransport()
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      controller.refresh()

      advanceTimeBy(10_000)
      runCurrent()

      assertTrue(
        controller.state.value.rows
          .single()
          .allowedDecisions
          .isEmpty(),
      )
      assertEquals(0, transport.requests.count { it.method == "approval.resolve" })
    }

  @Test
  fun staleRefreshCannotOverwriteAReplacementRefresh() =
    runTest {
      val firstList = CompletableDeferred<String>()
      var holdFirst = true
      val transport = FakeTransport()
      transport.respond = { _, method, params ->
        when (method) {
          "exec.approval.list" -> if (holdFirst) firstList.await() else "[]"
          "plugin.approval.list" -> if (holdFirst) "[]" else list("plugin:one")
          "approval.get" -> if (params.contains("exec:one")) get(execRecord()) else get(pluginRecord())
          else -> error("unexpected $method")
        }
      }
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }
      val stale = async(start = CoroutineStart.UNDISPATCHED) { controller.refresh() }
      holdFirst = false
      controller.refresh()
      firstList.complete(list("exec:one"))
      stale.await()

      assertEquals(
        listOf("plugin:one"),
        controller.state.value.rows
          .map { it.id },
      )
    }

  @Test
  fun missingCanonicalCatalogFailsClosedWithoutRequests() =
    runTest {
      val transport = FakeTransport(connection = ApprovalConnection("gateway", 1, 1, setOf("plugin.approval.list")))
      val controller = ApprovalController(this, transport) { testScheduler.currentTime }

      controller.refresh()

      assertTrue(transport.requests.isEmpty())
      assertEquals(ApprovalFailure.LoadInbox, controller.state.value.failure)
    }

  private fun pendingPluginTransport(): FakeTransport {
    val transport = FakeTransport()
    transport.respond = { _, method, params ->
      when (method) {
        "exec.approval.list" -> "[]"
        "plugin.approval.list" -> list("plugin:one")
        "approval.get" -> {
          check(params == """{"id":"plugin:one"}""")
          get(pluginRecord())
        }
        else -> error("unexpected $method")
      }
    }
    return transport
  }

  private data class Request(
    val connection: ApprovalConnection,
    val method: String,
    val params: String,
  )

  private class FakeTransport(
    var connection: ApprovalConnection = ApprovalConnection("gateway", 1, 1, ApprovalController.REQUIRED_METHODS),
  ) : ApprovalTransport {
    val requests = mutableListOf<Request>()
    var respond: suspend (ApprovalConnection, String, String) -> String = { _, method, _ -> error("unexpected $method") }

    override fun capture(): ApprovalConnection = connection

    override fun publish(
      connection: ApprovalConnection,
      block: () -> Unit,
    ): Boolean {
      if (this.connection != connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: ApprovalConnection,
      method: String,
      params: String,
    ): String {
      requests += Request(connection, method, params)
      return respond(connection, method, params)
    }
  }

  private companion object {
    fun list(id: String): String = """[{"id":"$id","createdAtMs":10,"expiresAtMs":10000}]"""

    fun get(record: String): String = """{"approval":$record}"""

    fun resolve(
      record: String,
      applied: Boolean,
    ): String = """{"applied":$applied,"approval":$record}"""

    fun execRecord(): String = """{"id":"exec:one","urlPath":"/approve/exec%3Aone","createdAtMs":10,"expiresAtMs":10000,"presentation":{"kind":"exec","commandText":"printf safe","commandPreview":"printf","warningText":null,"host":"gateway","nodeId":null,"agentId":"main","allowedDecisions":["allow-once","allow-always","deny"]},"status":"pending"}"""

    fun pluginRecord(): String = """{"id":"plugin:one","urlPath":"/approve/plugin%3Aone","createdAtMs":10,"expiresAtMs":10000,"presentation":{"kind":"plugin","title":"Install APK","description":"Install the reviewed artifact.","severity":"critical","pluginId":"claw-in-one","toolName":"android_install","agentId":"main","allowedDecisions":["allow-once","deny"]},"status":"pending"}"""

    fun pluginTerminal(): String = pluginRecord().replace("\"status\":\"pending\"", "\"status\":\"denied\",\"decision\":\"deny\",\"resolvedAtMs\":20,\"reason\":\"user\"")

    fun execTerminal(): String = execRecord().replace("\"status\":\"pending\"", "\"status\":\"allowed\",\"decision\":\"allow-once\",\"resolvedAtMs\":20,\"reason\":\"user\"")
  }
}
