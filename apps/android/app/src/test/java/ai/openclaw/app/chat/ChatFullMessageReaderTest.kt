package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatFullMessageReaderTest {
  @Test fun standaloneReaderLoadsCanonicalContentWithoutReplacingTranscript() =
    runTest {
      val h = Harness()
      val original = h.messages.value
      val read = h.prepare()
      assertEquals(ChatFullMessageState.Loading, read.state.value)
      read.execute()
      assertEquals(ChatFullMessageState.Loaded(listOf(ChatMessageContent(type = "text", text = "Full text"))), read.state.value)
      assertEquals(original, h.messages.value)
      assertEquals(1, h.enqueued)
      read.execute()
      assertEquals(1, h.enqueued)
    }

  @Test fun staleOwnerGenerationCatalogAndChangedPreviewCannotPrepare() {
    val h = Harness()
    assertNull(h.reader.feature.prepare(h.owner.copy(gatewayStableId = "other"), h.selection.generation.value, h.catalog, h.message))
    assertNull(h.reader.feature.prepare(h.owner, h.selection.generation.value - 1, h.catalog, h.message))
    assertNull(h.reader.feature.prepare(h.owner, h.selection.generation.value, h.catalog - 1, h.message))
    h.messages.value = listOf(h.message.copy(content = listOf(ChatMessageContent(type = "text", text = "Changed"))))
    assertNull(h.reader.feature.prepare(h.owner, h.selection.generation.value, h.catalog, h.message))
  }

  @Test fun retirementDuringTransportWaitNeverEnqueuesOldRead() =
    runTest {
      val h = Harness()
      val ready = CompletableDeferred<Unit>()
      h.beforeEnqueue = { ready.await() }
      val read = h.prepare()
      val job = async { read.execute() }
      runCurrent()
      h.selection.select("agent:main:other", "main")
      ready.complete(Unit)
      job.await()
      assertEquals(0, h.enqueued)
      assertEquals(ChatFullMessageState.Loading, read.state.value)
    }

  @Test fun physicalRetirementAfterResponseCannotPublishEvenWithSameLogicalOwner() =
    runTest {
      val h = Harness()
      val response = CompletableDeferred<String>()
      h.respond = { response.await() }
      val read = h.prepare()
      val job = async { read.execute() }
      runCurrent()
      assertEquals(1, h.enqueued)
      h.physicalGeneration++
      response.complete(h.validResponse)
      job.await()
      assertEquals(ChatFullMessageState.Loading, read.state.value)
    }

  @Test fun cancellationDoesNotPublishFailureOrStartAnotherRead() =
    runTest {
      val h = Harness()
      h.beforeEnqueue = { CompletableDeferred<Unit>().await() }
      val read = h.prepare()
      val job = async { read.execute() }
      runCurrent()
      job.cancel()
      runCurrent()
      assertTrue(job.isCancelled)
      assertEquals(0, h.enqueued)
      assertEquals(ChatFullMessageState.Loading, read.state.value)
    }

  @Test fun unsupportedAndMalformedRepliesDoNotInventFullText() =
    runTest {
      val h = Harness()
      h.supported = false
      val unavailable = h.prepare()
      unavailable.execute()
      assertEquals(ChatFullMessageState.Unavailable(ChatFullMessageUnavailable.GatewayUpdate), unavailable.state.value)
      assertEquals(0, h.enqueued)
      h.supported = true
      h.respond = { h.validResponse.replace("entry", "wrong-entry") }
      val mismatch = h.prepare()
      mismatch.execute()
      assertEquals(ChatFullMessageState.Failed, mismatch.state.value)
      h.respond = { """{"ok":false,"unavailableReason":"oversized"}""" }
      val oversized = h.prepare()
      oversized.execute()
      assertEquals(ChatFullMessageState.Unavailable(ChatFullMessageUnavailable.TooLarge), oversized.state.value)
    }

  private class Harness {
    val lock = Any()
    val gateway = ChatCacheScope("gateway", 1)
    var catalog = 1L
    var physicalGeneration = 1
    var supported = true
    val selection = ChatSessionSelection(lock, { gateway }, { "main" }, { 1L }).apply { select("agent:main:topic", "main") }
    val owner = ChatComposerOwner("gateway", "main", "agent:main:topic")
    val message = ChatMessage("display", "assistant", listOf(ChatMessageContent(type = "text", text = "Preview")), null, entryId = "entry", truncated = true)
    val messages = MutableStateFlow(listOf(message))
    val validResponse = """{"ok":true,"message":{"role":"assistant","content":[{"type":"text","text":"Full text"}],"__openclaw":{"id":"entry"}}}"""
    var enqueued = 0
    var beforeEnqueue: suspend () -> Unit = {}
    var respond: suspend () -> String = { validResponse }
    val reader =
      ChatFullMessageReader(
        publicationLock = lock,
        sessionSelection = selection,
        messages = messages,
        currentGatewayCatalogRevision = { catalog },
        captureRequestLease = {
          val physical = physicalGeneration
          GatewaySession.RequestLease("gateway", isCurrentImpl = { physical == physicalGeneration }) { method, params, _, enqueue ->
            check(method == "chat.message.get")
            check(params?.contains("agent:main:topic") == true)
            beforeEnqueue()
            enqueue { enqueued++ }
            respond()
          }
        },
        gatewayAdvertisesMethod = { supported },
        json = Json,
        historyCodec = ChatHistoryCodec(Json, ChatSessionCatalogCodec(Json)),
      )

    fun prepare() = checkNotNull(reader.feature.prepare(owner, selection.generation.value, catalog, message))
  }
}
