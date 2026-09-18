package ai.openclaw.app.chat

import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
class ChatOutboxStateTest {
  private val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ClientStateDatabase::class.java).build()
  private val store = RoomChatCommandOutbox(database)
  private val lock = Any()
  private var gateway: ChatCacheScope? = ChatCacheScope("gateway", 1)
  private val calls = mutableListOf<String>()
  private var recover: suspend () -> Unit = { store.failSendingAfterRestart() }
  private var load: suspend (String) -> List<ChatOutboxItem> = { store.load(it) }
  private var expire: suspend (String, Long) -> Unit = { id, time -> store.expireStale(id, time) }

  private fun call(name: String) {
    assertFalse("Storage I/O must run outside the publication lock", Thread.holdsLock(lock))
    calls += name
  }

  private fun state(scope: CoroutineScope) =
    ChatOutboxState(
      scope,
      object : ChatCommandOutbox by store {
        override suspend fun failSendingAfterRestart() {
          call("recover")
          recover()
        }

        override suspend fun load(gatewayId: String): List<ChatOutboxItem> {
          call("load:$gatewayId")
          return this@ChatOutboxStateTest.load(gatewayId)
        }

        override suspend fun expireStale(
          gatewayId: String,
          nowMs: Long,
        ) {
          call("expire:$gatewayId")
          expire(gatewayId, nowMs)
        }
      },
      lock,
      { gateway },
    )

  @After
  fun tearDown() = database.close()

  private fun row(id: String) =
    ChatOutboxItem(
      id = id,
      sessionKey = "main",
      text = id,
      thinkingLevel = "off",
      createdAtMs = 1,
      status = ChatOutboxStatus.Queued,
      retryCount = 0,
      lastError = null,
      ownerAgentId = "alice",
    )

  @Test
  fun absentStoreStartsRestoredAndRequiresNoStartupWork() =
    runTest {
      val state = ChatOutboxState(this, null, lock, { gateway })
      assertTrue(state.restored.value)
      assertTrue(state.awaitRecovery())
      state.clear()
      state.refresh()
      assertTrue(state.restored.value)
      assertTrue(state.items.value.isEmpty())
    }

  @Test
  fun startupRecoversThenExpiresThenPublishesAndWaitersShareOneBarrier() =
    runTest {
      val release = CompletableDeferred<Unit>()
      recover = { release.await() }
      load = { listOf(row("queued")) }
      val state = state(this)
      val first = async { state.awaitRecovery() }
      val second = async { state.awaitRecovery() }
      runCurrent()
      assertEquals(listOf("recover"), calls)
      assertFalse(first.isCompleted)
      assertFalse(second.isCompleted)
      assertFalse(state.restored.value)
      release.complete(Unit)
      assertTrue(first.await())
      assertTrue(second.await())
      assertEquals(listOf("recover", "expire:gateway", "load:gateway"), calls)
      assertEquals(listOf(row("queued")), state.items.value)
      assertTrue(state.restored.value)
    }

  @Test
  fun startupRecoversAmbiguousSendingWithoutReclassifyingLaterLiveDispatch() =
    runTest {
      val admitted = store.enqueue("gateway", "main", "old", "off", System.currentTimeMillis(), ownerAgentId = "alice") as ChatOutboxEnqueueResult.Queued
      store.updateStatus(admitted.item.id, ChatOutboxStatus.Sending, 0, null)
      val state = state(this)
      assertTrue(state.awaitRecovery())
      val restored = state.items.value.single()
      assertEquals(ChatOutboxStatus.Failed, restored.status)
      assertEquals(OUTBOX_DELIVERY_UNCONFIRMED_ERROR, restored.lastError)
      val live = store.enqueue("gateway", "other", "live", "off", System.currentTimeMillis(), ownerAgentId = "alice") as ChatOutboxEnqueueResult.Queued
      store.updateStatus(live.item.id, ChatOutboxStatus.Sending, 0, null)
      gateway = ChatCacheScope("gateway", 2)
      state.clear()
      assertTrue(state.awaitRecovery())
      state.refresh()
      assertEquals(
        ChatOutboxStatus.Sending,
        state.items.value
          .first { it.id == live.item.id }
          .status,
      )
      assertEquals(1, calls.count { it == "recover" })
    }

  @Test
  fun failedRecoveryIsNotRetriedInBackgroundAndBlocksUntilExplicitBarrierSucceeds() =
    runTest {
      recover = { error("storage unavailable") }
      val state = state(this)
      assertFalse(state.awaitRecovery())
      assertFalse(calls.any { it.startsWith("expire:") })
      val attempts = calls.count { it == "recover" }
      runCurrent()
      assertEquals(attempts, calls.count { it == "recover" })
      recover = {}
      assertTrue(state.awaitRecovery())
      assertEquals(attempts + 1, calls.count { it == "recover" })
    }

  @Test
  fun cancelledRecoveryDoesNotMarkBarrierCompleteAndCanBeExplicitlyRetried() =
    runTest {
      recover = { throw CancellationException("recovery interrupted") }
      val state = state(this)
      val pending = async { state.awaitRecovery() }
      try {
        pending.await()
        error("Expected cancellation")
      } catch (_: CancellationException) {
        assertTrue(pending.isCancelled)
      }
      assertFalse(state.restored.value)
      assertFalse(calls.any { it.startsWith("expire:") || it.startsWith("load:") })
      recover = {}
      assertTrue(state.awaitRecovery())
    }

  @Test
  fun expirationFailureDoesNotHideRecoveredDurableRows() =
    runTest {
      expire = { _, _ -> error("expiration unavailable") }
      load = { listOf(row("queued")) }
      val state = state(this)
      assertTrue(state.awaitRecovery())
      assertEquals(listOf(row("queued")), state.items.value)
      assertTrue(state.restored.value)
      assertEquals(listOf("recover", "expire:gateway", "load:gateway"), calls)
    }

  @Test
  fun expirationCancellationStopsStartupBeforeSnapshotIo() =
    runTest {
      expire = { _, _ -> throw CancellationException("startup retired") }
      val state = state(this)
      assertTrue(state.awaitRecovery())
      assertEquals(listOf("recover", "expire:gateway"), calls)
      assertFalse(state.restored.value)
    }

  @Test
  fun explicitRearmSerializesWithRecoveryAndForcesNextBarrierToRecover() =
    runTest {
      val state = state(this)
      assertTrue(state.awaitRecovery())
      state.requireRecovery()
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      recover = {
        entered.complete(Unit)
        release.await()
      }
      val barrier = async { state.awaitRecovery() }
      entered.await()
      val rearm = async { state.requireRecovery() }
      runCurrent()
      assertFalse(rearm.isCompleted)
      release.complete(Unit)
      assertTrue(barrier.await())
      rearm.await()
      recover = {}
      assertTrue(state.awaitRecovery())
      assertEquals(3, calls.count { it == "recover" })
    }

  @Test
  fun noConnectionStillRecoversButDoesNotLoadOrClaimRestoredPresentation() =
    runTest {
      gateway = null
      val state = state(this)
      assertTrue(state.awaitRecovery())
      assertEquals(listOf("recover"), calls)
      assertFalse(state.restored.value)
      gateway = ChatCacheScope("gateway", 1)
      state.refresh()
      assertTrue(state.restored.value)
      assertEquals(listOf("recover", "load:gateway"), calls)
    }

  @Test
  fun newerSnapshotWinsOverSlowOldReadWithinSameConnection() =
    runTest {
      val state = state(this)
      state.awaitRecovery()
      val old = CompletableDeferred<List<ChatOutboxItem>>()
      load = { old.await() }
      val pending = async { state.refresh() }
      runCurrent()
      load = { listOf(row("new")) }
      state.refresh()
      old.complete(listOf(row("old")))
      pending.await()
      assertEquals(listOf(row("new")), state.items.value)
      assertTrue(state.restored.value)
    }

  @Test
  fun staleFailureCannotHideNewerSuccessfulSnapshot() =
    runTest {
      val state = state(this)
      state.awaitRecovery()
      val old = CompletableDeferred<List<ChatOutboxItem>>()
      load = { old.await() }
      val pending = async { state.refresh() }
      runCurrent()
      load = { listOf(row("new")) }
      state.refresh()
      old.completeExceptionally(IllegalStateException("old read failed"))
      pending.await()
      assertEquals(listOf(row("new")), state.items.value)
      assertTrue(state.restored.value)
    }

  @Test
  fun latestFailureRetainsRowsButRetiresAnOlderSuccessfulRead() =
    runTest {
      load = { listOf(row("initial")) }
      val state = state(this)
      state.awaitRecovery()
      val old = CompletableDeferred<List<ChatOutboxItem>>()
      load = { old.await() }
      val pending = async { state.refresh() }
      runCurrent()
      load = { error("new read failed") }
      state.refresh()
      old.complete(listOf(row("old")))
      pending.await()
      assertEquals(listOf(row("initial")), state.items.value)
      assertFalse(state.restored.value)
    }

  @Test
  fun clearRetiresPendingReadEvenWhenConnectionIdentityHasNotChanged() =
    runTest {
      val state = state(this)
      state.awaitRecovery()
      val old = CompletableDeferred<List<ChatOutboxItem>>()
      load = { old.await() }
      val pending = async { state.refresh() }
      runCurrent()
      state.clear()
      old.complete(listOf(row("old")))
      pending.await()
      assertTrue(state.items.value.isEmpty())
      assertFalse(state.restored.value)
    }

  @Test
  fun connectionGenerationChangeRejectsOldReadWithoutRequiringClear() =
    runTest {
      val state = state(this)
      state.awaitRecovery()
      val old = CompletableDeferred<List<ChatOutboxItem>>()
      load = { old.await() }
      val pending = async { state.refresh() }
      runCurrent()
      gateway = ChatCacheScope("gateway", 2)
      old.complete(listOf(row("old")))
      pending.await()
      assertTrue(state.items.value.isEmpty())
    }

  @Test
  fun pairingReplacementCannotPublishRetiredRows() =
    runTest {
      val state = state(this)
      state.awaitRecovery()
      val old = CompletableDeferred<List<ChatOutboxItem>>()
      load = { old.await() }
      val pending = async { state.refresh() }
      runCurrent()
      gateway = ChatCacheScope("other", 2)
      state.clear()
      load = { listOf(row(it)) }
      state.refresh()
      old.complete(listOf(row("gateway")))
      pending.await()
      assertEquals(listOf(row("other")), state.items.value)
      assertTrue(state.restored.value)
    }

  @Test
  fun refreshWithoutConnectionClearsRowsAndRetiresPendingRead() =
    runTest {
      load = { listOf(row("initial")) }
      val state = state(this)
      state.awaitRecovery()
      val old = CompletableDeferred<List<ChatOutboxItem>>()
      load = { old.await() }
      val pending = async { state.refresh() }
      runCurrent()
      gateway = null
      state.refresh()
      old.complete(listOf(row("old")))
      pending.await()
      assertTrue(state.items.value.isEmpty())
      assertFalse(state.restored.value)
    }

  @Test
  fun readCancellationPropagatesWithoutChangingCurrentPresentation() =
    runTest {
      load = { listOf(row("initial")) }
      val state = state(this)
      state.awaitRecovery()
      load = { throw CancellationException("read retired") }
      val pending = async { state.refresh() }
      try {
        pending.await()
        error("Expected cancellation")
      } catch (_: CancellationException) {
        assertTrue(pending.isCancelled)
      }
      assertEquals(listOf(row("initial")), state.items.value)
      assertTrue(state.restored.value)
    }
}
