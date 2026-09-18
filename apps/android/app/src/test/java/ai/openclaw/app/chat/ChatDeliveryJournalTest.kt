package ai.openclaw.app.chat

import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDeliveryJournalTest {
  private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ClientStateDatabase::class.java).build()
  private val store = RoomChatCommandOutbox(database)

  @After
  fun tearDown() = database.close()

  @Test
  fun admissionPreservesCapturedOwnerEpochAndAttachmentBytes() =
    runTest {
      val h = ready(this)
      val attachment = OutgoingAttachment("image", "image/png", "test.png", "AQID")
      val row = h.queued("command", text = "/status", attachments = listOf(attachment))
      assertEquals("alice", row.ownerAgentId)
      assertEquals("main", row.sessionKey)
      assertEquals(1L, row.gatedEpoch)
      assertEquals(listOf(attachment), h.journal.loadAttachments(row))
      assertEquals(
        row.id,
        h.state.items.value
          .single()
          .id,
      )
      assertTrue(store.wasAdmitted(row.id))
    }

  @Test
  fun admissionRefusalIsTypedAndDoesNotCreateInput() =
    runTest {
      val h = ready(this)
      val invalid = h.admit("invalid", attachments = listOf(OutgoingAttachment("image", "image/png", "image", "%")))
      assertEquals(ChatJournalAdmission.Failed(ChatJournalFailure.AttachmentInvalid), invalid)
      assertEquals(ChatJournalAdmission.Failed(ChatJournalFailure.Unavailable), h.admit("no-gateway", gateway = null))
      h.allowAdmission = false
      assertEquals(ChatJournalAdmission.Revoked, h.admit("revoked"))
      assertTrue(store.load("gateway").isEmpty())
    }

  @Test
  fun stopDuringAdmissionWaitIsCheckedInsideTheStorageBoundary() =
    runTest {
      val h = ready(this)
      h.mutex.lock()
      val admission = async { h.admit("late") }
      runCurrent()
      assertFalse(admission.isCompleted)
      h.allowAdmission = false
      h.mutex.unlock()
      assertEquals(ChatJournalAdmission.Revoked, admission.await())
      assertTrue(store.load("gateway").isEmpty())
    }

  @Test
  fun claimReturnsSendingSnapshotForActualStoreSettlement() =
    runTest {
      val h = ready(this)
      val queued = h.queued("one")
      val claimed = h.journal.claim(queued) as ChatJournalClaim.Claimed
      assertEquals(ChatOutboxStatus.Sending, claimed.item.status)
      assertEquals(queued.attemptVersion, claimed.item.attemptVersion)
      assertEquals(ChatJournalClaim.NotClaimed, h.journal.claim(queued))
      assertEquals(1, h.journal.update(claimed.item, ChatOutboxStatus.Accepted, null))
      assertEquals(ChatOutboxStatus.Accepted, store.load("gateway").single().status)
    }

  @Test
  fun deletingQueuedInputCannotDeleteAClaimedSend() =
    runTest {
      val h = ready(this)
      val first = h.queued("first")
      assertTrue(h.journal.removeIfQueued(first))
      assertFalse(store.wasAdmitted(first.id))
      val second = h.queued("second")
      h.journal.claim(second)
      assertFalse(h.journal.removeIfQueued(second))
      assertEquals(ChatOutboxStatus.Sending, store.load("gateway").single().status)
    }

  @Test
  fun fifoBlocksOnlyItsSessionAndOnlyUnresolvedRows() =
    runTest {
      val h = ready(this)
      val first = h.accepted("first")
      val second = h.queued("second")
      val other = h.queued("other", session = "other")
      val rows = listOf(first, second, other)
      assertEquals(other.id, h.journal.nextFlushable(rows)?.id)
      h.live += first.id
      assertEquals(second.id, h.journal.nextFlushable(rows)?.id)
    }

  @Test
  fun unavailableBacklogCannotAuthorizeOvertakingOlderInput() =
    runTest {
      val h = ready(this)
      val row = h.queued("one")
      h.read = { error("storage unavailable") }
      assertTrue(h.journal.hasBacklog("gateway", row))
      assertEquals(1, h.storageFailures)
      assertEquals(ChatOutboxStatus.Queued, store.load("gateway").single().status)
    }

  @Test
  fun failedSettlementRearmsRecoveryInsteadOfRetryingTheSend() =
    runTest {
      val h = ready(this)
      val row = h.queued("one")
      h.journal.claim(row)
      h.beforeUpdate = { error("storage unavailable") }
      h.journal.settle(row, ChatOutboxStatus.Accepted, null)
      assertEquals(1, h.storageFailures)
      assertEquals(ChatOutboxStatus.Sending, store.load("gateway").single().status)
      h.beforeUpdate = {}
      assertTrue(h.state.awaitRecovery())
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, store.load("gateway").single().lastError)
    }

  @Test
  fun stopParksOnlyQueuedRowsOfTheCapturedOwner() =
    runTest {
      val h = ready(this)
      h.queued("queued")
      val sending = h.queued("sending")
      h.journal.claim(sending)
      h.queued("other-chat", session = "other")
      h.queued("other-agent", owner = "bob")
      h.journal.parkStopped(ChatStopTarget(ChatComposerOwner("gateway", "alice", "main"), "session", h.gateway, emptySet()))
      val rows = store.load("gateway").associateBy { it.id }
      assertEquals(OUTBOX_CHAT_STOPPED_ERROR, rows["queued"]?.lastError)
      assertEquals(ChatOutboxStatus.Sending, rows["sending"]?.status)
      assertEquals(ChatOutboxStatus.Queued, rows["other-chat"]?.status)
      assertEquals(ChatOutboxStatus.Queued, rows["other-agent"]?.status)
    }

  @Test
  fun retryUsesDisplayedAttemptAndRechecksRevocationAfterWaiting() =
    runTest {
      val h = ready(this)
      val row = h.accepted("one")
      h.journal.update(row, ChatOutboxStatus.Failed, OUTBOX_DELIVERY_UNCONFIRMED_ERROR)
      val failed = store.load("gateway").single()
      h.mutex.lock()
      var allowed = true
      val retry = async { h.journal.retry(failed, h.gateway, "alice") { allowed } }
      runCurrent()
      allowed = false
      h.mutex.unlock()
      retry.await()
      assertEquals(failed, store.load("gateway").single())
      h.journal.retry(failed, h.gateway, "alice") { true }
      val retried = store.load("gateway").single()
      assertEquals(ChatOutboxStatus.Queued, retried.status)
      // Explicit retry keeps the command identity while advancing attempt ownership.
      assertEquals(failed.id, retried.id)
      assertEquals(failed.attemptVersion + 1, retried.attemptVersion)
      h.journal.retry(failed, h.gateway, "alice") { true }
      assertEquals(retried, store.load("gateway").single())
    }

  @Test
  fun canonicalProofRetiresOnlyMatchingSessionAgentAndGateway() =
    runTest {
      val h = ready(this)
      val row = h.accepted("one")
      assertFalse(h.journal.reconcileHistory("gateway", history("other", row.id), "alice"))
      assertFalse(h.journal.reconcileHistory("gateway", history("main", row.id), "bob"))
      assertFalse(h.journal.reconcileHistory("different", history("main", row.id), "alice"))
      assertEquals(1, store.load("gateway").size)
      assertTrue(h.journal.reconcileHistory("gateway", history("main", row.id), "alice"))
      assertTrue(store.load("gateway").isEmpty())
    }

  @Test
  fun absentProofRequiresTwoSightingsAndInFlightResetsTheCount() =
    runTest {
      val h = ready(this)
      val row = h.accepted("one")
      assertFalse(h.journal.reconcileHistory("gateway", history(), "alice"))
      assertFalse(h.journal.reconcileHistory("gateway", history(inFlight = row.id), "alice"))
      assertFalse(h.journal.reconcileHistory("gateway", history(), "alice"))
      assertEquals(ChatOutboxStatus.Accepted, store.load("gateway").single().status)
      assertTrue(h.journal.reconcileHistory("gateway", history(), "alice"))
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, store.load("gateway").single().lastError)
    }

  @Test
  fun acknowledgedRunKeepsOnlyItsOwnAttemptAlive() =
    runTest {
      val h = ready(this)
      val first = h.accepted("one")
      h.journal.recordAcknowledgement(first, "server-first")
      h.live += "server-first"
      assertTrue(h.journal.isLocallyOwned(first))
      store.updateStatusIfAttempt(first.id, first.attemptVersion, ChatOutboxStatus.Queued, 0, null, ChatOutboxStatus.Accepted)
      val next = store.load("gateway").single()
      assertFalse(h.journal.isLocallyOwned(next))
      h.journal.recordAcknowledgement(next, "server-next")
      h.live += "server-next"
      h.journal.settle(first, ChatOutboxStatus.Failed, "late failure")
      assertTrue(h.journal.isLocallyOwned(next))
      assertEquals(next, store.load("gateway").single())
    }

  @Test
  fun staleConfirmationCannotRetireOrForgetANewerAttempt() =
    runTest {
      val h = ready(this)
      val old = h.accepted("one")
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      h.beforeConfirm = {
        entered.complete(Unit)
        release.await()
      }
      val proof = async { h.journal.reconcileHistory("gateway", history("main", old.id), "alice") }
      entered.await()
      store.updateStatusIfAttempt(old.id, old.attemptVersion, ChatOutboxStatus.Queued, 0, null, ChatOutboxStatus.Accepted)
      val next = store.load("gateway").single()
      h.journal.recordAcknowledgement(next, "new-run")
      h.live += "new-run"
      release.complete(Unit)
      assertFalse(proof.await())
      assertEquals(next, store.load("gateway").single())
      assertTrue(h.journal.isLocallyOwned(next))
    }

  @Test
  fun newerAttemptDoesNotInheritTheOldAbsentProofCount() =
    runTest {
      val h = ready(this)
      val old = h.accepted("one")
      assertFalse(h.journal.reconcileHistory("gateway", history(), "alice"))
      store.updateStatusIfAttempt(old.id, old.attemptVersion, ChatOutboxStatus.Queued, 0, null, ChatOutboxStatus.Accepted)
      val queued = store.load("gateway").single()
      val sending = (h.journal.claim(queued) as ChatJournalClaim.Claimed).item
      h.journal.update(sending, ChatOutboxStatus.Accepted, null)
      assertFalse(h.journal.reconcileHistory("gateway", history(), "alice"))
      assertEquals(ChatOutboxStatus.Accepted, store.load("gateway").single().status)
      assertTrue(h.journal.reconcileHistory("gateway", history(), "alice"))
    }

  @Test
  fun deadlineParkingRecognizesAcknowledgedIdentityWithoutRequeue() =
    runTest {
      val h = ready(this)
      val row = h.accepted("one")
      h.journal.recordAcknowledgement(row, "server")
      h.state.refresh()
      h.journal.parkUnconfirmed("server")
      val parked = store.load("gateway").single()
      assertEquals(ChatOutboxStatus.Failed, parked.status)
      assertEquals(row.id, parked.id)
      assertEquals(row.attemptVersion, parked.attemptVersion)
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, parked.lastError)
    }

  @Test
  fun staleCommandParkingFailureStopsInsteadOfReportingSafeDelivery() =
    runTest {
      val h = ready(this)
      val row = h.queued("command", text = "/status")
      h.beforeUpdate = { error("storage unavailable") }
      assertTrue(h.journal.parkStaleCommands(listOf(row), h.gateway.copy(connectionGeneration = 2)))
      assertEquals(1, h.storageFailures)
      assertEquals(ChatOutboxStatus.Queued, store.load("gateway").single().status)
    }

  @Test
  fun cancellationDuringHistoryReadIsNotAnEmptySuccess() =
    runTest {
      val h = ready(this)
      h.accepted("one")
      h.read = { throw CancellationException("stopped") }
      val pending = async { h.journal.reconcileHistory("gateway", history(), "alice") }
      runCurrent()
      assertTrue(pending.isCancelled)
      assertEquals(ChatOutboxStatus.Accepted, store.load("gateway").single().status)
      assertEquals(0, h.storageFailures)
    }

  private suspend fun ready(scope: CoroutineScope) = Harness(scope).also { assertTrue(it.state.awaitRecovery()) }

  private inner class Harness(
    scope: CoroutineScope,
  ) {
    val gateway = ChatCacheScope("gateway", 1)
    val mutex = Mutex()
    val live = mutableSetOf<String>()
    var allowAdmission = true
    var storageFailures = 0
    var resumptions = 0
    var read: suspend (String) -> List<ChatOutboxItem> = { store.load(it) }
    var beforeUpdate: suspend () -> Unit = {}
    var beforeConfirm: suspend () -> Unit = {}
    val adapter =
      object : ChatCommandOutbox by store {
        override suspend fun load(gatewayId: String) = read(gatewayId)

        override suspend fun updateStatusIfAttempt(
          id: String,
          expectedAttemptVersion: Int,
          status: ChatOutboxStatus,
          retryCount: Int,
          lastError: String?,
          expectedStatus: ChatOutboxStatus?,
        ): Int {
          beforeUpdate()
          return store.updateStatusIfAttempt(id, expectedAttemptVersion, status, retryCount, lastError, expectedStatus)
        }

        override suspend fun confirmDeliveredAttempts(ids: Map<String, Int>): Int {
          beforeConfirm()
          return store.confirmDeliveredAttempts(ids)
        }
      }
    val state = ChatOutboxState(scope, adapter, Any(), { gateway })
    val journal = ChatDeliveryJournal(adapter, state, mutex, { it }, live::contains, { storageFailures++ }, { resumptions++ })

    suspend fun admit(
      id: String,
      text: String = id,
      session: String = "main",
      owner: String = "alice",
      attachments: List<OutgoingAttachment> = emptyList(),
      gateway: ChatCacheScope? = this.gateway,
    ) = journal.enqueue(gateway, session, text, "off", attachments, owner, id) { allowAdmission }

    suspend fun queued(
      id: String,
      text: String = id,
      session: String = "main",
      owner: String = "alice",
      attachments: List<OutgoingAttachment> = emptyList(),
    ) = (admit(id, text, session, owner, attachments) as ChatJournalAdmission.Queued).item

    suspend fun accepted(id: String): ChatOutboxItem {
      val row = queued(id)
      val sending = (journal.claim(row) as ChatJournalClaim.Claimed).item
      assertEquals(1, journal.update(sending, ChatOutboxStatus.Accepted, null))
      return store.load("gateway").first { it.id == id }
    }
  }

  companion object {
    private fun history(
      key: String = "main",
      userId: String? = null,
      inFlight: String? = null,
    ) = ChatHistory(
      key,
      "session",
      null,
      messages = userId?.let { listOf(ChatMessage(id = "canonical", role = "user", content = listOf(ChatMessageContent(type = "text", text = it)), timestampMs = 1, idempotencyKey = "$it:user")) }.orEmpty(),
      inFlightRun = inFlight?.let { ChatInFlightRun(it, "working") },
    )
  }
}
