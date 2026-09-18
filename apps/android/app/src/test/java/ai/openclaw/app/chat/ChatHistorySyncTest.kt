package ai.openclaw.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistorySyncTest {
  @Test
  fun coldBootstrapPublishesCacheThenCanonicalHistoryBeforeHealth() =
    runTest {
      val h = Harness(this)
      h.cachedRows = { listOf(message("cached")) }
      val arrived = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      h.respond = {
        arrived.complete(Unit)
        release.await()
        historyJson("live")
      }
      val job = async { h.bootstrap() }
      arrived.await()
      assertEquals(listOf("cached"), h.texts())
      assertTrue(h.transcript.fromCache.value)
      release.complete(Unit)
      job.await()
      assertEquals(listOf("live"), h.texts())
      assertFalse(h.transcript.fromCache.value)
      assertTrue(h.trace.indexOf("published") < h.trace.indexOf("proof"))
      assertTrue(h.trace.indexOf("proof") < h.trace.indexOf("health"))
      assertEquals(listOf(true), h.healthPolls)
      assertTrue(h.errors.isEmpty())
    }

  @Test
  fun lateCacheCannotReplaceAnAlreadyPublishedLiveEmptyTranscript() =
    runTest {
      val h = Harness(this)
      val arrived = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      h.cachedRows = {
        arrived.complete(Unit)
        release.await()
        listOf(message("stale"))
      }
      h.respond = { """{"sessionKey":"main","sessionId":"session","messages":[]}""" }
      val cold = async { h.bootstrap() }
      arrived.await()
      assertTrue(h.fetch() is ChatHistoryRefreshResult.Applied)
      release.complete(Unit)
      cold.await()
      assertTrue(h.texts().isEmpty())
      assertFalse(h.transcript.fromCache.value)
    }

  @Test
  fun supersededHistoryCannotPublishSessionModelOrDurableProof() =
    runTest {
      val h = Harness(this)
      val arrived = CompletableDeferred<Unit>()
      val response = CompletableDeferred<String>()
      h.respond = {
        arrived.complete(Unit)
        response.await()
      }
      val read = async { h.fetch() }
      arrived.await()
      h.select("agent:bob:other")
      response.complete(historyJson("stale"))
      assertEquals(ChatHistoryRefreshResult.Superseded, read.await())
      assertTrue(h.trace.none { it in listOf("info", "published", "proof") })
      assertTrue(h.texts().isEmpty())
    }

  @Test
  fun replacedConnectionCannotCompleteOldHistoryPublication() =
    runTest {
      val h = Harness(this)
      val response = CompletableDeferred<String>()
      h.respond = { response.await() }
      val read = async { h.fetch() }
      runCurrent()
      h.gateway = h.gateway.copy(connectionGeneration = 2)
      response.complete(historyJson("stale"))
      assertEquals(ChatHistoryRefreshResult.Superseded, read.await())
      assertTrue(h.proofs.isEmpty())
      assertTrue(h.texts().isEmpty())
    }

  @Test
  fun staleDefaultOwnerReadCannotReplaceAProvenNewOwner() =
    runTest {
      val h = Harness(this)
      val response = CompletableDeferred<String>()
      h.respond = { response.await() }
      val read = async { h.fetch() }
      runCurrent()
      h.defaultAgent = "bob"
      h.defaultRevision++
      response.complete(historyJson("alice"))
      assertEquals(ChatHistoryRefreshResult.Superseded, read.await())
      assertTrue(h.proofs.isEmpty())
    }

  @Test
  fun unavailableOwnerFinishesLoadingWithoutInventingAnEmptyHistory() =
    runTest {
      val h = Harness(this, initialAgent = null)
      h.transcript.nextLoad(markLoading = true)
      h.bootstrap()
      assertFalse(h.transcript.loading.value)
      assertTrue(h.requests.isEmpty())
      assertTrue(h.proofs.isEmpty())
      assertTrue(h.healthPolls.isEmpty())
    }

  @Test
  fun cachedOwnerRestorationCannotOverwriteANewerHello() =
    runTest {
      val h = Harness(this, initialAgent = null)
      val arrived = CompletableDeferred<Unit>()
      val release = CompletableDeferred<String?>()
      h.persistedDefault = {
        arrived.complete(Unit)
        release.await()
      }
      val cold = async { h.bootstrap() }
      arrived.await()
      h.defaultAgent = "bob"
      h.defaultRevision++
      release.complete("alice")
      cold.await()
      assertTrue(h.restoredDefaults.isEmpty())
      assertEquals("bob", h.requests.single().agentId)
    }

  @Test
  fun persistedDefaultCanRestoreOfflineRoutingBeforeHistory() =
    runTest {
      val h = Harness(this, initialAgent = null)
      h.persistedDefault = { "alice" }
      h.bootstrap()
      assertEquals(listOf("alice"), h.restoredDefaults)
      assertEquals("alice", h.requests.single().agentId)
      assertEquals(listOf("live"), h.texts())
    }

  @Test
  fun newRunAdmittedDuringHistoryReadKeepsItsOptimisticOwnership() =
    runTest {
      val h = Harness(this)
      val response = CompletableDeferred<String>()
      h.respond = { response.await() }
      val read = async { h.fetch() }
      runCurrent()
      h.pending.record(ChatPendingSend(h.owner(), "local", message("optimistic", "local:user")))
      h.pending.projection("local")?.let(h.pending::project)
      response.complete(historyJson("live"))
      assertTrue(read.await() is ChatHistoryRefreshResult.Applied)
      assertEquals(listOf("live", "optimistic"), h.texts())
      assertTrue(h.pending.isLocallyOwned("local"))
      assertTrue(h.pending.hasReply("local"))
    }

  @Test
  fun draftReconnectHealsHealthWithoutCreatingASessionOrReadingHistory() =
    runTest {
      val h = Harness(this)
      val draft = h.drafts.newDraft("gateway", "alice")
      h.select(draft.intent.target.key)
      h.healthy = false
      h.sync.disconnected()
      h.sync.refreshForRecovery(forceHealth = true, completesReconnectRecovery = true)
      runCurrent()
      assertFalse(h.sync.recovering)
      assertTrue(h.healthy)
      assertTrue(h.requests.isEmpty())
      assertTrue(h.proofs.isEmpty())
      assertTrue(h.drafts.find("gateway", draft.intent.target.key)?.isLocal == true)
    }

  @Test
  fun failedReconnectHealthKeepsRecoveryPendingForALaterRead() =
    runTest {
      val h = Harness(this)
      h.pollHealth = { h.healthy = false }
      h.sync.disconnected()
      h.sync.refreshForRecovery(forceHealth = true, completesReconnectRecovery = true)
      runCurrent()
      assertTrue(h.sync.recovering)
      assertEquals(1, h.healthPolls.size)
      h.pollHealth = { h.healthy = true }
      h.sync.refreshForRecovery(forceHealth = true, completesReconnectRecovery = true)
      runCurrent()
      assertFalse(h.sync.recovering)
      assertEquals(2, h.healthPolls.size)
    }

  @Test
  fun retiredRecoveryCannotBeCompletedByItsSuspendedHealthRead() =
    runTest {
      val h = Harness(this)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      h.pollHealth = {
        entered.complete(Unit)
        release.await()
        h.healthy = true
      }
      h.sync.disconnected()
      h.sync.refreshForRecovery(forceHealth = true, completesReconnectRecovery = true)
      entered.await()
      h.sync.retireRecovery()
      h.sync.disconnected()
      release.complete(Unit)
      runCurrent()
      assertTrue(h.sync.recovering)
    }

  @Test
  fun bestEffortFailureReturnsEvidenceWithoutPublishingAnError() =
    runTest {
      val h = Harness(this)
      h.respond = { error("offline") }
      assertEquals(ChatHistoryRefreshResult.Failed, h.sync.refreshSnapshot("main", h.transcript.generation, emptySet()))
      assertTrue(h.errors.isEmpty())
      assertTrue(h.proofs.isEmpty())
    }

  @Test
  fun bootstrapFailurePublishesOnlyItsCapturedGeneration() =
    runTest {
      val h = Harness(this)
      h.respond = { error("offline") }
      h.bootstrap()
      assertEquals(listOf(h.transcript.generation to "offline"), h.errors)
      assertFalse(h.transcript.loading.value)
      assertTrue(h.healthPolls.isEmpty())
    }

  @Test
  fun cancelledBootstrapDoesNotPublishFailureOrScheduleRecovery() =
    runTest {
      val h = Harness(this)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<String>()
      h.respond = {
        entered.complete(Unit)
        release.await()
      }
      val work = async { h.sync.bootstrap("main", h.transcript.generation, true, false, setOf("local")) }
      entered.await()
      work.cancelAndJoin()
      advanceTimeBy(1_000)
      runCurrent()
      assertTrue(h.errors.isEmpty())
      assertEquals(0, h.pendingReads)
      assertTrue(h.proofs.isEmpty())
    }

  @Test
  fun cancelledCacheReadCannotFallThroughToLiveHistory() =
    runTest {
      val h = Harness(this)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<List<ChatMessage>>()
      h.cachedRows = {
        entered.complete(Unit)
        release.await()
      }
      val work = async { h.bootstrap() }
      entered.await()
      work.cancelAndJoin()
      assertTrue(h.requests.isEmpty())
      assertTrue(h.proofs.isEmpty())
      assertTrue(h.errors.isEmpty())
    }

  private class Harness(
    val testScope: TestScope,
    initialAgent: String? = "alice",
  ) {
    val lock = Any()
    val publicationMutex = Mutex()
    var gateway = ChatCacheScope("gateway", 1)
    var defaultAgent: String? = initialAgent
    var defaultRevision = 0L
    var healthy = true
    val trace = mutableListOf<String>()
    val proofs = mutableListOf<Pair<ChatCacheScope?, String>>()
    val errors = mutableListOf<Pair<Long, String?>>()
    val healthPolls = mutableListOf<Boolean>()
    val requests = mutableListOf<ChatHistoryRequest>()
    val restoredDefaults = mutableListOf<String>()
    var pendingReads = 0
    var respond: suspend (ChatHistoryRequest) -> String = { historyJson("live", it.sessionKey) }
    var cachedRows: suspend () -> List<ChatMessage> = { emptyList() }
    var persistedDefault: suspend () -> String? = { null }
    var pollHealth: suspend () -> Unit = { healthy = true }
    val drafts = ChatDraftController()
    val selection = ChatSessionSelection(lock, { gateway }, { defaultAgent }, { defaultRevision })
    val cache =
      object : ChatTranscriptCache {
        override suspend fun loadLastDefaultAgentId(gatewayId: String) = persistedDefault()

        override suspend fun saveLastDefaultAgentId(
          gatewayId: String,
          agentId: String,
        ) = Unit

        override suspend fun loadSessions(
          gatewayId: String,
          agentId: String,
        ) = emptyList<ChatSessionEntry>()

        override suspend fun loadTranscript(
          gatewayId: String,
          agentId: String,
          sessionKey: String,
        ): List<ChatMessage> {
          assertFalse(Thread.holdsLock(lock))
          trace += "cache"
          return cachedRows()
        }

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
        ) = Unit

        override suspend fun deleteSession(
          gatewayId: String,
          agentId: String,
          sessionKey: String,
        ) = Unit

        override suspend fun clearGateway(gatewayId: String) = Unit
      }
    val transcript =
      ChatTranscript(
        testScope.backgroundScope,
        lock,
        selection,
        { gateway },
        { defaultRevision },
        ChatHistoryCodec(Json, ChatSessionCatalogCodec(Json)),
        awaitSessionReadiness = { _, _ -> },
        requestHistory = {
          assertFalse(Thread.holdsLock(lock))
          requests += it
          trace += "live"
          respond(it)
        },
        cache = cache,
        cacheMutationMutex = Mutex(),
      )
    val settings =
      ChatSessionSettings(
        testScope.backgroundScope,
        Json,
        lock,
        drafts,
        { selection.key.value },
        currentKey = { ChatSessionSettingsKey(gateway, it, selection.resolveOwner(it)) },
        readMetadata = { null },
        publishMetadata = { _, _ -> },
        captureRequestLease = { error("Unexpected settings mutation") },
        publishError = {},
        recordModelRecent = {},
        onLaneDrained = {},
      )
    val catalog = ChatSessionCatalog(lock, selection, settings, ChatSessionCatalogCodec(Json), { _, _ -> """{"sessions":[]}""" }, { _, _, _, _ -> }, {})
    val pending =
      ChatPendingSends(
        testScope.backgroundScope,
        lock,
        transcript,
        ChatRunState(),
        context = { ChatPendingSendContext(gateway, owner(), selection.key.value, healthy, emptySet()) },
        refreshHistory = { _, _, _ ->
          pendingReads++
          ChatPendingHistoryResult.Unavailable
        },
        parkUnconfirmed = {},
        publish = {},
      )
    val outboxState = ChatOutboxState(testScope.backgroundScope, null, lock, { gateway })
    val sync =
      ChatHistorySync(
        scope = testScope.backgroundScope,
        gatewayScopeApplyLock = lock,
        historyPublicationMutex = publicationMutex,
        sessionSelection = selection,
        transcript = transcript,
        sessionSettings = settings,
        sessionCatalog = catalog,
        pendingSends = pending,
        outboxState = outboxState,
        drafts = drafts,
        transcriptCache = cache,
        context = { ChatHistoryContext(gateway, defaultAgent, defaultRevision, healthy) },
        pollHealthIfNeeded = { force ->
          assertFalse(Thread.holdsLock(lock))
          trace += "health"
          healthPolls += force
          pollHealth()
        },
        publishSessionInfo = {
          assertTrue(Thread.holdsLock(lock))
          trace += "info"
        },
        onHistoryPublished = { _, _ ->
          assertTrue(Thread.holdsLock(lock))
          trace += "published"
        },
        onHistoryError = { generation, message -> errors += generation to message },
        onOfflineDefaultAgentRestored = restoredDefaults::add,
        confirmDelivery = { gateway, _, agent ->
          assertFalse(Thread.holdsLock(lock))
          trace += "proof"
          proofs += gateway to agent
        },
      )

    fun owner() = ChatComposerOwner("gateway", selection.resolveOwner(selection.key.value) ?: "alice", selection.key.value)

    fun texts() = transcript.messages.value.map { it.content.single().text }

    fun select(key: String) =
      synchronized(lock) {
        transcript.beginSelection(true, true)
        selection.select(key, null)
      }

    suspend fun fetch() = sync.fetchAndApply(selection.key.value, transcript.generation, true)

    suspend fun bootstrap() = sync.bootstrap(selection.key.value, transcript.generation, true, false)
  }

  companion object {
    private fun message(
      id: String,
      key: String? = null,
    ) = ChatMessage(id, "user", listOf(ChatMessageContent(type = "text", text = id)), timestampMs = 1, idempotencyKey = key)

    private fun historyJson(
      id: String,
      key: String = "main",
    ) = """{"sessionKey":"$key","sessionId":"session","messages":[{"id":"$id","role":"user","timestamp":0,"content":[{"type":"text","text":"$id"}]}]}"""
  }
}
