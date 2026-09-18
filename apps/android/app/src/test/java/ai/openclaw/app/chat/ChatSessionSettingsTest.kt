package ai.openclaw.app.chat

import ai.openclaw.app.ai.AiModel
import ai.openclaw.app.ai.AiThinkingLevel
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.i18n.NativeText
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionSettingsTest {
  @Test
  fun catalogSuppliesModelSpecificThinkingLevelsAndDefault() =
    runTest {
      val h = Harness(this)
      h.owner.applyHistoryModel(h.owner.modelRevision, "provider/model")

      h.owner.applyModelCatalog(
        listOf(
          model("model", listOf("off", "high", "ultra"), "high"),
        ),
      )

      assertEquals(
        listOf("off", "high", "ultra"),
        h.owner.thinkingLevelSelection.value.options
          .map { it.id },
      )
      assertEquals("high", h.owner.thinkingLevel.value)
    }

  @Test
  fun catalogThinkingOptionsFollowModelChangesAndResetForDefaultRouting() =
    runTest {
      val h = Harness(this)
      val catalog =
        listOf(
          model("first", listOf("off", "high"), "high"),
          model("second", listOf("off", "ultra"), "ultra"),
        )

      h.owner.applyHistoryModel(h.owner.modelRevision, "provider/first")
      h.owner.applyModelCatalog(catalog)
      assertEquals(
        listOf("off", "high"),
        h.owner.thinkingLevelSelection.value.options
          .map { it.id },
      )

      h.owner.applyHistoryModel(h.owner.modelRevision, "provider/second")
      h.owner.applyModelCatalog(catalog)
      assertEquals(
        listOf("off", "ultra"),
        h.owner.thinkingLevelSelection.value.options
          .map { it.id },
      )
      assertEquals("ultra", h.owner.thinkingLevel.value)

      h.owner.applyHistoryModel(h.owner.modelRevision, null)
      h.owner.applyModelCatalog(catalog)
      assertEquals(defaultChatThinkingLevelSelection, h.owner.thinkingLevelSelection.value)
      assertEquals("off", h.owner.thinkingLevel.value)
    }

  @Test
  fun draftChoicesStayLocalAndFreezeWithCreationIntent() =
    runTest {
      val h = Harness(this)
      val draft = h.drafts.newDraft("gateway", "agent")
      h.select(draft.intent.target.key)
      assertTrue(h.owner.setSessionModelAwait(h.selected, " provider/model "))
      h.owner.setThinkingLevel(" HIGH ")
      assertEquals("provider/model", h.owner.selectedModelRef.value)
      assertEquals("high", h.owner.thinkingLevel.value)
      val intent = h.drafts.beginCreation(draft.intent.target)!!
      assertEquals("provider/model", intent.modelRef)
      assertEquals("high", intent.thinkingLevel)
      assertFalse(h.owner.setSessionModelAwait(h.selected, "other/model"))
      h.owner.setThinkingLevel("low")
      assertEquals("high", h.owner.thinkingLevel.value)
      assertTrue(h.captures.isEmpty())
      assertTrue(h.requests.isEmpty())
    }

  @Test
  fun modelUsesOneCanonicalSessionPatch() =
    runTest {
      val h = Harness(this)
      h.rows["main"] = ChatSessionSettingsMetadata()
      h.respond = { _, _ -> """{"resolved":{"modelProvider":"openai","model":"gpt"}}""" }

      assertTrue(h.owner.setSessionModelAwait("main", "openai/gpt"))

      assertEquals(1, h.requests.size)
      assertEquals(
        "openai/gpt",
        h.requests
          .single()
          .second["model"]
          ?.settingsString(),
      )
      assertEquals("openai/gpt", h.owner.selectedModelRef.value)
      assertEquals(listOf("openai/gpt"), h.recents)
      assertEquals("gpt", h.rows.getValue("main").model)
    }

  @Test
  fun modelAndThinkingShareAdmissionAndRollbackToAcceptedModelMetadata() =
    runTest {
      val h = Harness(this)
      val model = CompletableDeferred<String>()
      val thinking = CompletableDeferred<String>()
      h.respond = { _, params -> if ("model" in params) model.await() else thinking.await() }
      h.owner.setSessionModel("main", "provider/model")
      h.owner.setThinkingLevel("high")
      val waitingSend = async(start = CoroutineStart.UNDISPATCHED) { h.owner.awaitPending("main") }
      assertEquals(1, h.requests.size)
      assertFalse(waitingSend.isCompleted)
      assertEquals("high", h.owner.thinkingLevel.value)
      model.complete(resolved("low", "off", "low", "high"))
      runCurrent()
      assertEquals(2, h.requests.size)
      assertEquals("high", h.owner.thinkingLevel.value)
      thinking.completeExceptionally(IllegalStateException("rejected"))
      advanceUntilIdle()
      assertFalse(waitingSend.await())
      assertEquals("low", h.owner.thinkingLevel.value)
      assertEquals(
        listOf("off", "low", "high"),
        h.owner.thinkingLevelSelection.value.options
          .map { it.id },
      )
      assertEquals(0, h.drains)
    }

  @Test
  fun olderResponseCannotReplaceRepeatedLatestThinkingIntent() =
    runTest {
      val h = Harness(this)
      val replies = List(3) { CompletableDeferred<String>() }
      var call = 0
      h.respond = { _, _ -> replies[call++].await() }
      h.owner.setThinkingLevel("high")
      h.owner.setThinkingLevel("low")
      h.owner.setThinkingLevel("high")
      replies[0].complete(resolved("low", "off", "low", "high"))
      runCurrent()
      assertEquals("high", h.owner.thinkingLevel.value)
      replies[1].complete(resolved("medium", "off", "medium", "high"))
      runCurrent()
      assertEquals("high", h.owner.thinkingLevel.value)
      replies[2].completeExceptionally(IllegalStateException("latest rejected"))
      advanceUntilIdle()
      assertEquals("medium", h.owner.thinkingLevel.value)
      assertEquals(
        listOf("off", "medium", "high"),
        h.owner.thinkingLevelSelection.value.options
          .map { it.id },
      )
    }

  @Test
  fun queuedMutationKeepsCapturedConnectionAndCannotPublishIntoReplacement() =
    runTest {
      val h = Harness(this)
      val oldScope = h.gateway
      val oldReply = CompletableDeferred<String>()
      h.respond = { connection, _ -> if (connection == oldScope) oldReply.await() else resolved("medium", "off", "medium") }
      h.owner.setSessionModel("main", "provider/old")
      h.owner.setSessionModel("main", "provider/queued")
      val oldWaiter = async(start = CoroutineStart.UNDISPATCHED) { h.owner.awaitPending(h.key("main")) }
      h.gateway = oldScope.copy(connectionGeneration = 2)
      assertTrue(h.owner.setSessionModelAwait("main", "provider/new"))
      val newRevision = h.owner.modelRevision
      oldReply.complete(resolved("high", "off", "high"))
      advanceUntilIdle()
      assertFalse(oldWaiter.await())
      assertEquals(listOf(oldScope, oldScope, h.gateway), h.captures)
      assertEquals(listOf(oldScope, h.gateway), h.requests.map { it.first })
      assertEquals("provider/new", h.owner.selectedModelRef.value)
      assertEquals("medium", h.owner.thinkingLevel.value)
      assertEquals(newRevision, h.owner.modelRevision)
    }

  @Test
  fun staleFailureDoesNotReplaceNewConnectionErrorOrPicker() =
    runTest {
      val h = Harness(this)
      val oldScope = h.gateway
      val oldReply = CompletableDeferred<String>()
      h.respond = { connection, _ -> if (connection == oldScope) oldReply.await() else error("new failure") }
      h.owner.setThinkingLevel("high")
      h.gateway = oldScope.copy(connectionGeneration = 2)
      h.owner.applyMetadata(ChatSessionSettingsMetadata(thinkingLevel = "low"))
      assertFalse(h.owner.setSessionModelAwait("main", "provider/new"))
      val errorCount = h.errors.size
      oldReply.completeExceptionally(IllegalStateException("old failure"))
      advanceUntilIdle()
      assertEquals(errorCount, h.errors.size)
      assertEquals("low", h.owner.thinkingLevel.value)
    }

  @Test
  fun refreshFenceRetainsRevisionUntilLastReaderAndDoesNotWaitOnAnotherConnection() =
    runTest {
      val h = Harness(this)
      val oldScope = h.gateway
      h.owner.beginRefresh(oldScope)
      h.owner.beginRefresh(oldScope)
      val revision = h.owner.revision(oldScope)
      assertTrue(h.owner.matchesSnapshot(oldScope, revision))
      val reply = CompletableDeferred<String>()
      h.respond = { _, _ -> reply.await() }
      h.owner.setSessionModel("main", "provider/model")
      assertFalse(h.owner.matchesSnapshot(oldScope, revision))
      val sameScope = async(start = CoroutineStart.UNDISPATCHED) { h.owner.awaitPending(oldScope) }
      val otherScope = async(start = CoroutineStart.UNDISPATCHED) { h.owner.awaitPending(oldScope.copy(connectionGeneration = 2)) }
      assertFalse(sameScope.isCompleted)
      assertTrue(otherScope.isCompleted)
      reply.complete("{}")
      advanceUntilIdle()
      assertTrue(sameScope.isCompleted)
      assertFalse(h.owner.matchesSnapshot(oldScope, revision))
      h.owner.endRefresh(oldScope)
      assertFalse(h.owner.matchesSnapshot(oldScope, revision))
      h.owner.endRefresh(oldScope)
      assertEquals(0L, h.owner.revision(oldScope))
      assertEquals(1, h.drains)
    }

  @Test
  fun cancelledMutationSettlesItsWaitersWithoutClaimingSuccess() =
    runTest {
      val h = Harness(this)
      h.respond = { _, _ -> CompletableDeferred<String>().await() }
      val mutation = async(start = CoroutineStart.UNDISPATCHED) { h.owner.setSessionModelAwait("main", "provider/model") }
      val waiter = async(start = CoroutineStart.UNDISPATCHED) { h.owner.awaitPending("main") }
      mutation.cancel()
      advanceUntilIdle()
      assertFalse(waiter.await())
      assertTrue(h.owner.awaitPending("main"))
      assertNull(h.owner.selectedModelRef.value)
      assertEquals(0, h.drains)
    }

  @Test
  fun cancelledQueuedMutationCannotReleaseItsStillRunningPredecessor() =
    runTest {
      val h = Harness(this)
      val reply = CompletableDeferred<String>()
      h.respond = { _, _ -> reply.await() }
      h.owner.setSessionModel("main", "provider/first")
      val queued = async(start = CoroutineStart.UNDISPATCHED) { h.owner.setSessionModelAwait("main", "provider/cancelled") }
      queued.cancel()
      runCurrent()
      val waiter = async(start = CoroutineStart.UNDISPATCHED) { h.owner.awaitPending("main") }
      assertFalse(waiter.isCompleted)
      assertEquals(1, h.requests.size)
      reply.complete("{}")
      advanceUntilIdle()
      assertFalse(waiter.await())
      assertTrue(h.owner.awaitPending("main"))
      assertEquals("provider/first", h.owner.selectedModelRef.value)
    }

  @Test
  fun successorBehindCancelledQueuedWorkStillWaitsForPredecessor() =
    runTest {
      val h = Harness(this)
      val reply = CompletableDeferred<String>()
      h.respond = { _, params ->
        if (params["model"].settingsString() == "provider/first") reply.await() else "{}"
      }
      h.owner.setSessionModel("main", "provider/first")
      val cancelled = async(start = CoroutineStart.UNDISPATCHED) { h.owner.setSessionModelAwait("main", "provider/cancelled") }
      cancelled.cancel()
      runCurrent()
      h.owner.setSessionModel("main", "provider/last")
      val waiter = async(start = CoroutineStart.UNDISPATCHED) { h.owner.awaitPending("main") }
      assertEquals(1, h.requests.size)
      assertFalse(waiter.isCompleted)
      reply.complete("{}")
      advanceUntilIdle()
      assertTrue(waiter.await())
      assertEquals(listOf("provider/first", "provider/last"), h.requests.map { it.second["model"].settingsString() })
      assertEquals("provider/last", h.owner.selectedModelRef.value)
      assertEquals(1, h.drains)
    }

  @Test
  fun backgroundAcceptedSettingsDoNotReplaceSelectedSession() =
    runTest {
      val h = Harness(this)
      h.rows["background"] = ChatSessionSettingsMetadata(thinkingLevel = "off")
      h.owner.applyMetadata(ChatSessionSettingsMetadata(thinkingLevel = "low"))
      h.respond = { _, _ -> resolved("high", "off", "high") }
      assertTrue(h.owner.setSessionModelAwait("background", "provider/model"))
      assertEquals("high", h.rows["background"]?.thinkingLevel)
      assertEquals("low", h.owner.thinkingLevel.value)
      assertNull(h.owner.selectedModelRef.value)
    }

  @Test
  fun staleHistoryCannotReplaceAcceptedModelAndEffectiveThinkingNeedNotBeAnOption() =
    runTest {
      val h = Harness(this)
      val staleRevision = h.owner.modelRevision
      assertTrue(h.owner.setSessionModelAwait("main", "provider/current"))
      h.owner.applyHistoryModel(staleRevision, "provider/stale")
      assertEquals("provider/current", h.owner.selectedModelRef.value)
      h.owner.applyMetadata(ChatSessionSettingsMetadata(thinkingLevel = "ultra", thinkingLevels = listOf(ChatThinkingLevelOption(" HIGH ", " High "))))
      assertEquals("ultra", h.owner.thinkingLevel.value)
      assertEquals(listOf(ChatThinkingLevelOption("high", "High")), h.owner.thinkingLevelSelection.value.options)
      h.owner.setThinkingLevel("not-advertised")
      assertEquals("ultra", h.owner.thinkingLevel.value)
    }

  @Test
  fun settingsPublicationPreservesUnrelatedSessionFields() {
    val row = ChatSessionEntry(key = "main", updatedAtMs = 99, unread = true, activeRunIds = listOf("run"), model = "old")
    val applied = ChatSessionSettingsMetadata(model = "new", thinkingLevel = "high").applyTo(row)
    assertEquals("new", applied.model)
    assertEquals("high", applied.thinkingLevel)
    assertEquals(row.copy(model = "new", thinkingLevel = "high"), applied)
  }

  private class Harness(
    scope: TestScope,
  ) {
    var gateway = ChatCacheScope("gateway", 1)
    var selected = "main"
    val drafts = ChatDraftController()
    val rows = mutableMapOf<String, ChatSessionSettingsMetadata>()
    val captures = mutableListOf<ChatCacheScope?>()
    val requests = mutableListOf<Pair<ChatCacheScope?, JsonObject>>()
    val errors = mutableListOf<NativeText?>()
    val recents = mutableListOf<String>()
    var drains = 0
    var respond: suspend (ChatCacheScope?, JsonObject) -> String = { _, _ -> "{}" }

    fun key(key: String) = ChatSessionSettingsKey(gateway, key, "agent")

    val owner =
      ChatSessionSettings(
        scope = scope,
        json = Json,
        publicationLock = Any(),
        drafts = drafts,
        selectedSessionKey = { selected },
        currentKey = ::key,
        readMetadata = rows::get,
        publishMetadata = { key, metadata -> if (rows.containsKey(key.sessionKey)) rows[key.sessionKey] = metadata },
        captureRequestLease = { connection ->
          captures += connection
          GatewaySession.RequestLease(connection?.gatewayId.orEmpty()) { method, params, _, withEnqueue ->
            assertEquals("sessions.patch", method)
            withEnqueue {}
            val payload = Json.parseToJsonElement(params!!) as JsonObject
            requests += connection to payload
            respond(connection, payload)
          }
        },
        publishError = errors::add,
        recordModelRecent = recents::add,
        onLaneDrained = { drains++ },
      )

    fun select(key: String) {
      selected = key
      owner.selectSession(rows[key])
    }
  }

  private fun resolved(
    level: String,
    vararg options: String,
  ): String = """{"resolved":{"thinkingLevel":"$level","thinkingLevels":[${options.joinToString { """{"id":"$it","label":"$it"}""" }}]}}"""

  private fun model(
    id: String,
    levels: List<String>,
    default: String,
  ): AiModel =
    AiModel(
      id = id,
      name = id,
      provider = "provider",
      alias = null,
      tags = emptyList(),
      available = true,
      unavailableReason = null,
      unavailableUntilEpochMs = null,
      contextTokens = null,
      supportsReasoning = true,
      supportsTools = true,
      apiKeySupported = true,
      thinkingLevels = levels.map { AiThinkingLevel(it, it) },
      thinkingDefault = default,
    )
}
