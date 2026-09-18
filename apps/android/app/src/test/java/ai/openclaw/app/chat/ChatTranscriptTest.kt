package ai.openclaw.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTranscriptTest {
  @Test
  fun requestCapturesOwnerAndWaitsForMainSessionReadiness() =
    runTest {
      val h = Harness(this)
      val ready = CompletableDeferred<Unit>()
      h.awaitReady = { _, _ -> ready.await() }
      val request = async(start = CoroutineStart.UNDISPATCHED) { h.request() }
      assertFalse(request.isCompleted)
      assertTrue(h.requests.isEmpty())
      ready.complete(Unit)
      advanceUntilIdle()
      val ticket = request.await()
      assertEquals("alice", ticket.agentId)
      assertEquals(h.gateway, ticket.gatewayScope)
      assertEquals("main", ticket.sessionKey)
      assertEquals(
        "history",
        h.transcript
          .fetch(ticket)
          ?.messages
          ?.single()
          ?.content
          ?.single()
          ?.text,
      )
      assertEquals(listOf(ticket), h.requests)
    }

  @Test
  fun selectionChangeWhileWaitingRetiresTheReadWithoutDispatch() =
    runTest {
      val h = Harness(this)
      val ready = CompletableDeferred<Unit>()
      h.awaitReady = { _, _ -> ready.await() }
      val request = async(start = CoroutineStart.UNDISPATCHED) { h.transcript.prepareRead("main", h.transcript.generation) }
      h.select("other")
      ready.complete(Unit)
      advanceUntilIdle()
      assertEquals(ChatHistoryPreparation.Superseded, request.await())
      assertTrue(h.requests.isEmpty())
    }

  @Test
  fun onlyTheNewestPublishedReadWinsNotMerelyTheNewestStartedRead() =
    runTest {
      val h = Harness(this)
      val first = h.request()
      val second = h.request()
      assertTrue(h.publish(first, "first"))
      assertTrue(h.publish(second, "second"))
      assertFalse(h.publish(first, "stale"))
      advanceUntilIdle()
      assertEquals(second.sequence, h.transcript.publicationSequence)
      assertEquals(
        "second",
        h.transcript.messages.value
          .single()
          .id,
      )
      assertEquals(listOf("first", "second"), h.cache.saved.map { it.messages.single().id })
    }

  @Test
  fun staleResponseCannotCrossSelectionConnectionOrDefaultOwnerRevision() =
    runTest {
      for (transition in listOf("selection", "connection", "default")) {
        val h = Harness(this)
        val ticket = h.request()
        val reply = CompletableDeferred<String>()
        h.respond = { reply.await() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { h.transcript.fetch(ticket) }
        when (transition) {
          "selection" -> {
            h.select("other")
            h.select("main")
          }
          "connection" -> h.gateway = h.gateway.copy(connectionGeneration = 2)
          else -> h.defaultRevision++
        }
        reply.complete(historyJson())
        advanceUntilIdle()
        assertNull(read.await())
        assertFalse(h.publish(ticket, "stale"))
        assertTrue(h.cache.saved.isEmpty())
      }
    }

  @Test
  fun explicitAgentReadDoesNotDependOnDefaultAgentRevision() =
    runTest {
      val h = Harness(this)
      h.select("agent:bob:chat")
      val ticket = h.request()
      h.defaultRevision++
      assertEquals("bob", ticket.agentId)
      assertFalse(ticket.tracksDefaultAgent)
      assertTrue(h.publish(ticket, "bob-history"))
    }

  @Test
  fun currentFailureIsObservableButSupersededFailureDoesNotReplaceCurrentChat() =
    runTest {
      val h = Harness(this)
      h.respond = { throw IllegalStateException("current failure") }
      var failed = false
      try {
        h.transcript.fetch(h.request())
      } catch (error: IllegalStateException) {
        failed = error.message == "current failure"
      }
      assertTrue(failed)
      val reply = CompletableDeferred<String>()
      h.respond = { reply.await() }
      val ticket = h.request()
      val read = async(start = CoroutineStart.UNDISPATCHED) { h.transcript.fetch(ticket) }
      h.select("other")
      reply.completeExceptionally(IllegalStateException("stale failure"))
      advanceUntilIdle()
      assertNull(read.await())
    }

  @Test
  fun canceledReadCannotPublishOrTurnIntoAnEmptySnapshot() =
    runTest {
      val h = Harness(this)
      h.respond = { CompletableDeferred<String>().await() }
      val ticket = h.request()
      val read = async(start = CoroutineStart.UNDISPATCHED) { h.transcript.fetch(ticket) }
      read.cancel()
      advanceUntilIdle()
      assertTrue(read.isCancelled)
      assertEquals(0L, h.transcript.publicationSequence)
      assertTrue(h.cache.saved.isEmpty())
    }

  @Test
  fun missingOwnerIsNotAnAcceptedEmptyHistory() =
    runTest {
      val h = Harness(this, initialAgent = null)
      assertEquals(ChatHistoryPreparation.OwnerUnavailable, h.transcript.prepareRead("main", 0))
      assertTrue(h.requests.isEmpty())
      assertFalse(h.transcript.hasLiveHistory("main"))
      h.select("agent:bob:main")
      assertEquals("bob", h.request().agentId)
    }

  @Test
  fun cacheCanSeedOptimisticRowsButNeverOverwriteLiveEmptyHistory() =
    runTest {
      val h = Harness(this)
      val optimistic = message("draft", "client-key")
      h.transcript.appendOptimistic(optimistic)
      assertTrue(h.transcript.publishCached("main", 0, listOf(message("cached")), listOf(optimistic)))
      assertTrue(h.transcript.fromCache.value)
      assertFalse(h.transcript.hasLiveHistory("main"))
      val ticket = h.request()
      assertTrue(h.transcript.publishLive(ticket, ChatHistory("main", "session", null, emptyList()), emptyList(), false))
      assertTrue(h.transcript.hasLiveHistory("main"))
      assertFalse(h.transcript.publishCached("main", 0, listOf(message("stale-cache")), emptyList()))
      assertFalse(h.transcript.fromCache.value)
      assertTrue(
        h.transcript.messages.value
          .isEmpty(),
      )
    }

  @Test
  fun disconnectPreservesMessagesButRetiresLiveIdentityAndLoading() =
    runTest {
      val h = Harness(this)
      h.publish(h.request(), "accepted")
      h.transcript.nextLoad(markLoading = true)
      assertTrue(h.transcript.loading.value)
      val before = h.transcript.generation
      h.transcript.disconnect()
      assertEquals(before + 1, h.transcript.generation)
      assertEquals(
        "accepted",
        h.transcript.messages.value
          .single()
          .id,
      )
      assertFalse(h.transcript.hasLiveHistory("main"))
      assertNull(h.transcript.sessionId.value)
      assertFalse(h.transcript.loading.value)
    }

  @Test
  fun onlyTheCurrentLoadCanFinishAndSelectionClearsVisibleRows() =
    runTest {
      val h = Harness(this)
      val generation = h.select("main")
      h.transcript.appendOptimistic(message("draft"))
      h.transcript.finishLoad("other", generation)
      h.transcript.finishLoad("main", generation - 1)
      assertTrue(h.transcript.loading.value)
      h.transcript.finishLoad("main", generation)
      assertFalse(h.transcript.loading.value)
      h.select("other")
      assertTrue(
        h.transcript.messages.value
          .isEmpty(),
      )
      assertFalse(h.transcript.fromCache.value)
    }

  @Test
  fun optimisticRowsHaveOneVisibleCopyAndNeverReachTheCanonicalCache() =
    runTest {
      val h = Harness(this)
      val first = message("optimistic", "send-one")
      h.transcript.appendOptimistic(first)
      h.transcript.appendOptimistic(first.copy(id = "duplicate"))
      assertEquals(1, h.transcript.messages.value.size)
      val rekeyed = first.copy(idempotencyKey = "accepted-key")
      h.transcript.rekeyOptimistic(first.id, rekeyed)
      assertEquals(
        "accepted-key",
        h.transcript.messages.value
          .single()
          .idempotencyKey,
      )
      val ticket = h.request()
      val canonical = message("canonical", "accepted-key")
      assertTrue(h.transcript.publishLive(ticket, ChatHistory("main", "session", null, listOf(canonical)), listOf(rekeyed, message("pending", "send-two")), false))
      advanceUntilIdle()
      assertEquals(
        listOf("canonical", "pending"),
        h.transcript.messages.value
          .map { it.id },
      )
      assertEquals(
        listOf(canonical),
        h.cache.saved
          .single()
          .messages,
      )
      h.transcript.removeOptimistic("pending")
      assertEquals(listOf(canonical), h.transcript.messages.value)
    }

  @Test
  fun completionAnchorSurvivesRefreshButDoesNotLeakToAnotherSession() =
    runTest {
      val h = Harness(this)
      val ticket = h.request()
      val history = ChatHistory("main", "session", null, listOf(message("done")), sessionInfo = ChatSessionEntry("main", null, endedAt = 100))
      assertTrue(h.transcript.publishLive(ticket, history, emptyList(), true))
      h.publish(h.request(), "later")
      assertEquals(
        "later",
        h.transcript.anchor.value
          ?.newestItemId,
      )
      assertEquals(
        "done",
        h.transcript.anchor.value
          ?.completedNewestItemId,
      )
      assertEquals(
        100L,
        h.transcript.anchor.value
          ?.completedEndedAt,
      )
      h.select("other")
      h.publish(h.request(), "other-row")
      assertNull(
        h.transcript.anchor.value
          ?.completedEndedAt,
      )
    }

  @Test
  fun cacheWritesFollowPublicationOrderAndDiscardAQueuedOldGatewayWrite() =
    runTest {
      val h = Harness(this)
      h.cacheMutex.lock()
      h.publish(h.request(), "first")
      h.publish(h.request(), "second")
      assertTrue(h.cache.saved.isEmpty())
      h.cacheMutex.unlock()
      advanceUntilIdle()
      assertEquals(listOf("first", "second"), h.cache.saved.map { it.messages.single().id })
      h.cacheMutex.lock()
      h.publish(h.request(), "discarded")
      h.gateway = h.gateway.copy(connectionGeneration = 2)
      h.cacheMutex.unlock()
      advanceUntilIdle()
      assertEquals(2, h.cache.saved.size)
    }

  @Test
  fun decoderReconcilesStableMessageIdsAcrossReadRefreshes() =
    runTest {
      val h = Harness(this)
      val first = h.request()
      val history = h.transcript.fetch(first)!!
      h.transcript.publishLive(first, history, emptyList(), false)
      val second = h.transcript.fetch(h.request())!!
      assertEquals(history.messages.single().id, second.messages.single().id)
    }

  private class Harness(
    scope: TestScope,
    initialAgent: String? = "alice",
  ) {
    val lock = Any()
    var gateway = ChatCacheScope("gateway", 1)
    var defaultAgent: String? = initialAgent
    var defaultRevision = 0L
    val selection = ChatSessionSelection(lock, { gateway }, { defaultAgent }, { defaultRevision })
    var awaitReady: suspend (String, ChatCacheScope?) -> Unit = { _, _ -> }
    var respond: suspend (ChatHistoryRequest) -> String = { historyJson() }
    val requests = mutableListOf<ChatHistoryRequest>()
    val cache = Cache()
    val cacheMutex = Mutex()
    val transcript =
      ChatTranscript(
        scope,
        lock,
        selection,
        { gateway },
        { defaultRevision },
        ChatHistoryCodec(Json, ChatSessionCatalogCodec(Json)),
        awaitSessionReadiness = { key, gateway -> awaitReady(key, gateway) },
        requestHistory = { request ->
          assertFalse("Gateway I/O must not run under the publication lock", Thread.holdsLock(lock))
          requests += request
          respond(request)
        },
        cache,
        cacheMutex,
      )

    suspend fun request(): ChatHistoryRequest = (transcript.prepareRead(selection.key.value, transcript.generation) as ChatHistoryPreparation.Ready).request

    fun select(key: String): Long =
      synchronized(lock) {
        val generation = transcript.beginSelection(true, true)
        selection.select(key, null)
        generation
      }

    fun publish(
      request: ChatHistoryRequest,
      id: String,
    ): Boolean = transcript.publishLive(request, ChatHistory(request.sessionKey, "session", null, listOf(message(id))), emptyList(), false)
  }

  private data class Saved(
    val gateway: String,
    val agent: String,
    val key: String,
    val messages: List<ChatMessage>,
  )

  private class Cache : ChatTranscriptCache {
    val saved = mutableListOf<Saved>()

    override suspend fun loadLastDefaultAgentId(gatewayId: String): String? = null

    override suspend fun saveLastDefaultAgentId(
      gatewayId: String,
      agentId: String,
    ) = Unit

    override suspend fun loadSessions(
      gatewayId: String,
      agentId: String,
    ): List<ChatSessionEntry> = emptyList()

    override suspend fun loadTranscript(
      gatewayId: String,
      agentId: String,
      sessionKey: String,
    ): List<ChatMessage> = emptyList()

    override suspend fun saveSessions(
      gatewayId: String,
      agentId: String,
      sessions: List<ChatSessionEntry>,
      retainedSessionKey: String?,
    ) = Unit

    override suspend fun saveTranscript(
      gatewayId: String,
      agentId: String,
      sessionKey: String,
      messages: List<ChatMessage>,
    ) {
      saved += Saved(gatewayId, agentId, sessionKey, messages)
    }

    override suspend fun deleteSession(
      gatewayId: String,
      agentId: String,
      sessionKey: String,
    ) = Unit

    override suspend fun clearGateway(gatewayId: String) = Unit
  }

  companion object {
    private fun message(
      id: String,
      clientKey: String? = null,
    ) = ChatMessage(id = id, role = "user", content = listOf(ChatMessageContent(type = "text", text = id)), timestampMs = 1, idempotencyKey = clientKey)

    private fun historyJson(): String = """{"sessionId":"session","messages":[{"role":"assistant","content":[{"type":"text","text":"history"}],"timestamp":1}]}"""
  }
}
