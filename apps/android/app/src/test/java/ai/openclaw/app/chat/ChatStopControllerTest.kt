package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStopControllerTest {
  private class Fixture(
    scope: TestScope,
  ) {
    var connection = ChatCacheScope("phone", 1)
    val owner = ChatComposerOwner("phone", "main", "agent:main:a")
    var sessionId = "session-a"
    var active = true
    var runIds = setOf("run-a")
    var includeActivity = true
    var includeRunIds = true
    var loseAck = false
    var settle = true
    var recoverPermissions = true
    var nativeRevoked = true
    var nativeThrows = false
    var beforeAbort: suspend () -> Unit = {}
    val requests = mutableListOf<Pair<String, JsonObject>>()
    val reconciled = mutableListOf<ChatStopTarget>()
    val nativeRequests = mutableListOf<ChatStopTarget>()
    val stops =
      ChatStopController(scope.backgroundScope, { connection }, { captured ->
        GatewaySession.RequestLease("phone", isCurrentImpl = { connection == captured }) { method, params, _, enqueue ->
          enqueue {}
          val parsed = Json.parseToJsonElement(checkNotNull(params)).jsonObject
          requests += method to parsed
          when (method) {
            "sessions.list" ->
              buildJsonObject {
                put(
                  "sessions",
                  JsonArray(
                    listOf(
                      buildJsonObject {
                        put("key", JsonPrimitive(owner.sessionKey))
                        put("agentId", JsonPrimitive(owner.agentId))
                        put("sessionId", JsonPrimitive(sessionId))
                        if (includeActivity) put("hasActiveRun", JsonPrimitive(active))
                        if (includeRunIds) put("activeRunIds", JsonArray(runIds.map(::JsonPrimitive)))
                      },
                    ),
                  ),
                )
              }.toString()
            "chat.abort" -> {
              beforeAbort()
              if (settle) {
                active = false
                runIds = emptySet()
              }
              if (loseAck) throw GatewayRequestOutcomeUnknown("ack lost")
              """{"ok":true,"aborted":true,"runIds":["run-a"]}"""
            }
            else -> error(method)
          }
        }
      }, onStopped = {
        reconciled += it
        recoverPermissions
      }, stopNativeControl = {
        nativeRequests += it
        check(!nativeThrows)
        nativeRevoked
      })

    fun target(runs: Set<String> = setOf("run-a")) = ChatStopTarget(owner, "session-a", connection, runs)

    val aborts get() = requests.filter { it.first == "chat.abort" }
    val phase get() = stops.states.value[owner]?.phase
  }

  @Test fun exactRunStopConfirmsOnlyAfterCanonicalReadAndPermissionReconciliation() =
    runTest {
      val f = Fixture(this)
      assertTrue(f.stops.stop(f.target()))
      assertEquals(listOf(f.target()), f.nativeRequests)
      assertTrue("Native revocation must precede the first suspension", f.requests.isEmpty())
      assertTrue(f.stops.blocksSend(f.owner))
      runCurrent()
      assertEquals(ChatStopPhase.Confirmed, f.phase)
      assertFalse(f.stops.blocksSend(f.owner))
      assertEquals(listOf("sessions.list", "chat.abort", "sessions.list"), f.requests.map { it.first })
      assertEquals(JsonPrimitive("agent:main:a"), f.aborts.single().second["sessionKey"])
      assertEquals(JsonPrimitive("run-a"), f.aborts.single().second["runId"])
      assertEquals(1, f.reconciled.size)
      assertTrue(f.requests.none { it.first == "sessions.patch" || it.first == "chat.send" })
    }

  @Test fun nativeUnconfirmedOrThrowingDoesNotSuppressGatewayAbortOrClaimSuccess() =
    runTest {
      for (throws in listOf(false, true)) {
        val f = Fixture(this)
        f.nativeRevoked = false
        f.nativeThrows = throws
        assertTrue(f.stops.stop(f.target()))
        runCurrent()
        assertEquals(1, f.aborts.size)
        assertEquals(ChatStopPhase.Unconfirmed, f.phase)
        assertTrue(f.reconciled.isEmpty())
        f.nativeRevoked = true
        f.nativeThrows = false
        assertTrue(f.stops.reconcile(f.owner))
        runCurrent()
        assertEquals(ChatStopPhase.Confirmed, f.phase)
        assertEquals(1, f.aborts.size)
      }
    }

  @Test fun newlyReadRunsAreRevokedWithoutDependingOnCurrentSelection() =
    runTest {
      val f = Fixture(this)
      f.runIds = setOf("server-run")
      f.stops.stop(f.target())
      runCurrent()
      assertEquals(setOf("run-a"), f.nativeRequests.first().runIds)
      assertEquals(setOf("run-a", "server-run"), f.nativeRequests.last().runIds)
      assertTrue(f.nativeRequests.all { it.owner == f.owner && it.sessionId == "session-a" })
      assertEquals(ChatStopPhase.Confirmed, f.phase)
    }

  @Test fun lostAbortResponseIsNotRetriedByReadOnlyRecovery() =
    runTest {
      val f = Fixture(this)
      f.loseAck = true
      f.stops.stop(f.target())
      runCurrent()
      assertEquals(ChatStopPhase.Unconfirmed, f.phase)
      assertTrue(f.reconciled.isEmpty())
      assertTrue(f.stops.reconcile(f.owner))
      runCurrent()
      assertEquals(ChatStopPhase.Confirmed, f.phase)
      assertEquals(1, f.aborts.size)
      assertEquals(1, f.reconciled.size)
    }

  @Test fun activeRowWithoutRunIdsUsesOnlyTheLocallyCapturedExactRun() =
    runTest {
      for (hasCapturedRun in listOf(true, false)) {
        val f = Fixture(this)
        f.includeRunIds = false
        f.stops.stop(f.target(if (hasCapturedRun) setOf("run-a") else emptySet()))
        runCurrent()
        if (hasCapturedRun) {
          assertEquals(ChatStopPhase.Confirmed, f.phase)
          assertEquals(JsonPrimitive("run-a"), f.aborts.single().second["runId"])
          assertEquals(1, f.reconciled.size)
        } else {
          assertEquals(ChatStopPhase.Unconfirmed, f.phase)
          assertTrue(f.aborts.isEmpty())
          assertTrue(f.reconciled.isEmpty())
        }
      }
    }

  @Test fun replacedSessionAndMissingActivityNeverFallBackToBroadAbort() =
    runTest {
      for (replace in listOf(true, false)) {
        val f = Fixture(this)
        if (replace) f.sessionId = "replacement" else f.includeActivity = false
        f.stops.stop(f.target())
        runCurrent()
        assertEquals(ChatStopPhase.Unconfirmed, f.phase)
        assertTrue(f.aborts.isEmpty())
        assertTrue(f.reconciled.isEmpty())
      }
    }

  @Test fun activeSessionWithoutRunIdentityCannotUseKeyOnlyAbort() =
    runTest {
      val f = Fixture(this)
      f.runIds = emptySet()
      f.stops.stop(f.target(emptySet()))
      runCurrent()
      assertEquals(ChatStopPhase.Unconfirmed, f.phase)
      assertTrue(f.aborts.isEmpty())
    }

  @Test fun knownInactiveSessionCanRecoverPermissionsWithoutInventingAnAbort() =
    runTest {
      val f = Fixture(this)
      f.active = false
      f.runIds = emptySet()
      f.stops.stop(f.target(emptySet()))
      runCurrent()
      assertEquals(ChatStopPhase.Confirmed, f.phase)
      assertTrue(f.aborts.isEmpty())
      assertEquals(1, f.reconciled.size)
    }

  @Test fun stoppedRevisionPreventsAnAlreadyWaitingSendEvenAfterConfirmation() =
    runTest {
      val f = Fixture(this)
      val revision = f.stops.revision(f.owner)
      f.stops.stop(f.target())
      var enqueued = false
      assertFalse(f.stops.enqueue(f.owner, revision) { enqueued = true })
      runCurrent()
      assertEquals(ChatStopPhase.Confirmed, f.phase)
      assertFalse(f.stops.enqueue(f.owner, revision) { enqueued = true })
      assertFalse(enqueued)
      assertTrue(f.stops.enqueue(f.owner, f.stops.revision(f.owner)) { enqueued = true })
      assertTrue(enqueued)
    }

  @Test fun duplicateTapAndOtherChatDoNotShareStopState() =
    runTest {
      val f = Fixture(this)
      val gate = CompletableDeferred<Unit>()
      f.beforeAbort = { gate.await() }
      assertTrue(f.stops.stop(f.target()))
      runCurrent()
      assertFalse(f.stops.stop(f.target()))
      assertFalse(f.stops.blocksSend(f.owner.copy(sessionKey = "agent:main:b")))
      gate.complete(Unit)
      runCurrent()
      assertEquals(1, f.aborts.size)
      assertEquals(ChatStopPhase.Confirmed, f.phase)
    }

  @Test fun oldConnectionCannotPublishStopConfirmationAndReconnectOnlyReads() =
    runTest {
      val f = Fixture(this)
      f.beforeAbort = { f.connection = f.connection.copy(connectionGeneration = 2) }
      f.stops.stop(f.target())
      runCurrent()
      assertEquals(ChatStopPhase.Unconfirmed, f.phase)
      assertTrue(f.reconciled.isEmpty())
      f.stops.reconcile(f.owner)
      runCurrent()
      assertEquals(1, f.aborts.size)
      assertEquals(ChatStopPhase.Confirmed, f.phase)
      assertEquals(
        2L,
        f.reconciled
          .single()
          .connection.connectionGeneration,
      )
    }

  @Test fun abortAckIsNotProofOfNoRemainingRunOrAppliedPermissions() =
    runTest {
      for (stillRunning in listOf(true, false)) {
        val f = Fixture(this)
        if (stillRunning) f.settle = false else f.recoverPermissions = false
        f.stops.stop(f.target())
        runCurrent()
        assertEquals(ChatStopPhase.Unconfirmed, f.phase)
        assertTrue(f.stops.blocksSend(f.owner))
      }
    }
}
