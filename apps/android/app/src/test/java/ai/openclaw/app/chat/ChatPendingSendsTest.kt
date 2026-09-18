package ai.openclaw.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPendingSendsTest {
  @Test
  fun projectionIsOwnerBoundIdempotentAndExposesDetachedSnapshots() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.pending.project(send)
      val visible = h.pending.visibleRunIds
      val echoes = h.pending.optimisticMessages
      assertEquals(listOf(send.optimisticMessage), h.transcript.messages.value)
      assertEquals(1, h.events.count { it == ChatPendingSendEvent.Projected })

      h.select("other")
      h.pending.project(send)
      assertTrue(h.pending.visibleRunIds.isEmpty())
      assertTrue(
        h.transcript.messages.value
          .isEmpty(),
      )
      assertEquals(send, h.pending.projection("local"))
      assertEquals(setOf("local"), visible)
      assertEquals(listOf(send.optimisticMessage), echoes)

      h.select("main")
      h.pending.restoreForCurrentOwner()
      assertEquals(setOf("local"), h.pending.visibleRunIds)
      assertEquals(listOf(send.optimisticMessage), h.transcript.messages.value)
      assertEquals(send.optimisticMessage.id, h.pending.clockKey("local"))
    }

  @Test
  fun acknowledgementMovesRunButPreservesClientMessageAndTelemetry() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.runState.recordUsage("local", 1, 12)
      h.pending.markUnknown("local")
      h.pending.transfer("local", "server", send.optimisticMessage)
      assertEquals(setOf("server"), h.pending.locallyOwnedRunIds)
      assertEquals(setOf("server"), h.pending.replyRunIds)
      assertNull(h.pending.projection("local"))
      assertEquals(
        "local:user",
        h.pending
          .projection("server")
          ?.optimisticMessage
          ?.idempotencyKey,
      )
      assertEquals(listOf(send.optimisticMessage), h.transcript.messages.value)
      assertEquals(send.optimisticMessage.id, h.pending.clockKey("server"))
      h.publishRun()
      assertEquals(12L, h.runState.presentation.value.outputTokens)
    }

  @Test
  fun hiddenAcknowledgementDoesNotProjectIntoAnotherChatAndExpiresOnce() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.select("other")
      h.pending.transfer("local", "server", send.optimisticMessage)
      runCurrent()
      assertTrue(h.pending.visibleRunIds.isEmpty())
      assertTrue(
        h.transcript.messages.value
          .isEmpty(),
      )
      assertEquals(setOf("server"), h.pending.locallyOwnedRunIds)
      h.events.clear()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(listOf("server"), h.parked)
      assertTrue(h.reads.isEmpty())
      assertTrue(h.events.isEmpty())
      assertNull(h.pending.projection("server"))
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(listOf("server"), h.parked)
    }

  @Test
  fun returningToHiddenOwnerReplacesItsDeadline() =
    runTest {
      val h = Harness(this)
      h.send("local")
      h.select("other")
      runCurrent()
      advanceTimeBy(TIMEOUT / 2)
      h.select("main")
      h.pending.restoreForCurrentOwner()
      runCurrent()
      advanceTimeBy(TIMEOUT / 2)
      runCurrent()
      assertTrue(h.parked.isEmpty())
      assertTrue(h.pending.isPending("local"))
      advanceTimeBy(TIMEOUT / 2)
      runCurrent()
      assertEquals(listOf("local"), h.parked)
      assertEquals(1, h.events.count { it == ChatPendingSendEvent.ReplyTimedOut })
    }

  @Test
  fun canonicalUserRowDoesNotSettleReplyButMatchingAssistantDoes() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      val user = send.optimisticMessage.copy(id = "persisted-user")
      val projection = h.apply(history(user), setOf("local"))
      assertTrue(projection.optimisticMessages.isEmpty())
      assertFalse(projection.completionSettled)
      assertFalse(h.pending.isPending("local"))
      assertTrue(h.pending.hasReply("local"))
      assertEquals(listOf(user), h.transcript.messages.value)

      val completed = h.apply(history(user, message("answer", role = "assistant")), setOf("local"))
      assertTrue(completed.completionSettled)
      assertFalse(h.pending.isLocallyOwned("local"))
      assertEquals(
        100L,
        h.transcript.anchor.value
          ?.completedEndedAt,
      )
    }

  @Test
  fun assistantForFollowingUserDoesNotSettleAnEarlierReply() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      val incoming = history(send.optimisticMessage, message("another"), message("answer", role = "assistant"))
      assertFalse(h.apply(incoming, setOf("local")).completionSettled)
      assertTrue(h.pending.hasReply("local"))
      h.pending.markTerminalWithoutReply("local")
      assertTrue(h.apply(incoming, setOf("local")).completionSettled)
      assertFalse(h.pending.hasReply("local"))
    }

  @Test
  fun terminalWithoutReplyStillRequiresCanonicalUserProof() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.pending.markTerminalWithoutReply("local")
      assertFalse(h.apply(history(), setOf("local")).completionSettled)
      assertTrue(h.pending.hasReply("local"))
      assertTrue(h.apply(history(send.optimisticMessage), setOf("local")).completionSettled)
      assertFalse(h.pending.hasReply("local"))
    }

  @Test
  fun lostAcknowledgementTransfersOnlyWithCanonicalKeyAndMatchingContent() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.pending.markUnknown("local")
      val wrong = message("different", key = "server:user")
      h.apply(history(wrong, inFlight = "server"), setOf("local"))
      assertTrue(h.pending.isPending("local"))
      assertFalse(h.pending.isPending("server"))

      val proof = send.optimisticMessage.copy(id = "canonical", idempotencyKey = "server:user")
      h.apply(history(proof, inFlight = "server"), setOf("local"))
      assertEquals(setOf("server"), h.pending.visibleRunIds)
      assertEquals(setOf("server"), h.pending.replyRunIds)
      assertEquals(
        "server:user",
        h.pending
          .projection("server")
          ?.optimisticMessage
          ?.idempotencyKey,
      )
      assertEquals(listOf(proof), h.transcript.messages.value)
    }

  @Test
  fun ambiguousLocalRunsCannotClaimAHistoryRun() =
    runTest {
      val h = Harness(this)
      val first = h.send("first")
      h.send("second")
      h.pending.markUnknown("first")
      val proof = first.optimisticMessage.copy(idempotencyKey = "server:user")
      h.apply(history(proof, inFlight = "server"))
      assertEquals(setOf("first", "second"), h.pending.visibleRunIds)
      assertNull(h.pending.projection("server"))
    }

  @Test
  fun historyDoesNotAdoptAnOlderRunOverANewerLocalSend() =
    runTest {
      val h = Harness(this)
      h.send("new")
      h.apply(history(inFlight = "old"), ownedAfterRequest = setOf("new"))
      assertEquals(setOf("new"), h.pending.visibleRunIds)
      assertFalse(h.events.any { it is ChatPendingSendEvent.Streaming })
    }

  @Test
  fun visibleTimeoutCanSuspendWhileParkingAndDoesNotRetry() =
    runTest {
      val h = Harness(this)
      val releaseParking = CompletableDeferred<Unit>()
      h.park = { releaseParking.await() }
      h.send("local")
      runCurrent()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(1, h.reads.size)
      assertTrue(h.pending.visibleRunIds.isEmpty())
      assertTrue(
        h.transcript.messages.value
          .isEmpty(),
      )
      assertTrue(h.pending.consumeTimeout("local"))
      assertFalse(h.pending.consumeTimeout("local"))
      assertTrue(h.parked.isEmpty())
      releaseParking.complete(Unit)
      runCurrent()
      assertEquals(listOf("local"), h.parked)
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(1, h.reads.size)
      assertEquals(1, h.events.count { it == ChatPendingSendEvent.ReplyTimedOut })
    }

  @Test
  fun authoritativeInFlightRefreshRearmsWithoutFalseTimeout() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.refresh = {
        h.apply(history(send.optimisticMessage, inFlight = "local"))
        ChatPendingHistoryResult.Applied
      }
      runCurrent()
      repeat(2) {
        advanceTimeBy(TIMEOUT)
        runCurrent()
        assertTrue(h.pending.isPending("local"))
        assertTrue(h.pending.hasReply("local"))
        assertTrue(h.parked.isEmpty())
      }
      assertEquals(2, h.reads.size)
      assertTrue(h.events.any { it == ChatPendingSendEvent.Streaming("local", "working") })
      assertFalse(h.events.contains(ChatPendingSendEvent.ReplyTimedOut))
    }

  @Test
  fun supersededReadDefersToNewerHistoryInsteadOfExpiringOwnership() =
    runTest {
      val h = Harness(this)
      h.send("local")
      h.refresh = { ChatPendingHistoryResult.Superseded }
      runCurrent()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertTrue(h.pending.isPending("local"))
      assertTrue(h.parked.isEmpty())
      h.refresh = { ChatPendingHistoryResult.Unavailable }
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(listOf("local"), h.parked)
    }

  @Test
  fun staleWatchdogCannotClearAReplacementEvenIfReadIgnoresCancellation() =
    runTest {
      val h = Harness(this)
      val late = CompletableDeferred<ChatPendingHistoryResult>()
      h.refresh = { withContext(NonCancellable) { late.await() } }
      h.send("local")
      runCurrent()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(1, h.reads.size)
      h.pending.clear("local")
      val replacement = h.send("local", text = "new message")
      late.complete(ChatPendingHistoryResult.Unavailable)
      runCurrent()
      assertTrue(h.pending.isPending("local"))
      assertEquals(replacement, h.pending.projection("local"))
      assertTrue(h.parked.isEmpty())
      assertFalse(h.events.contains(ChatPendingSendEvent.ReplyTimedOut))
    }

  @Test
  fun disconnectedOwnershipRestoresWithoutDuplicatingTheEcho() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.pending.markUnknown("local")
      h.pending.rememberDisconnected()
      h.pending.clearAll(clearOptimisticMessages = false, preserveDisconnectedOwnership = true)
      assertFalse(h.pending.isPending("local"))
      assertTrue(h.pending.hasReply("local"))
      h.pending.restoreDisconnected()
      assertTrue(h.pending.isPending("local"))
      assertEquals(listOf(send.optimisticMessage), h.transcript.messages.value)
      runCurrent()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(1, h.reads.size)
      assertEquals(listOf("local"), h.parked)
    }

  @Test
  fun unresolvedReplyRecoveryReadsTwiceThenParksOutsideThePublicationLock() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.apply(history(send.optimisticMessage), setOf("local"))
      h.pending.scheduleRecovery("main", h.transcript.generation, emptySet())
      runCurrent()
      advanceTimeBy(RETRY)
      runCurrent()
      assertEquals(1, h.reads.size)
      assertEquals(setOf("local"), h.reads.single().runs)
      assertTrue(h.pending.hasReply("local"))
      advanceTimeBy(TIMEOUT - RETRY)
      runCurrent()
      assertEquals(2, h.reads.size)
      assertEquals(listOf("local"), h.parked)
      assertFalse(h.pending.hasReply("local"))
      assertEquals(1, h.events.count { it == ChatPendingSendEvent.ConfirmationTimedOut })
    }

  @Test
  fun olderGenerationCannotReplaceNewerRecovery() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.apply(history(send.optimisticMessage), setOf("local"))
      h.transcript.nextLoad()
      h.pending.scheduleRecovery("main", h.transcript.generation, setOf("local"))
      h.pending.scheduleRecovery("main", h.transcript.generation - 1, setOf("local"))
      runCurrent()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(2, h.reads.size)
      assertTrue(h.reads.all { it.generation == h.transcript.generation })
      assertEquals(listOf("local"), h.parked)
    }

  @Test
  fun recoveryConnectionChangeRetiresLateConfirmation() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.apply(history(send.optimisticMessage), setOf("local"))
      val late = CompletableDeferred<ChatPendingHistoryResult>()
      h.refresh = { if (h.reads.size == 2) late.await() else ChatPendingHistoryResult.Unavailable }
      h.pending.scheduleRecovery("main", h.transcript.generation, setOf("local"))
      runCurrent()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(2, h.reads.size)
      h.gateway = ChatCacheScope("gateway", 2)
      late.complete(ChatPendingHistoryResult.Unavailable)
      runCurrent()
      assertTrue(h.pending.hasReply("local"))
      assertTrue(h.parked.isEmpty())
      assertFalse(h.events.contains(ChatPendingSendEvent.ConfirmationTimedOut))
    }

  @Test
  fun cancelledRecoveryCannotPublishALateTimeout() =
    runTest {
      val h = Harness(this)
      val send = h.send("local")
      h.apply(history(send.optimisticMessage), setOf("local"))
      val late = CompletableDeferred<ChatPendingHistoryResult>()
      h.refresh = { if (h.reads.size == 2) withContext(NonCancellable) { late.await() } else ChatPendingHistoryResult.Unavailable }
      h.pending.scheduleRecovery("main", h.transcript.generation, setOf("local"))
      runCurrent()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertEquals(2, h.reads.size)
      backgroundScope.cancel()
      late.complete(ChatPendingHistoryResult.Unavailable)
      runCurrent()
      assertTrue(h.pending.hasReply("local"))
      assertTrue(h.parked.isEmpty())
      assertFalse(h.events.contains(ChatPendingSendEvent.ConfirmationTimedOut))
    }

  @Test
  fun scopeCancellationStopsWatchdogsBeforeAnyIo() =
    runTest {
      val h = Harness(this)
      h.send("local")
      runCurrent()
      backgroundScope.cancel()
      advanceTimeBy(TIMEOUT)
      runCurrent()
      assertTrue(h.reads.isEmpty())
      assertTrue(h.parked.isEmpty())
    }

  private data class Read(
    val key: String,
    val generation: Long,
    val runs: Set<String>,
  )

  private class Harness(
    scope: TestScope,
  ) {
    val lock = Any()
    var gateway = ChatCacheScope("gateway", 1)
    var owner = ChatComposerOwner("gateway", "alice", "main")
    val selection = ChatSessionSelection(lock, { gateway }, { "alice" }, { 0L })
    val transcript =
      ChatTranscript(
        scope.backgroundScope,
        lock,
        selection,
        { gateway },
        { 0L },
        ChatHistoryCodec(Json, ChatSessionCatalogCodec(Json)),
        awaitSessionReadiness = { _, _ -> },
        requestHistory = { error("Unexpected transport call") },
        cache = null,
        cacheMutationMutex = Mutex(),
      )
    val runState = ChatRunState()
    val events = mutableListOf<ChatPendingSendEvent>()
    val reads = mutableListOf<Read>()
    val parked = mutableListOf<String>()
    var refresh: suspend (Read) -> ChatPendingHistoryResult = { ChatPendingHistoryResult.Unavailable }
    var park: suspend (String) -> Unit = { yield() }
    val pending =
      ChatPendingSends(
        scope.backgroundScope,
        lock,
        transcript,
        runState,
        context = { ChatPendingSendContext(gateway, owner, selection.key.value, true, emptySet()) },
        refreshHistory = { key, generation, runs ->
          assertFalse("History I/O must run outside the publication lock", Thread.holdsLock(lock))
          val read = Read(key, generation, runs)
          reads += read
          refresh(read)
        },
        parkUnconfirmed = { id ->
          assertFalse("Durable I/O must run outside the publication lock", Thread.holdsLock(lock))
          park(id)
          parked += id
        },
        publish = events::add,
        timeoutMs = TIMEOUT,
        recoveryDelayMs = RETRY,
      )

    fun send(
      id: String,
      text: String = id,
    ): ChatPendingSend {
      val send = ChatPendingSend(owner, id, message(text, key = "$id:user"))
      pending.record(send)
      pending.project(send)
      return send
    }

    fun select(key: String) =
      synchronized(lock) {
        transcript.beginSelection(true, false)
        selection.select(key, "alice")
        owner = owner.copy(sessionKey = key)
        pending.clearAll()
      }

    suspend fun apply(
      history: ChatHistory,
      reconcile: Set<String> = emptySet(),
      ownedAfterRequest: Set<String> = emptySet(),
    ): ChatPendingHistoryProjection {
      val request = (transcript.prepareRead(selection.key.value, transcript.generation) as ChatHistoryPreparation.Ready).request
      return synchronized(lock) {
        val projection = pending.beforeHistory(history, reconcile, true)
        assertTrue(transcript.publishLive(request, history, projection.optimisticMessages, projection.completionSettled))
        pending.afterHistory(history, reconcile, ownedAfterRequest)
        projection
      }
    }

    fun publishRun() {
      runState.publish(ChatRunSnapshot(selection.key.value, pending.visibleRunIds, emptyList(), false, null, pending.visibleRunIds.associateWith(pending::clockKey)))
    }
  }

  companion object {
    private const val TIMEOUT = 1_000L
    private const val RETRY = 100L

    private fun message(
      text: String,
      key: String = "$text:user",
      role: String = "user",
    ) = ChatMessage(id = text, role = role, content = listOf(ChatMessageContent(type = "text", text = text)), timestampMs = 1, idempotencyKey = key)

    private fun history(
      vararg messages: ChatMessage,
      inFlight: String? = null,
    ) = ChatHistory(
      "main",
      "session",
      null,
      messages.toList(),
      sessionInfo = ChatSessionEntry("main", null, endedAt = 100),
      inFlightRun = inFlight?.let { ChatInFlightRun(it, "working") },
    )
  }
}
