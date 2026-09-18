package ai.openclaw.app.chat

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionCatalogTest {
  @Test
  fun newerRefreshWinsAndOnlyPublishedSnapshotIsPersisted() =
    runTest {
      val h = Harness(this)
      val replies = List(2) { CompletableDeferred<String>() }
      var call = 0
      h.respond = { _, _ -> replies[call++].await() }
      val first = async(start = CoroutineStart.UNDISPATCHED) { h.catalog.refresh(20) }
      val second = async(start = CoroutineStart.UNDISPATCHED) { h.catalog.refresh(20) }
      replies[1].complete(list("new"))
      runCurrent()
      replies[0].complete(list("old"))
      advanceUntilIdle()
      assertFalse(first.await())
      assertTrue(second.await())
      assertEquals(
        listOf("new"),
        h.catalog.entries.value
          .map { it.key },
      )
      assertEquals("alice", h.catalog.entry("new")?.ownerAgentId)
      assertEquals(listOf(listOf("new")), h.persisted)
      assertEquals(1, h.publications)
    }

  @Test
  fun clearInvalidatesInFlightListEvenOnSameConnectionAndAgent() =
    runTest {
      val h = Harness(this)
      val reply = CompletableDeferred<String>()
      h.respond = { _, _ -> reply.await() }
      val refresh = async(start = CoroutineStart.UNDISPATCHED) { h.catalog.refresh(20, archived = true) }
      h.catalog.clear()
      reply.complete(list("old"))
      advanceUntilIdle()
      assertFalse(refresh.await())
      assertTrue(
        h.catalog.entries.value
          .isEmpty(),
      )
      assertFalse(h.catalog.isArchived)
      assertTrue(h.persisted.isEmpty())
    }

  @Test
  fun oldConnectionResponseCannotPublishOrPersist() =
    runTest {
      val h = Harness(this)
      val reply = CompletableDeferred<String>()
      h.respond = { _, _ -> reply.await() }
      val refresh = async(start = CoroutineStart.UNDISPATCHED) { h.catalog.refresh(20) }
      h.gateway = h.gateway.copy(connectionGeneration = 2)
      reply.complete(list("old"))
      advanceUntilIdle()
      assertFalse(refresh.await())
      assertTrue(
        h.catalog.entries.value
          .isEmpty(),
      )
      assertTrue(h.persisted.isEmpty())
    }

  @Test
  fun listOverlappingSettingsMutationRetriesInsteadOfRestoringStalePicker() =
    runTest {
      val h = Harness(this)
      val staleList = CompletableDeferred<String>()
      var lists = 0
      h.respond = { method, _ ->
        when {
          method == "sessions.patch" -> """{"resolved":{"thinkingLevel":"high"}}"""
          ++lists == 1 -> staleList.await()
          else -> """{"sessions":[{"key":"main","thinkingLevel":"high"}]}"""
        }
      }
      val refresh = async(start = CoroutineStart.UNDISPATCHED) { h.catalog.refresh(20) }
      assertTrue(h.settings.setSessionModelAwait("main", "provider/model"))
      staleList.complete("""{"sessions":[{"key":"main","thinkingLevel":"off"}]}""")
      advanceUntilIdle()
      assertTrue(refresh.await())
      assertEquals(2, lists)
      assertEquals("high", h.settings.thinkingLevel.value)
      assertEquals("high", h.catalog.entry("main")?.thinkingLevel)
      assertEquals(1, h.publications)
    }

  @Test
  fun refreshWaitsForSettingsBeforeRequestingList() =
    runTest {
      val h = Harness(this)
      val patch = CompletableDeferred<String>()
      h.respond = { method, _ -> if (method == "sessions.patch") patch.await() else list("main") }
      h.settings.setSessionModel("main", "provider/model")
      val refresh = async(start = CoroutineStart.UNDISPATCHED) { h.catalog.refresh(20) }
      assertEquals(listOf("sessions.patch"), h.requests.map { it.first })
      patch.complete("{}")
      advanceUntilIdle()
      assertTrue(refresh.await())
      assertEquals(listOf("sessions.patch", "sessions.list"), h.requests.map { it.first })
    }

  @Test
  fun searchUsesCapturedAgentAndDoesNotReplaceTheLiveList() =
    runTest {
      val h = Harness(this)
      h.catalog.upsert(row("active", "Original"))
      h.respond = { _, _ -> list("archived-result") }
      val result = h.catalog.search(" search ", archived = true)
      assertEquals("alice", result.single().ownerAgentId)
      assertEquals(
        "search",
        h.requests
          .single()
          .second["search"]
          .settingsString(),
      )
      assertEquals(
        "true",
        h.requests
          .single()
          .second["archived"]
          .settingsString(),
      )
      assertEquals(
        SESSION_LIST_FETCH_LIMIT.toString(),
        h.requests
          .single()
          .second["limit"]
          .settingsString(),
      )
      assertEquals(
        "true",
        h.requests
          .single()
          .second["includeDerivedTitles"]
          .settingsString(),
      )
      assertEquals(
        "active",
        h.catalog.entries.value
          .single()
          .key,
      )
      assertTrue(h.persisted.isEmpty())
    }

  @Test
  fun failedSearchFiltersActiveCacheButNeverSuppliesArchivedRows() =
    runTest {
      val h = Harness(this)
      h.catalog.upsert(row("one", "Research"))
      h.catalog.upsert(row("two", "Travel"))
      h.respond = { _, _ -> error("offline") }
      assertEquals(listOf("one"), h.catalog.search("research", false).map { it.key })
      assertTrue(h.catalog.search("research", true).isEmpty())
    }

  @Test
  fun refreshRequestsCanonicalDerivedTitles() =
    runTest {
      val h = Harness(this)
      h.respond = { _, _ -> list("main") }

      assertTrue(h.catalog.refresh(20))

      assertEquals(
        "true",
        h.requests
          .single()
          .second["includeDerivedTitles"]
          .settingsString(),
      )
    }

  @Test
  fun foreignSearchResultCannotBorrowTheNewAgentsCache() =
    runTest {
      val h = Harness(this)
      val reply = CompletableDeferred<String>()
      h.respond = { _, _ -> reply.await() }
      val search = async(start = CoroutineStart.UNDISPATCHED) { h.catalog.search(null, false) }
      h.selection.select("topic", "bob")
      h.catalog.clear()
      h.catalog.upsert(row("new-agent", "New"))
      reply.completeExceptionally(IllegalStateException("old failure"))
      advanceUntilIdle()
      assertTrue(search.await().isEmpty())
      assertEquals(
        "new-agent",
        h.catalog.entries.value
          .single()
          .key,
      )
    }

  @Test
  fun settingsAndPartialEventsPreserveUnrelatedFieldsAndHonorExplicitClears() =
    runTest {
      val h = Harness(this)
      h.catalog.upsert(row("main", "Title").copy(label = "old", unread = true, activeRunIds = listOf("run")))
      h.catalog.applySettings(ChatSessionSettingsKey(h.gateway, "main", "alice"), ChatSessionSettingsMetadata(model = "model"))
      val updated = h.catalog.upsert(row("main", null), clearedFields = setOf("label"))
      assertEquals("model", updated.model)
      assertEquals("Title", updated.displayName)
      assertTrue(updated.unread == true)
      assertNull(updated.label)
      h.catalog.applySettings(ChatSessionSettingsKey(h.gateway.copy(connectionGeneration = 0), "main", "alice"), ChatSessionSettingsMetadata(model = "stale"))
      assertEquals("model", h.catalog.entry("main")?.model)
    }

  @Test
  fun cacheSeedsOnlyAnEmptyListAndArchivedRefreshDoesNotOverwriteActiveCache() =
    runTest {
      val h = Harness(this)
      h.catalog.restoreCached(listOf(row("cached", "Cached")), h.gateway, "alice")
      h.catalog.restoreCached(listOf(row("ignored", "Ignored")), h.gateway, "alice")
      assertEquals(
        "cached",
        h.catalog.entries.value
          .single()
          .key,
      )
      assertEquals(
        "alice",
        h.catalog.entries.value
          .single()
          .ownerAgentId,
      )
      h.respond = { _, _ -> list("archived") }
      assertTrue(h.catalog.refresh(20, archived = true))
      assertTrue(h.catalog.isArchived)
      assertTrue(h.persisted.isEmpty())
      assertEquals(
        "archived",
        h.catalog.entries.value
          .single()
          .key,
      )
    }

  @Test
  fun removalAndCacheRestoreRequireTheExactCurrentOwner() =
    runTest {
      val h = Harness(this)
      val oldScope = h.gateway
      h.gateway = oldScope.copy(connectionGeneration = 2)
      h.catalog.restoreCached(listOf(row("old", "Old")), oldScope, "alice")
      h.catalog.restoreCached(listOf(row("foreign", "Foreign")), h.gateway, "bob")
      assertTrue(
        h.catalog.entries.value
          .isEmpty(),
      )
      h.catalog.restoreCached(listOf(row("main", "Current")), h.gateway, "alice")
      assertFalse(h.catalog.remove("main", oldScope, "alice"))
      assertFalse(h.catalog.remove("main", h.gateway, "bob"))
      assertEquals("Current", h.catalog.entry("main")?.displayName)
      assertTrue(h.catalog.remove("main", h.gateway, "alice"))
      assertTrue(
        h.catalog.entries.value
          .isEmpty(),
      )
    }

  @Test
  fun codecPreservesPresenceFlagsAndTruncationEvidence() {
    val codec = ChatSessionCatalogCodec(Json)
    val parsed = codec.parseList("""{"totalCount":3,"sessions":[{"key":" main ","color":null,"activeRunIds":[],"thinkingLevels":[{"id":" HIGH ","label":" High "},{"id":"high"}]}]}""")
    assertTrue(parsed.isTruncated)
    val row = parsed.sessions.single()
    assertEquals("main", row.key)
    assertTrue(row.hasColorMetadata)
    assertNull(row.color)
    assertTrue(row.hasActiveRunIdsMetadata)
    assertFalse(row.hasRunMetadata)
    assertEquals(listOf(ChatThinkingLevelOption("high", "High")), row.thinkingLevels)
  }

  private class Harness(
    scope: TestScope,
  ) {
    var gateway = ChatCacheScope("gateway", 1)
    private val lock = Any()
    val selection = ChatSessionSelection(lock, { gateway }, { "alice" }, { 0L })
    val requests = mutableListOf<Pair<String, JsonObject>>()
    val persisted = mutableListOf<List<String>>()
    var publications = 0
    var respond: suspend (String, JsonObject) -> String = { _, _ -> "{}" }

    private suspend fun request(
      method: String,
      payload: String?,
    ): String {
      val params = Json.parseToJsonElement(payload!!) as JsonObject
      requests += method to params
      return respond(method, params)
    }

    val settings: ChatSessionSettings =
      ChatSessionSettings(
        scope,
        Json,
        lock,
        ChatDraftController(),
        { selection.key.value },
        { ChatSessionSettingsKey(gateway, it, selection.resolveOwner(it)) },
        { catalog.entry(it)?.settingsMetadata() },
        { key, metadata -> catalog.applySettings(key, metadata) },
        { captured ->
          GatewaySession.RequestLease(captured?.gatewayId.orEmpty()) { method, params, _, enqueue ->
            enqueue {}
            request(method, params)
          }
        },
        {},
        {},
        {},
      )
    val catalog: ChatSessionCatalog =
      ChatSessionCatalog(
        lock,
        selection,
        settings,
        ChatSessionCatalogCodec(Json),
        ::request,
        { _, _, rows, _ -> persisted += rows.map { it.key } },
        { publications++ },
      )
  }

  private fun row(
    key: String,
    name: String?,
  ) = ChatSessionEntry(key = key, updatedAtMs = null, displayName = name)

  private fun list(key: String) = """{"sessions":[{"key":"$key"}]}"""
}
