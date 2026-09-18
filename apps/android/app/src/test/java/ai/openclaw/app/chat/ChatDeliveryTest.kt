package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDeliveryTest {
  private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ClientStateDatabase::class.java).build()
  private val store = RoomChatCommandOutbox(database)

  @After
  fun tearDown() = database.close()

  @Test
  fun directDeliveryUsesCapturedPayloadAndSettlesTheActualClaim() =
    runTest {
      val h = ready(this)
      val attachment = OutgoingAttachment("image", "image/png", "one.png", "AQID")
      val attachments = mutableListOf(attachment)
      val request = h.request(attachments = attachments)
      attachments.clear()
      assertTrue(h.delivery.send(request))
      val accepted = h.row("one")
      assertEquals(ChatOutboxStatus.Accepted, accepted.status)
      assertEquals(listOf(attachment), h.journal.loadAttachments(accepted))
      assertEquals(JsonPrimitive(KEY), h.frames.single()["sessionKey"])
      assertEquals(JsonPrimitive("alice"), h.frames.single()["agentId"])
      assertEquals(JsonPrimitive("one"), h.frames.single()["idempotencyKey"])
      assertTrue(h.pending.isPending("server"))
      assertEquals(
        "one:user",
        h.pending.optimisticMessages
          .single()
          .idempotencyKey,
      )
    }

  @Test
  fun offlineInputRemainsQueuedWithoutCallingTheTransport() =
    runTest {
      val h = ready(this)
      h.healthy = false
      assertTrue(h.delivery.send(h.request()))
      assertEquals(ChatOutboxStatus.Queued, h.row("one").status)
      assertTrue(h.frames.isEmpty())
      assertTrue(h.pending.locallyOwnedRunIds.isEmpty())
    }

  @Test
  fun selectionChangeDuringAdmissionRestoresOnlyStillQueuedInput() =
    runTest {
      val h = ready(this)
      val gate = CompletableDeferred<Unit>()
      val entered = CompletableDeferred<Unit>()
      h.beforeAdmission = {
        entered.complete(Unit)
        gate.await()
      }
      val result = async { h.delivery.send(h.request()) }
      entered.await()
      h.selectOther()
      gate.complete(Unit)
      assertFalse(result.await())
      assertTrue(store.load("gateway").isEmpty())
      assertTrue(h.frames.isEmpty())
      assertTrue(h.events.isEmpty())
    }

  @Test
  fun stopBetweenAdmissionAndPhysicalEnqueueParksWithoutSending() =
    runTest {
      val h = ready(this)
      val gate = CompletableDeferred<Unit>()
      val entered = CompletableDeferred<Unit>()
      h.beforeEnqueue = {
        entered.complete(Unit)
        gate.await()
      }
      val result = async { h.delivery.send(h.request()) }
      entered.await()
      assertTrue(h.stops.stop(ChatStopTarget(h.owner, "session", h.gateway, setOf("one"))))
      gate.complete(Unit)
      assertTrue(result.await())
      assertEquals(ChatOutboxStatus.Failed, h.row("one").status)
      assertEquals(OUTBOX_CHAT_STOPPED_ERROR, h.row("one").lastError)
      assertTrue(h.frames.isEmpty())
      assertFalse(h.pending.isLocallyOwned("one"))
    }

  @Test
  fun leavingTheCallerAfterEnqueueDoesNotCancelDurableSettlement() =
    runTest {
      val h = ready(this)
      val ack = CompletableDeferred<String>()
      h.response = { ack.await() }
      val result = async { h.delivery.send(h.request()) }
      h.enqueued.await()
      result.cancelAndJoin()
      assertEquals(ChatOutboxStatus.Sending, h.row("one").status)
      ack.complete(STARTED)
      h.awaitRow("one", ChatOutboxStatus.Accepted)
      assertEquals(1, h.frames.size)
    }

  @Test
  fun lateFailureBelongsToTheCapturedChatNotTheNewSelection() =
    runTest {
      val h = ready(this)
      val ack = CompletableDeferred<String>()
      h.response = { ack.await() }
      val result = async { h.delivery.send(h.request()) }
      h.enqueued.await()
      h.selectOther()
      h.events.clear()
      ack.complete("""{"status":"error","runId":"server"}""")
      assertTrue(result.await())
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, h.row("one").lastError)
      assertTrue(h.events.isEmpty())
      assertTrue(h.pending.visibleRunIds.isEmpty())
      assertEquals(JsonPrimitive(KEY), h.frames.single()["sessionKey"])
    }

  @Test
  fun directUnknownAcknowledgementKeepsOwnershipAndOnlyReadsHistory() =
    runTest {
      val h = ready(this)
      h.response = { throw GatewayRequestOutcomeUnknown("lost acknowledgement") }
      assertTrue(h.delivery.send(h.request()))
      assertEquals(ChatOutboxStatus.Accepted, h.row("one").status)
      assertTrue(h.pending.isLocallyOwned("one"))
      assertEquals(listOf(setOf("one")), h.refreshes)
      repeat(3) { h.delivery.requestFlush() }
      h.awaitDrain()
      assertEquals(1, h.frames.size)
      assertEquals(ChatOutboxStatus.Accepted, h.row("one").status)
    }

  @Test
  fun transmittedRejectionParksInputInsteadOfMakingItRetryable() =
    runTest {
      val h = ready(this)
      h.response = { throw GatewayRequestRejected(GatewaySession.ErrorShape("UNAVAILABLE", "cached failure")) }
      assertTrue(h.delivery.send(h.request()))
      assertEquals(ChatOutboxStatus.Failed, h.row("one").status)
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, h.row("one").lastError)
      h.delivery.requestFlush()
      h.awaitDrain()
      assertEquals(1, h.frames.size)
      assertFalse(h.pending.isLocallyOwned("one"))
    }

  @Test
  fun provenNonEnqueueRetainsOneQueuedInputAndStopsTheDrain() =
    runTest {
      val h = ready(this)
      h.beforeEnqueue = { throw GatewayRequestNotEnqueued("socket retired") }
      assertTrue(h.delivery.send(h.request()))
      assertEquals(ChatOutboxStatus.Queued, h.row("one").status)
      assertFalse(h.healthy)
      assertTrue(h.frames.isEmpty())
      assertFalse(h.pending.isLocallyOwned("one"))
    }

  @Test
  fun knownUnsupportedThinkingIsAppliedOnlyToTheVisibleQueuedSession() =
    runTest {
      val h = ready(this)
      h.supportsThinking = false
      h.queued("one")
      h.delivery.requestFlush()
      h.awaitRow("one", ChatOutboxStatus.Accepted)
      assertEquals(JsonPrimitive("off"), h.frames.single()["thinking"])
    }

  @Test
  fun queuedSettingsWaitKeepsOriginalOwnerAndCapturedPhysicalLease() =
    runTest {
      val h = ready(this)
      val gate = CompletableDeferred<Unit>()
      val entered = CompletableDeferred<Unit>()
      h.settings = { gateway, key, agent ->
        assertEquals(h.gateway, gateway)
        assertEquals(KEY, key)
        assertEquals("alice", agent)
        entered.complete(Unit)
        gate.await()
        true
      }
      h.queued("one")
      h.delivery.requestFlush()
      entered.await()
      h.selectOther()
      h.supportsThinking = false
      h.currentLease = GatewaySession.RequestLease("gateway") { _, _, _, _ -> error("must not recapture") }
      gate.complete(Unit)
      h.awaitRow("one", ChatOutboxStatus.Accepted)
      assertEquals(JsonPrimitive(KEY), h.frames.single()["sessionKey"])
      assertEquals(JsonPrimitive("high"), h.frames.single()["thinking"])
      assertTrue(h.pending.visibleRunIds.isEmpty())
      assertEquals(KEY, h.permissions.single().sessionKey)
    }

  @Test
  fun repeatedFlushRequestsDoNotClaimAnInFlightRowTwice() =
    runTest {
      val h = ready(this)
      val ack = CompletableDeferred<String>()
      h.response = { ack.await() }
      h.queued("one")
      h.delivery.requestFlush()
      h.enqueued.await()
      repeat(10) { h.delivery.requestFlush() }
      ack.complete(STARTED)
      h.awaitRow("one", ChatOutboxStatus.Accepted)
      h.awaitDrain()
      assertEquals(1, h.frames.size)
      assertTrue(h.pending.isLocallyOwned("server"))
    }

  @Test
  fun queuedUnknownOutcomeStopsBeforeYoungerWorkAndNeverResends() =
    runTest {
      val h = ready(this)
      h.response = { throw GatewayRequestOutcomeUnknown("lost acknowledgement") }
      h.queued("one")
      h.queued("two")
      h.delivery.requestFlush()
      h.awaitRow("one", ChatOutboxStatus.Failed)
      h.awaitDrain()
      assertEquals(ChatOutboxStatus.Queued, h.row("two").status)
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, h.row("one").lastError)
      assertFalse(h.healthy)
      assertEquals(1, h.frames.size)
    }

  @Test
  fun orphanProofRetiresTheHeadBeforeSendingItsQueuedSuccessor() =
    runTest {
      val h = ready(this)
      val first = h.queued("one")
      val claimed = (h.journal.claim(first) as ChatJournalClaim.Claimed).item
      assertEquals(1, h.journal.update(claimed, ChatOutboxStatus.Accepted, null))
      h.queued("two")
      h.history = { gateway, owner ->
        assertEquals(h.gateway, gateway)
        assertEquals(KEY, owner.sessionKey)
        history("one")
      }
      h.delivery.requestFlush()
      h.awaitRow("two", ChatOutboxStatus.Accepted)
      assertNull(store.load("gateway").firstOrNull { it.id == "one" })
      assertEquals(JsonPrimitive("two"), h.frames.single()["idempotencyKey"])
    }

  @Test
  fun orphanWithNoCanonicalProofIsParkedAfterTwoReadsNotRedispatched() =
    runTest {
      val h = ready(this)
      val first = h.queued("one")
      val claimed = (h.journal.claim(first) as ChatJournalClaim.Claimed).item
      assertEquals(1, h.journal.update(claimed, ChatOutboxStatus.Accepted, null))
      h.delivery.requestFlush()
      h.awaitRow("one", ChatOutboxStatus.Failed)
      assertEquals(2, h.historyReads.size)
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, h.row("one").lastError)
      assertTrue(h.frames.isEmpty())
    }

  @Test
  fun queuedDrainWinningTheDirectClaimStillSendsTheInputOnlyOnce() =
    runTest {
      val h = ready(this)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      var first = true
      h.beforeClaim = {
        if (first) {
          first = false
          entered.complete(Unit)
          release.await()
        }
      }
      val direct = async { h.delivery.send(h.request()) }
      entered.await()
      h.delivery.requestFlush()
      h.awaitRow("one", ChatOutboxStatus.Accepted)
      release.complete(Unit)
      assertTrue(direct.await())
      h.awaitDrain()
      assertEquals(1, h.frames.size)
      assertEquals(1, store.load("gateway").size)
      assertEquals(ChatOutboxStatus.Accepted, h.row("one").status)
      assertTrue(h.pending.isLocallyOwned("server"))
    }

  @Test
  fun queuedPermissionPrerequisiteRefusalDoesNotClaimOrSend() =
    runTest {
      val h = ready(this)
      h.permissionReady = false
      h.queued("one")
      h.awaitDrain()
      assertEquals(ChatOutboxStatus.Queued, h.row("one").status)
      assertEquals(listOf(h.owner), h.permissions)
      assertTrue(h.frames.isEmpty())
      assertTrue(h.healthy)
    }

  @Test
  fun terminalSuccessSettlesReplyAndRequestsCanonicalHistory() =
    runTest {
      val h = ready(this)
      h.response = { """{"status":"ok","runId":"server"}""" }
      assertTrue(h.delivery.send(h.request()))
      assertTrue(h.pending.visibleRunIds.isEmpty())
      assertFalse(h.pending.isLocallyOwned("server"))
      assertTrue(h.refreshes.contains(setOf("server")))
      assertTrue(h.events.contains(ChatDeliveryEvent.Idle))
      assertEquals(1, h.frames.size)
    }

  @Test
  fun originUsesExistingSelectionAndDefaultOwnerRevisions() =
    runTest {
      val h = ready(this)
      val tracked = h.request().origin.copy(defaultAgentRevision = 0)
      assertTrue(tracked.ownsUi(h.context()))
      h.selectionGeneration++
      assertFalse(tracked.ownsUi(h.context()))
      h.selectionGeneration--
      h.defaultAgentRevision++
      assertFalse(tracked.ownsUi(h.context()))
      assertTrue(tracked.copy(defaultAgentRevision = null).ownsUi(h.context()))
    }

  private suspend fun ready(scope: TestScope) = Harness(scope).also { assertTrue(it.state.awaitRecovery()) }

  private inner class Harness(
    val testScope: TestScope,
  ) {
    val lock = Any()
    var gateway = ChatCacheScope("gateway", 1)
    var owner = ChatComposerOwner("gateway", "alice", KEY)
    var selectionGeneration = 0L
    var defaultAgentRevision = 0L
    var healthy = true
    var supportsThinking = true
    var permissionReady = true
    var beforeAdmission: suspend () -> Unit = {}
    var beforeClaim: suspend () -> Unit = {}
    var beforeEnqueue: suspend () -> Unit = {}
    var response: suspend () -> String = { STARTED }
    var settings: suspend (ChatCacheScope, String, String) -> Boolean = { _, _, _ -> true }
    var history: suspend (ChatCacheScope, ChatComposerOwner) -> ChatHistory = { _, _ -> history() }
    val frames = CopyOnWriteArrayList<JsonObject>()
    val events = CopyOnWriteArrayList<ChatDeliveryEvent>()
    val refreshes = CopyOnWriteArrayList<Set<String>>()
    val permissions = CopyOnWriteArrayList<ChatComposerOwner>()
    val historyReads = CopyOnWriteArrayList<ChatComposerOwner>()
    val enqueued = CompletableDeferred<Unit>()
    private val executionJob = SupervisorJob(testScope.backgroundScope.coroutineContext[Job])
    private val executionScope = CoroutineScope(testScope.backgroundScope.coroutineContext + executionJob)
    val adapter =
      object : ChatCommandOutbox by store {
        override suspend fun enqueue(
          gatewayId: String,
          sessionKey: String,
          text: String,
          thinkingLevel: String,
          nowMs: Long,
          attachments: List<OutboxAttachmentPayload>,
          gatedEpoch: Long?,
          ownerAgentId: String,
          idempotencyKey: String?,
        ): ChatOutboxEnqueueResult {
          beforeAdmission()
          return store.enqueue(gatewayId, sessionKey, text, thinkingLevel, nowMs, attachments, gatedEpoch, ownerAgentId, idempotencyKey)
        }

        override suspend fun claimForSendingIfAttempt(
          id: String,
          expectedAttemptVersion: Int,
          retryCount: Int,
          lastError: String?,
        ): Int {
          beforeClaim()
          return store.claimForSendingIfAttempt(id, expectedAttemptVersion, retryCount, lastError)
        }
      }
    val state = ChatOutboxState(testScope.backgroundScope, adapter, lock, { gateway })
    val selection = ChatSessionSelection(lock, { gateway }, { "alice" }, { 0L })
    val transcript =
      ChatTranscript(
        testScope.backgroundScope,
        lock,
        selection,
        { gateway },
        { 0L },
        ChatHistoryCodec(Json, ChatSessionCatalogCodec(Json)),
        awaitSessionReadiness = { _, _ -> },
        requestHistory = { error("Unexpected transcript read") },
        cache = null,
        cacheMutationMutex = Mutex(),
      )
    val runState = ChatRunState()
    val pending =
      ChatPendingSends(
        // Room uses real I/O. Reply watchdogs must not skip two virtual minutes while a DB
        // callback is outstanding; their clock/race behavior has its own deterministic suite.
        CoroutineScope(testScope.backgroundScope.coroutineContext + Dispatchers.Default),
        lock,
        transcript,
        runState,
        context = { ChatPendingSendContext(gateway, owner, owner.sessionKey, healthy, emptySet()) },
        refreshHistory = { _, _, _ -> ChatPendingHistoryResult.Unavailable },
        parkUnconfirmed = {},
        publish = {},
      )
    val journal =
      ChatDeliveryJournal(
        adapter,
        state,
        Mutex(),
        { it },
        pending::isLocallyOwned,
        onStorageFailure = { healthy = false },
        resumeDelivery = { if (healthy) delivery.requestFlush() },
      )
    var currentLease =
      GatewaySession.RequestLease("gateway") { method, params, _, enqueue ->
        if (method != "chat.send") return@RequestLease "{}"
        beforeEnqueue()
        enqueue {
          frames += Json.parseToJsonElement(requireNotNull(params)).jsonObject
          enqueued.complete(Unit)
        }
        response()
      }
    val stops = ChatStopController(testScope.backgroundScope, { gateway }, { currentLease }, journal::parkStopped, { true })
    val delivery: ChatDelivery =
      ChatDelivery(
        scope = executionScope,
        commandOutbox = adapter,
        journal = journal,
        outboxState = state,
        pendingSends = pending,
        runState = runState,
        sendDispatcher = ChatSendDispatcher(Json, stops::enqueue),
        stops = stops,
        context = ::context,
        normalizeSessionKey = { it },
        awaitSettings = { gateway, key, agent -> settings(gateway, key, agent) },
        prepareSessionSend = { gateway, key, agent ->
          permissions += ChatComposerOwner(gateway, agent, key)
          permissionReady
        },
        captureRequestLease = { currentLease },
        readHistory = { gateway, owner ->
          historyReads += owner
          history(gateway, owner)
        },
        refreshHistory = { refreshes += it },
        onUnavailable = { healthy = false },
        publish = events::add,
      )

    fun context() = ChatDeliveryContext(gateway, owner, selectionGeneration, defaultAgentRevision, owner.sessionKey, healthy, supportsThinking)

    fun request(
      id: String = "one",
      attachments: List<OutgoingAttachment> = emptyList(),
    ) = ChatDeliveryRequest(
      ChatDeliveryOrigin(gateway, owner, selectionGeneration, null),
      owner.sessionKey,
      "hello",
      "high",
      attachments,
      id,
      currentLease,
      stops.revision(owner),
    )

    fun selectOther() {
      owner = owner.copy(sessionKey = "agent:alice:other")
      selectionGeneration++
      pending.clearAll()
    }

    suspend fun queued(id: String) = (journal.enqueue(gateway, KEY, id, "high", emptyList(), "alice", id) { true } as ChatJournalAdmission.Queued).item

    suspend fun row(id: String) = store.load("gateway").single { it.id == id }

    suspend fun awaitRow(
      id: String,
      status: ChatOutboxStatus,
    ) {
      withContext(Dispatchers.Default) {
        withTimeout(5_000) {
          while (store.load("gateway").firstOrNull { it.id == id }?.status != status) delay(10)
        }
      }
      testScope.runCurrent()
    }

    suspend fun awaitDrain() {
      // Join the actual delivery workers, excluding independent reply watchdogs. No test
      // endpoint or fixed sleep is needed to prove that the requested passes finished.
      delivery.requestFlush()
      while (true) {
        val jobs = executionJob.children.toList()
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
      }
    }
  }

  companion object {
    private const val KEY = "agent:alice:main"
    private const val STARTED = """{"status":"started","runId":"server"}"""

    private fun history(userId: String? = null) =
      ChatHistory(
        KEY,
        "session",
        null,
        messages = userId?.let { listOf(ChatMessage(id = "canonical", role = "user", content = listOf(ChatMessageContent(type = "text", text = it)), timestampMs = 1, idempotencyKey = "$it:user")) }.orEmpty(),
      )
  }
}
