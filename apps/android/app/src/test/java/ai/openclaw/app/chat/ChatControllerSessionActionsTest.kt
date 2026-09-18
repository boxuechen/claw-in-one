package ai.openclaw.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerSessionActionsTest {
  private val json = Json { ignoreUnknownKeys = true }

  private fun controller(
    scope: kotlinx.coroutines.CoroutineScope,
    gateway: ScriptedGateway,
  ): ChatController =
    ChatController(
      scope = scope,
      json = json,
      requestGateway = gateway::request,
    )

  private inner class ArchiveFixture(
    private val scope: TestScope,
  ) {
    val gateway = ScriptedGateway(json)
    var gatewayScope = ChatCacheScope("gateway-a", 1)
    var defaultAgentId = "owner-a"
    var defaultAgentRevision = 0L
    var canonicalSessionId = "session-a"
    val routedRequests = mutableListOf<Pair<String, ScriptedGateway.Call>>()
    val controller =
      ChatController(
        scope = scope,
        json = json,
        requestGateway = gateway::request,
        requestGatewayForGateway = { gatewayId, method, params ->
          routedRequests += gatewayId to ScriptedGateway.Call(method, params)
          gateway.request(method, params)
        },
        cacheScope = { gatewayScope },
        currentDefaultAgentId = { defaultAgentId },
        currentDefaultAgentRevision = { defaultAgentRevision },
      )
    private val archiveResponse = CompletableDeferred<String>()

    init {
      gateway.respond("chat.history") { params ->
        val request = json.parseToJsonElement(params!!).jsonObject
        val key = request.getValue("sessionKey").jsonPrimitive.content
        val owner = request.getValue("agentId").jsonPrimitive.content
        historyResponse(
          sessionId = if (key == "custom") canonicalSessionId else "session-$key",
          messages = listOf(ReplayHistoryMessage("user", "${gatewayScope.gatewayId}/$owner/$key/$canonicalSessionId", 1)),
        )
      }
      gateway.respond("sessions.patch") { archiveResponse.await() }
    }

    suspend fun completeArchive(
      expectedSessionId: String = "session-a",
      ownerAgentId: String = "owner-a",
      fallsBack: Boolean = false,
      whilePending: suspend () -> Unit = {},
    ) {
      val archive =
        scope.async {
          controller.patchSession(
            key = "custom",
            ownerAgentId = ownerAgentId,
            expectedSessionId = expectedSessionId,
            archived = true,
          )
        }
      try {
        scope.runCurrent()
        assertFalse(archive.isCompleted)
        val request = json.parseToJsonElement(gateway.calls.single { it.method == "sessions.patch" }.paramsJson!!).jsonObject
        assertEquals("custom", request.getValue("key").jsonPrimitive.content)
        assertEquals(ownerAgentId, request.getValue("agentId").jsonPrimitive.content)
        assertEquals(expectedSessionId, request.getValue("expectedSessionId").jsonPrimitive.content)
        assertEquals("true", request.getValue("archived").jsonPrimitive.content)
        whilePending()
        scope.runCurrent()
        val selectedKey = controller.sessionKey.value
        val selectedOwner = controller.sessionOwnerAgentId.value
        val selectedId = controller.sessionId.value
        val selectedMessages = controller.messages.value
        assertTrue(selectedMessages.isNotEmpty())
        archiveResponse.complete("{}")
        assertTrue(archive.await())
        scope.runCurrent()
        assertNull(controller.errorText.value)
        if (fallsBack) {
          assertEquals("main", controller.sessionKey.value)
          assertEquals("session-main", controller.sessionId.value)
        } else {
          assertEquals(selectedKey, controller.sessionKey.value)
          assertEquals(selectedOwner, controller.sessionOwnerAgentId.value)
          assertEquals(selectedId, controller.sessionId.value)
          assertEquals(selectedMessages, controller.messages.value)
        }
        assertEquals("gateway-a", routedRequests.single { it.second.method == "sessions.patch" }.first)
      } finally {
        archiveResponse.complete("{}")
      }
    }
  }

  private fun TestScope.loadedArchiveFixture(ownerAgentId: String? = "owner-a"): ArchiveFixture =
    ArchiveFixture(this).also {
      it.controller.load("custom", ownerAgentId)
      runCurrent()
      assertEquals("session-a", it.controller.sessionId.value)
    }

  @Test
  fun archiveCompletionFallsBackForTheMatchingActiveOccurrence() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive(fallsBack = true) { fixture.controller.refresh() }
    }

  @Test
  fun archiveCompletionPreservesSameKeyCanonicalSuccessorWithoutNewSelection() =
    runTest {
      val fixture = loadedArchiveFixture()
      val selection = fixture.controller.selectionGeneration.value
      fixture.completeArchive {
        fixture.canonicalSessionId = "session-b"
        fixture.controller.refresh()
        runCurrent()
        assertEquals("session-b", fixture.controller.sessionId.value)
        assertEquals(selection, fixture.controller.selectionGeneration.value)
      }
    }

  @Test
  fun archiveCompletionPreservesSameKeyAndIdAcrossPairingReplacement() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive {
        fixture.gatewayScope = ChatCacheScope("gateway-b", 2)
        fixture.controller.onGatewayScopeChanging()
        fixture.controller.load("custom", "owner-a")
      }
    }

  @Test
  fun archiveCompletionPreservesSameKeyAndIdAfterReconnect() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive {
        fixture.controller.onDisconnected("reconnecting")
        fixture.gatewayScope = ChatCacheScope("gateway-a", 2)
        fixture.controller.onGatewayConnected()
      }
    }

  @Test
  fun archiveCompletionPreservesSameKeyAndIdForAnotherOwner() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive { fixture.controller.switchSession("custom", "owner-b") }
    }

  @Test
  fun archiveCompletionPreservesChangedDefaultOwnerWithoutNewSelection() =
    runTest {
      val fixture = loadedArchiveFixture(ownerAgentId = null)
      val selection = fixture.controller.selectionGeneration.value
      fixture.completeArchive {
        fixture.defaultAgentRevision += 1
        fixture.defaultAgentId = "owner-b"
        fixture.controller.onDefaultAgentChanged("owner-b")
        runCurrent()
        assertEquals(selection, fixture.controller.selectionGeneration.value)
      }
    }

  @Test
  fun archiveCompletionPreservesDefaultOwnerAwayAndBackToTheSameOccurrence() =
    runTest {
      val fixture = loadedArchiveFixture(ownerAgentId = null)
      val selection = fixture.controller.selectionGeneration.value
      fixture.completeArchive {
        for (owner in listOf("owner-b", "owner-a")) {
          fixture.defaultAgentRevision += 1
          fixture.defaultAgentId = owner
          fixture.controller.onDefaultAgentChanged(owner)
          runCurrent()
        }
        assertEquals(selection, fixture.controller.selectionGeneration.value)
      }
    }

  @Test
  fun archiveCompletionStillFallsBackAfterUnrelatedDefaultOwnerChange() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive(fallsBack = true) {
        fixture.defaultAgentRevision += 1
        fixture.defaultAgentId = "owner-b"
        fixture.controller.onDefaultAgentChanged("owner-b")
      }
    }

  @Test
  fun archiveCompletionPreservesDifferentNewerNavigation() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive { fixture.controller.switchSession("other", "owner-a") }
    }

  @Test
  fun archiveCompletionPreservesNavigationAwayAndBackToTheSameOccurrence() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive {
        fixture.controller.switchSession("other", "owner-a")
        runCurrent()
        fixture.controller.switchSession("custom", "owner-a")
      }
    }

  @Test
  fun archiveOfInactiveRowStillSucceedsWhenTheRowIsSelectedWhilePending() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.controller.switchSession("other", "owner-a")
      runCurrent()
      fixture.completeArchive { fixture.controller.switchSession("custom", "owner-a") }
    }

  @Test
  fun archiveOfAnotherOccurrenceCannotAcquireNavigationOwnershipOnCompletion() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive(expectedSessionId = "session-b") {
        fixture.canonicalSessionId = "session-b"
        fixture.controller.refresh()
      }
    }

  @Test
  fun archiveOfAnotherOwnerPreservesTheActiveSameKeyOccurrence() =
    runTest {
      val fixture = loadedArchiveFixture()
      fixture.completeArchive(ownerAgentId = "owner-b")
    }
}
