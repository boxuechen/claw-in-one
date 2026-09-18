package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionReadsTest {
  @Test
  fun oneAcknowledgementPerCanonicalUnreadEpisode() =
    runTest {
      val h = Harness(this)
      h.visit(row())
      advanceUntilIdle()
      repeat(3) { h.reads.observe(row()) }
      h.visit(row())
      advanceUntilIdle()
      assertEquals(1, h.requests.size)
      assertEquals(1, h.refreshes)
      assertEquals(JsonPrimitive(false), h.requests.single()["unread"])
      assertEquals(JsonPrimitive("alice"), h.requests.single()["agentId"])
      assertEquals(JsonNull, h.requests.single()["expectedMarkedUnreadAt"])
      h.reads.observe(row(unread = false))
      h.reads.observe(row())
      advanceUntilIdle()
      assertEquals(2, h.requests.size)
    }

  @Test
  fun manualUnreadMarkerIsPreservedUntilLeavingAndReopening() =
    runTest {
      val h = Harness(this)
      h.visit(row(unread = false))
      repeat(3) { h.reads.observe(row(marker = 100)) }
      advanceUntilIdle()
      assertTrue(h.requests.isEmpty())
      h.visit(row(key = "other", unread = false))
      h.visit(row(marker = 100))
      advanceUntilIdle()
      assertEquals(JsonPrimitive(100L), h.requests.single()["expectedMarkedUnreadAt"])
    }

  @Test
  fun onlyAdvertisedConditionalFieldIsSent() =
    runTest {
      val h = Harness(this)
      h.conditional = false
      h.visit(row(marker = 100))
      advanceUntilIdle()
      assertEquals(setOf("key", "agentId", "unread"), h.requests.single().keys)
    }

  @Test
  fun backgroundAndForeignOwnerSnapshotsCannotAcknowledge() =
    runTest {
      val h = Harness(this)
      h.visit(row(unread = false))
      h.reads.observe(row(key = "background"))
      h.reads.observe(row(owner = "bob"))
      h.selection.select("draft", "alice")
      h.reads.selectionChanged()
      h.reads.observe(row())
      advanceUntilIdle()
      assertTrue(h.requests.isEmpty())
    }

  @Test
  fun lateFailureCannotUnlatchAReopenedSameKeyVisit() =
    runTest {
      val h = Harness(this)
      val old = CompletableDeferred<String>()
      val fresh = CompletableDeferred<String>()
      var calls = 0
      h.respond = { if (++calls == 1) old.await() else fresh.await() }
      h.visit(row())
      runCurrent()
      h.visit(row(key = "other", unread = false))
      h.visit(row())
      runCurrent()
      old.completeExceptionally(IllegalStateException("old failure"))
      runCurrent()
      h.reads.observe(row())
      runCurrent()
      assertEquals(2, h.requests.size)
      assertTrue(h.failures.isEmpty())
      fresh.complete("{}")
      advanceUntilIdle()
      assertEquals(1, h.refreshes)
    }

  @Test
  fun canonicalReadRetiresAnOlderAttemptBeforeANewUnreadEpisode() =
    runTest {
      val h = Harness(this)
      val old = CompletableDeferred<String>()
      val fresh = CompletableDeferred<String>()
      var calls = 0
      h.respond = { if (++calls == 1) old.await() else fresh.await() }
      h.visit(row())
      runCurrent()
      h.reads.observe(row(unread = false))
      h.reads.observe(row())
      runCurrent()
      old.completeExceptionally(IllegalStateException("old episode"))
      runCurrent()
      h.reads.observe(row())
      runCurrent()
      assertEquals(2, h.requests.size)
      assertTrue(h.failures.isEmpty())
      fresh.complete("{}")
      advanceUntilIdle()
      assertEquals(1, h.refreshes)
    }

  @Test
  fun failedAttemptAllowsLaterSnapshotButUnknownOutcomeDoesNotReplay() =
    runTest {
      val h = Harness(this)
      h.respond = { throw GatewayRequestNotEnqueued("not sent") }
      h.visit(row())
      advanceUntilIdle()
      h.respond = { throw GatewayRequestOutcomeUnknown("lost response") }
      h.reads.observe(row())
      advanceUntilIdle()
      repeat(3) { h.reads.observe(row()) }
      advanceUntilIdle()
      assertEquals(2, h.requests.size)
      assertEquals(listOf("not sent", "lost response"), h.failures)
      assertEquals(0, h.refreshes)
      h.respond = { "{}" }
      h.reads.observe(row(unread = false))
      h.reads.observe(row())
      advanceUntilIdle()
      assertEquals(3, h.requests.size)
    }

  @Test
  fun queuedAcknowledgementDoesNotFollowAChangedVisitOrConnection() =
    runTest {
      for (change in listOf("selection", "connection", "clear", "read")) {
        val h = Harness(this)
        h.visit(row())
        assertEquals(1, h.leaseCaptures)
        when (change) {
          "selection" -> h.visit(row(key = "other", unread = false))
          "connection" -> h.gateway = h.gateway.copy(connectionGeneration = 2)
          "clear" -> h.reads.clear()
          else -> h.reads.observe(row(unread = false))
        }
        advanceUntilIdle()
        assertTrue("$change must retire dispatch", h.requests.isEmpty())
        assertEquals(0, h.refreshes)
        assertEquals(if (change == "selection") 2 else 1, h.leaseCaptures)
      }
    }

  @Test
  fun dispatchRechecksVisitAfterWaitingForTransport() =
    runTest {
      val h = Harness(this)
      val transportReady = CompletableDeferred<Unit>()
      h.beforeEnqueue = { transportReady.await() }
      h.visit(row())
      runCurrent()
      h.visit(row(key = "other", unread = false))
      transportReady.complete(Unit)
      advanceUntilIdle()
      assertTrue(h.requests.isEmpty())
      assertTrue(h.failures.isEmpty())
    }

  @Test
  fun changedOwnerOnSameKeyCannotReceiveOldResult() =
    runTest {
      val h = Harness(this)
      val reply = CompletableDeferred<String>()
      h.respond = { reply.await() }
      h.visit(row())
      runCurrent()
      h.visit(row(owner = "bob", unread = false))
      reply.complete("{}")
      advanceUntilIdle()
      assertEquals(0, h.refreshes)
      assertTrue(h.failures.isEmpty())
    }

  @Test
  fun cancellationAfterEnqueueDoesNotClaimReadOrRetry() =
    runTest {
      val job = Job()
      val h = Harness(this, CoroutineScope(coroutineContext + job))
      h.respond = { CompletableDeferred<String>().await() }
      h.visit(row())
      runCurrent()
      assertEquals(1, h.requests.size)
      job.cancel()
      advanceUntilIdle()
      h.reads.observe(row())
      assertEquals(1, h.leaseCaptures)
      assertEquals(0, h.refreshes)
      assertTrue(job.isCancelled)
    }

  @Test
  fun activationCannotSurviveInvalidationWhileCapturingTheTransport() =
    runTest {
      for (clear in listOf(true, false)) {
        val h = Harness(this)
        h.onCapture = {
          if (clear) {
            h.reads.clear()
          } else {
            h.selection.select("other", "alice")
            h.reads.selectionChanged()
          }
        }
        h.reads.activate("main", "alice") { row() }
        advanceUntilIdle()
        assertTrue("clear=$clear selected=${h.selection.key.value} requests=${h.requests}", h.requests.isEmpty())
      }
    }

  @Test
  fun backgroundSnapshotCannotAcquireAReplacementPhysicalConnection() =
    runTest {
      val h = Harness(this)
      h.visit(row(unread = false))
      h.connection++
      h.reads.observe(row())
      advanceUntilIdle()
      assertEquals(1, h.leaseCaptures)
      assertTrue(h.requests.isEmpty())
      h.visit(row())
      advanceUntilIdle()
      assertEquals(2, h.leaseCaptures)
      assertEquals(1, h.requests.size)
    }

  @Test
  fun explicitReactivationCanCaptureALeaseAfterAnOfflineVisit() =
    runTest {
      val h = Harness(this)
      h.connected = false
      h.visit(row())
      advanceUntilIdle()
      assertTrue(h.requests.isEmpty())
      h.connected = true
      h.visit(row())
      advanceUntilIdle()
      assertEquals(1, h.requests.size)
      assertFalse(h.requests.single().isEmpty())
    }

  private class Harness(
    testScope: TestScope,
    scope: CoroutineScope = testScope,
  ) {
    val lock = Any()
    var gateway = ChatCacheScope("gateway", 1)
    var connection = 1
    var conditional = true
    var connected = true
    var leaseCaptures = 0
    var refreshes = 0
    val failures = mutableListOf<String?>()
    var respond: suspend () -> String = { "{}" }
    var beforeEnqueue: suspend () -> Unit = {}
    var onCapture: () -> Unit = {}
    val requests = mutableListOf<JsonObject>()
    val selection = ChatSessionSelection(lock, { gateway }, { "alice" }, { 0L })
    val reads =
      ChatSessionReads(
        scope,
        lock,
        selection,
        { gateway },
        { conditional },
        captureRequestLease = {
          assertFalse("Transport capture must not invert lifecycle -> publication lock order", Thread.holdsLock(lock))
          if (!connected) {
            null
          } else {
            val captured = gateway
            val capturedConnection = connection
            leaseCaptures++
            onCapture()
            GatewaySession.RequestLease("gateway", isCurrentImpl = { captured == gateway && capturedConnection == connection && connected }) { method, params, _, enqueue ->
              beforeEnqueue()
              if (captured != gateway || capturedConnection != connection || !connected) throw GatewayRequestNotEnqueued("retired")
              enqueue {
                assertEquals("sessions.patch", method)
                requests += Json.parseToJsonElement(params!!) as JsonObject
              }
              respond()
            }
          }
        },
        onAcknowledged = { refreshes++ },
        onFailure = { failures += it },
      )

    fun visit(row: ChatSessionEntry) {
      reads.activate(row.key, row.ownerAgentId) { row }
      selection.select(row.key, row.ownerAgentId)
      reads.selectionChanged()
    }
  }

  companion object {
    private fun row(
      key: String = "main",
      owner: String = "alice",
      unread: Boolean = true,
      marker: Long? = null,
    ) = ChatSessionEntry(key = key, ownerAgentId = owner, updatedAtMs = null, unread = unread, markedUnreadAt = marker)
  }
}
