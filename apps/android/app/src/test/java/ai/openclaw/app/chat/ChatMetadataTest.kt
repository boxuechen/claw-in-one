package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMetadataTest {
  @Test
  fun negotiatedScopeAndLocalDraftNeverInventASessionProfile() =
    runTest {
      for (sessionScoped in listOf(null, false, true)) {
        val h = Harness(this)
        h.sessionScoped = sessionScoped
        h.select("agent:alice:one")
        h.metadata.refreshAwait()
        assertEquals(JsonPrimitive("alice"), h.requests.single()["agentId"])
        assertEquals(if (sessionScoped == true) JsonPrimitive("agent:alice:one") else null, h.requests.single()["sessionKey"])
        h.select("agent:alice:two")
        assertEquals(sessionScoped != true, h.metadata.isLoaded())
        h.localDraft = true
        h.metadata.selectionChanged()
        h.metadata.refreshAwait()
        assertEquals(setOf("agentId"), h.requests.last().keys)
      }
    }

  @Test
  fun queuedRefreshRetiresCompletedOldReadBeforeNewCoroutineRuns() =
    runTest {
      for (oldFails in listOf(false, true)) {
        val h = Harness(this)
        h.metadata.refreshAwait()
        val old = CompletableDeferred<String>()
        val fresh = CompletableDeferred<String>()
        var count = 0
        h.respond = { if (++count == 1) old.await() else fresh.await() }
        h.metadata.refresh()
        runCurrent()
        if (oldFails) old.completeExceptionally(IllegalStateException("stale")) else old.complete(payload("stale"))
        h.metadata.refresh()
        runCurrent()
        assertEquals(
          "accepted",
          h.metadata.commands.value
            .single()
            .name,
        )
        fresh.complete(payload("fresh"))
        advanceUntilIdle()
        assertEquals(
          "fresh",
          h.metadata.commands.value
            .single()
            .name,
        )
      }
    }

  @Test
  fun failureRetainsAcceptedCommands() =
    runTest {
      val h = Harness(this)
      h.respond = { payload("accepted") }
      h.metadata.refreshAwait()
      h.respond = { throw IllegalStateException("offline") }
      h.metadata.refreshAwait()
      assertTrue(h.metadata.isLoaded())
      assertEquals(
        listOf("accepted"),
        h.metadata.commands.value
          .map { it.name },
      )
    }

  @Test
  fun disconnectOrSelectionRoundTripCannotPublishLateMetadata() =
    runTest {
      for (transition in listOf("disconnect", "round-trip", "agent-round-trip")) {
        val h = Harness(this)
        h.metadata.refreshAwait()
        val reply = CompletableDeferred<String>()
        h.respond = { reply.await() }
        h.metadata.refresh()
        runCurrent()
        when (transition) {
          "disconnect" -> h.metadata.clear()
          "round-trip" -> {
            h.select("other")
            h.select("main")
          }
          else -> {
            h.select("agent:other:main")
            h.select("main")
          }
        }
        reply.complete(payload("stale"))
        advanceUntilIdle()
        assertTrue(
          h.metadata.commands.value
            .isEmpty(),
        )
      }
    }

  @Test
  fun agentScopedReadAlsoRejectsASelectionRoundTripWithoutErasingAcceptedCatalog() =
    runTest {
      val h = Harness(this)
      h.sessionScoped = false
      h.metadata.refreshAwait()
      val reply = CompletableDeferred<String>()
      h.respond = { reply.await() }
      h.metadata.refresh()
      runCurrent()
      h.select("other")
      h.select("main")
      reply.complete(payload("stale"))
      advanceUntilIdle()
      assertTrue(h.metadata.isLoaded())
      assertEquals(
        "accepted",
        h.metadata.commands.value
          .single()
          .name,
      )
    }

  @Test
  fun connectionAndCatalogRevisionsFenceInFlightReadsWithoutAnExtraRefresh() =
    runTest {
      for (changeConnection in listOf(false, true)) {
        val h = Harness(this)
        h.metadata.refreshAwait()
        val reply = CompletableDeferred<String>()
        h.respond = { reply.await() }
        h.metadata.refresh()
        runCurrent()
        if (changeConnection) h.gateway = h.gateway.copy(connectionGeneration = 2) else h.catalogRevision++
        reply.complete(payload("stale"))
        advanceUntilIdle()
        assertFalse(h.metadata.isLoaded())
        assertEquals(
          "accepted",
          h.metadata.commands.value
            .single()
            .name,
        )
      }
    }

  @Test
  fun refreshCapturesConnectionBeforeDispatchAndDoesNotFollowReplacement() =
    runTest {
      val h = Harness(this)
      h.metadata.refresh()
      assertEquals(1, h.leaseCaptures)
      h.gateway = h.gateway.copy(connectionGeneration = 2)
      advanceUntilIdle()
      assertTrue(h.requests.isEmpty())
      assertEquals(1, h.leaseCaptures)
    }

  @Test
  fun retiredPhysicalConnectionCannotPublishDespiteUnchangedLogicalScope() =
    runTest {
      val h = Harness(this)
      h.metadata.refreshAwait()
      val reply = CompletableDeferred<String>()
      h.respond = { reply.await() }
      h.metadata.refresh()
      runCurrent()
      h.connection++
      reply.complete(payload("stale"))
      advanceUntilIdle()
      assertEquals(
        "accepted",
        h.metadata.commands.value
          .single()
          .name,
      )
    }

  @Test
  fun validEmptyCommandsLoadWithoutRetry() =
    runTest {
      val h = Harness(this)
      h.respond = { "{\"commands\":[]}" }
      h.metadata.refreshAwait()
      assertTrue(h.metadata.isLoaded())
      assertTrue(
        h.metadata.commands.value
          .isEmpty(),
      )
      assertEquals(1, h.requests.size)
    }

  @Test
  fun cancellationIsNotSwallowedOrPublishedAsAnEmptyCatalog() =
    runTest {
      val h = Harness(this)
      h.metadata.refreshAwait()
      h.respond = { CompletableDeferred<String>().await() }
      val read = async(start = CoroutineStart.UNDISPATCHED) { h.metadata.refreshAwait() }
      read.cancel()
      advanceUntilIdle()
      assertTrue(read.isCancelled)
      assertEquals(
        "accepted",
        h.metadata.commands.value
          .single()
          .name,
      )
    }

  @Test
  fun sessionMutationMatchesOnlyNegotiatedSelectedProfile() =
    runTest {
      val h = Harness(this)
      assertTrue(h.metadata.matchesSession("main", "alice"))
      assertFalse(h.metadata.matchesSession("main", "bob"))
      assertFalse(h.metadata.matchesSession("other", "alice"))
      assertFalse(h.metadata.matchesSession(null, "alice"))
      h.sessionScoped = false
      assertFalse(h.metadata.matchesSession("main", "alice"))
      h.sessionScoped = true
      h.localDraft = true
      assertFalse(h.metadata.matchesSession("main", "alice"))
    }

  private class Harness(
    scope: TestScope,
  ) {
    val json = Json
    val lock = Any()
    var gateway = ChatCacheScope("gateway", 1)
    var connection = 1
    var catalogRevision = 0L
    var sessionScoped: Boolean? = true
    var localDraft = false
    var leaseCaptures = 0
    var respond: suspend () -> String = { payload("accepted") }
    val requests = mutableListOf<JsonObject>()
    val selection = ChatSessionSelection(lock, { gateway }, { "alice" }, { 0L })
    val metadata =
      ChatMetadata(
        scope,
        json,
        lock,
        selection,
        { gateway },
        { catalogRevision },
        { sessionScoped },
        { _, _ -> localDraft },
        captureRequestLease = {
          assertFalse("Transport capture must not invert lifecycle -> publication lock order", Thread.holdsLock(lock))
          val captured = gateway
          val capturedConnection = connection
          leaseCaptures++
          GatewaySession.RequestLease("gateway", isCurrentImpl = { captured == gateway && capturedConnection == connection }) { method, params, _, enqueue ->
            if (captured != gateway || capturedConnection != connection) throw GatewayRequestNotEnqueued("retired")
            enqueue {}
            assertEquals("chat.metadata", method)
            requests += json.parseToJsonElement(params!!) as JsonObject
            respond()
          }
        },
      )

    fun select(key: String) {
      synchronized(lock) {
        selection.select(key, null)
        metadata.selectionChanged()
      }
    }
  }

  companion object {
    private fun payload(command: String): String = """{"commands":[{"name":"$command","textAliases":["/$command"]}]}"""
  }
}
