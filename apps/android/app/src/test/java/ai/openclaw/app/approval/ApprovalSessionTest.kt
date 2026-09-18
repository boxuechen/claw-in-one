package ai.openclaw.app.approval

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalSessionTest {
  @Test
  fun codecRetainsSourceAudienceReplayCompletenessAndTerminalOrigin() {
    val codec = ApprovalCodec()
    val event = codec.sessionEvent(event(record("one", CHILD), audience = PARENT))
    assertEquals(PARENT, event.audience)
    assertEquals(CHILD, event.sourceSessionKey)
    assertEquals(CHILD, (event.approval as ApprovalSnapshot.Pending).row.attribution.sourceSessionKey)
    val replay = codec.subscription(replay(CHILD, record("one", CHILD), truncated = true))
    assertTrue(replay.truncated)
    assertEquals(
      CHILD,
      replay.approvals
        .single()
        .attribution.sourceSessionKey,
    )
    val terminal = codec.get(get(terminal("one")), "one", ApprovalKind.Plugin) as ApprovalSnapshot.Terminal
    assertEquals(ApprovalSource(CHILD, "main"), terminal.source)
    assertEquals(30L, terminal.resolvedAtMs)
    assertThrows(IllegalArgumentException::class.java) { codec.sessionEvent(event(record("one", CHILD)).replace("\"phase\":\"pending\"", "\"phase\":\"terminal\"")) }
    assertThrows(IllegalArgumentException::class.java) { codec.subscription(replay(CHILD, "${record("one", CHILD)},${record("one", CHILD)}")) }
  }

  @Test
  fun parentAudienceIsNotSourceAndGlobalReadCannotEraseAttribution() =
    runTest {
      val transport = FakeTransport()
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      controller.onEvent("session.approval", event(record("one", CHILD), audience = PARENT))
      assertTrue(
        controller.state.value
          .pendingFor(ApprovalSession(PARENT))
          .isEmpty(),
      )
      assertEquals(
        1,
        controller.state.value
          .pendingFor(ApprovalSession(CHILD))
          .size,
      )
      transport.records["one"] = record("one", null)
      controller.refresh()
      val row =
        controller.state.value.rows
          .single()
      assertEquals(CHILD, row.attribution.sourceSessionKey)
      assertTrue(PARENT in row.attribution.audiences)
    }

  @Test
  fun canonicalTerminalWinsAgainstInflightReadAndLatePendingEvent() =
    runTest {
      val transport = FakeTransport()
      transport.records["one"] = record("one", null)
      val held = CompletableDeferred<String>()
      transport.respondOverride = { method, _ -> if (method == "approval.get") held.await() else null }
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      controller.onEvent("session.approval", event(record("one", CHILD)))
      val refresh = backgroundScope.launch { controller.refresh() }
      runCurrent()
      controller.onEvent("session.approval", event(terminal("one"), phase = "terminal", time = 30))
      val notice = controller.state.value.notice
      held.complete(get(record("one", null)))
      runCurrent()
      refresh.join()
      controller.onEvent("session.approval", event(record("one", CHILD), time = 20))
      controller.onEvent("session.approval", event(terminal("one"), phase = "terminal", time = 30))
      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
      assertEquals(notice, controller.state.value.notice)
      assertEquals(1, controller.state.value.outcomes.size)
      assertEquals(
        CHILD,
        controller.state.value.outcomes
          .single()
          .attribution.sourceSessionKey,
      )
    }

  @Test
  fun conflictingCanonicalSourcesRemainGlobal() =
    runTest {
      val controller = ApprovalController(backgroundScope, FakeTransport()) { testScheduler.currentTime }
      controller.onEvent("session.approval", event(record("one", CHILD)))
      controller.onEvent("session.approval", event(record("one", PARENT), source = PARENT, time = 21))
      val row =
        controller.state.value.rows
          .single()
      assertTrue(row.attribution.conflicted)
      assertNull(row.attribution.sourceSessionKey)
      assertEquals(listOf(row), controller.state.value.unattributed)
      assertTrue(
        controller.state.value
          .pendingFor(ApprovalSession(CHILD))
          .isEmpty(),
      )
    }

  @Test
  fun unknownResolutionStaysFrozenAcrossSessionEventsAndTruncatedReplay() =
    runTest {
      val transport = FakeTransport()
      transport.records["one"] = record("one", null)
      transport.replays[CHILD] = replay(CHILD, truncated = true)
      transport.respondOverride = { method, _ -> if (method == "approval.resolve") throw GatewayRequestOutcomeUnknown("lost") else null }
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      controller.onEvent("session.approval", event(record("one", CHILD)))
      controller.resolve("one", ApprovalKind.Plugin, "deny")
      controller.setSessions(ApprovalSession(CHILD), emptySet())
      runCurrent()
      controller.onEvent("session.approval", event(record("one", CHILD), time = 40))
      assertEquals(
        ApprovalFailure.OutcomeUnknown,
        controller.state.value.rows
          .single()
          .failure,
      )
      assertEquals(
        "deny",
        controller.state.value.rows
          .single()
          .resolvingDecision,
      )
      assertTrue(ApprovalSession(CHILD) in controller.state.value.incompleteSessions)
      controller.resolve("one", ApprovalKind.Plugin, "allow-once")
      assertEquals(1, transport.calls.count { it.first == "approval.resolve" })
    }

  @Test
  fun requestMetadataIsOnlyAHintUntilSubscriptionProvidesCanonicalOrigin() =
    runTest {
      val transport = FakeTransport()
      transport.records["one"] = record("one", null)
      transport.listSource = "alias"
      transport.replays["alias"] = replay(CHILD, record("one", CHILD))
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      controller.refresh()
      assertNull(
        controller.state.value.rows
          .single()
          .attribution.sourceSessionKey,
      )
      runCurrent()
      assertEquals(
        CHILD,
        controller.state.value.rows
          .single()
          .attribution.sourceSessionKey,
      )
      assertEquals(CHILD, controller.state.value.canonicalKey(ApprovalSession("alias", "main")))
    }

  @Test
  fun aliasLeaseIsRetainedWhileAnotherTargetStillOwnsItsCanonicalStream() =
    runTest {
      val transport = FakeTransport()
      transport.replays["alias"] = replay(CHILD)
      val replays = mutableListOf<String>()
      val subscriptions = ApprovalSessionSubscriptions(backgroundScope, transport, { _, _, replay -> replays += replay.audience }, { _, _ -> error("unexpected") })
      subscriptions.update(setOf(ApprovalSession("alias"), ApprovalSession(CHILD)))
      runCurrent()
      subscriptions.update(setOf(ApprovalSession(CHILD)))
      runCurrent()
      assertEquals(2, replays.size)
      assertFalse(transport.calls.any { it.first == "sessions.messages.unsubscribe" })
      subscriptions.update(emptySet())
      runCurrent()
      assertEquals(1, transport.calls.count { it.first == "sessions.messages.unsubscribe" })
    }

  @Test
  fun reconnectDiscardsOldSubscriptionResultAndResubscribesWithoutWritingDecisions() =
    runTest {
      val transport = FakeTransport()
      val held = CompletableDeferred<String>()
      transport.respondOverride = { method, _ -> if (method == "sessions.messages.subscribe") held.await() else null }
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      controller.setSessions(ApprovalSession(CHILD), emptySet())
      runCurrent()
      controller.clear(retirePendingWrites = false)
      transport.connection = transport.connection.copy(generation = 2)
      transport.respondOverride = null
      held.complete(replay(CHILD, record("stale", CHILD)))
      controller.refresh()
      runCurrent()
      assertTrue(
        controller.state.value.rows
          .isEmpty(),
      )
      assertEquals(CHILD, controller.state.value.sessionKeys[ApprovalSession(CHILD)])
      assertEquals(2, transport.calls.count { it.first == "sessions.messages.subscribe" })
      assertEquals(0, transport.calls.count { it.first == "approval.resolve" })
    }

  @Test
  fun missingSubscriptionCapabilityIsVisibleWithoutGuessingOrigin() =
    runTest {
      val transport = FakeTransport()
      transport.connection = transport.connection.copy(methods = ApprovalController.REQUIRED_METHODS)
      val controller = ApprovalController(backgroundScope, transport) { testScheduler.currentTime }
      controller.setSessions(ApprovalSession(CHILD), emptySet())
      runCurrent()
      assertTrue(ApprovalSession(CHILD) in controller.state.value.incompleteSessions)
      assertTrue(
        controller.state.value.sessionKeys
          .isEmpty(),
      )
      assertTrue(transport.calls.isEmpty())
    }

  private class FakeTransport : ApprovalTransport {
    var connection = ApprovalConnection("gateway", 1, 1, ApprovalController.REQUIRED_METHODS + ApprovalSessionSubscriptions.METHODS)
    val records = mutableMapOf<String, String>()
    val replays = mutableMapOf<String, String>()
    val calls = mutableListOf<Pair<String, String>>()
    var listSource: String? = null
    var respondOverride: (suspend (String, String) -> String?)? = null

    override fun capture() = connection

    override fun publish(
      connection: ApprovalConnection,
      block: () -> Unit,
    ): Boolean {
      if (connection != this.connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: ApprovalConnection,
      method: String,
      params: String,
    ): String {
      calls += method to params
      respondOverride?.invoke(method, params)?.let { return it }
      val fields = Json.parseToJsonElement(params) as JsonObject
      return when (method) {
        "exec.approval.list" -> "[]"
        "plugin.approval.list" ->
          records.keys.joinToString(",", "[", "]") { id ->
            val request = listSource?.let { ",\"request\":{\"sessionKey\":\"$it\",\"agentId\":\"main\"}" }.orEmpty()
            """{"id":"$id","createdAtMs":10,"expiresAtMs":10000$request}"""
          }
        "approval.get" -> get(records[fields.getValue("id").jsonPrimitive.content] ?: terminal(fields.getValue("id").jsonPrimitive.content))
        "sessions.messages.subscribe" ->
          fields
            .getValue("key")
            .jsonPrimitive.content
            .let { replays[it] ?: replay(it) }
        "sessions.messages.unsubscribe" -> "{}"
        else -> error("unexpected $method")
      }
    }
  }

  private companion object {
    const val CHILD = "agent:main:child"
    const val PARENT = "agent:main:parent"

    fun record(
      id: String,
      source: String?,
    ): String {
      val origin = source?.let { ",\"sourceSessionKey\":\"$it\"" }.orEmpty()
      return """{"id":"$id","urlPath":"/approve/$id","createdAtMs":10,"expiresAtMs":10000,"presentation":{"kind":"plugin","title":"Install app","description":"Install an inspected APK.","severity":"warning","allowedDecisions":["allow-once","deny"]},"status":"pending"$origin}"""
    }

    fun terminal(id: String): String = record(id, null).replace("\"status\":\"pending\"", "\"status\":\"denied\",\"decision\":\"deny\",\"reason\":\"user\",\"resolvedAtMs\":30,\"source\":{\"sessionKey\":\"$CHILD\",\"agentId\":\"main\"}")

    fun get(record: String) = """{"approval":$record}"""

    fun event(
      record: String,
      audience: String = CHILD,
      source: String = CHILD,
      phase: String = "pending",
      time: Long = 20,
    ) = """{"sessionKey":"$audience","sourceSessionKey":"$source","updatedAtMs":$time,"phase":"$phase","approval":$record}"""

    fun replay(
      audience: String,
      rows: String = "",
      truncated: Boolean = false,
    ) = """{"subscribed":true,"key":"$audience","approvalReplay":{"sessionKey":"$audience","updatedAtMs":25,"approvals":[$rows],"truncated":$truncated}}"""
  }
}
