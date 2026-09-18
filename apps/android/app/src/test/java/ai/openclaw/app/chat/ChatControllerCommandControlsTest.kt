package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerCommandControlsTest {
  private val json = chatControllerTestJson

  @Test
  fun parseChatCommandsKeepsTextAliasesAndArgumentFlag() {
    val commands =
      parseChatCommands(
        json,
        """
        {
          "commands": [
            {
              "name": "new",
              "description": "Start a fresh chat",
              "category": "session",
              "textAliases": ["/new", "/reset"],
              "acceptsArgs": false
            },
            {
              "name": "/model",
              "description": "Switch models",
              "category": "options",
              "textAliases": ["model", "/model"],
              "acceptsArgs": true
            }
          ]
        }
        """.trimIndent(),
      )

    assertEquals(2, commands.size)
    assertEquals("new", commands[0].name)
    assertEquals(listOf("/new", "/reset"), commands[0].textAliases)
    assertEquals(false, commands[0].acceptsArgs)
    assertEquals("model", commands[1].name)
    assertEquals(listOf("/model"), commands[1].textAliases)
    assertEquals(true, commands[1].acceptsArgs)
  }

  @Test
  fun healthEventRefreshesCommandsAfterReconnect() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("chat.metadata", commandResponse("model", "Switch models", acceptsArgs = true))
        }

      controller.handleGatewayEvent("health", null)
      advanceUntilIdle()
      assertEquals(
        listOf("/model"),
        controller.commands.value
          .single()
          .textAliases,
      )

      controller.onDisconnected("gateway closed")
      assertEquals(emptyList<ChatCommandEntry>(), controller.commands.value)

      controller.handleGatewayEvent("health", null)
      advanceUntilIdle()
      assertEquals(
        listOf("/model"),
        controller.commands.value
          .single()
          .textAliases,
      )
      assertEquals(2, requests.count { it.first == "chat.metadata" })
    }

  @Test
  fun terminalSessionEventRefreshesTheCanonicalDerivedTitle() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond(
            "sessions.list",
            """{"sessions":[{"key":"main","sessionId":"session-main","agentId":"main","derivedTitle":"Who r u"}]}""",
          )
        }

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","agentId":"main","phase":"end","runId":"run-1","session":{"key":"main","sessionId":"session-main","agentId":"main","hasActiveRun":false}}""",
      )
      advanceUntilIdle()

      assertEquals(
        "Who r u",
        controller.sessions.value
          .single()
          .derivedTitle,
      )
      assertTrue(
        requests
          .single { it.first == "sessions.list" }
          .second
          .orEmpty()
          .contains("\"includeDerivedTitles\":true"),
      )
    }

  @Test
  fun commandListScopesToActiveAgentAndRefreshesAfterAgentSwitch() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("chat.metadata") { paramsJson ->
            if (paramsJson.orEmpty().contains("\"agentId\":\"ops\"")) {
              commandResponse("ops", "Ops command")
            } else {
              commandResponse("main", "Main command")
            }
          }
          respond("chat.history", """{"sessionId":"loaded-session","messages":[]}""")
          respond("health", "{}")
        }

      controller.handleGatewayEvent("health", null)
      advanceUntilIdle()
      assertEquals(
        listOf("/main"),
        controller.commands.value
          .single()
          .textAliases,
      )

      controller.switchSession("agent:ops:dashboard:parent")
      advanceUntilIdle()
      assertEquals(
        listOf("/ops"),
        controller.commands.value
          .single()
          .textAliases,
      )

      val commandRequests = requests.filter { it.first == "chat.metadata" }
      assertTrue(commandRequests.any { it.second.orEmpty().contains("\"agentId\":\"main\"") })
      assertTrue(commandRequests.any { it.second.orEmpty().contains("\"agentId\":\"ops\"") })
    }

  @Test
  fun delayedCommandListFromPreviousGatewayCannotReplaceCurrentCommands() =
    runTest {
      var cacheScope = ChatCacheScope(gatewayId = "gateway-a", connectionGeneration = 1)
      val gatewayAResponse = CompletableDeferred<String>()
      val controller =
        createChatController(
          requestGatewayForGateway = { gatewayId, method, _ ->
            require(method == "chat.metadata")
            if (gatewayId == "gateway-a") {
              gatewayAResponse.await()
            } else {
              commandResponse("gateway-b")
            }
          },
          cacheScope = { cacheScope },
        ) { _, _ -> error("gateway-bound request expected") }

      controller.refreshCommands()
      runCurrent()
      cacheScope = ChatCacheScope(gatewayId = "gateway-b", connectionGeneration = 2)
      controller.onGatewayScopeChanging()
      controller.refreshCommands()
      runCurrent()
      assertEquals(
        "gateway-b",
        controller.commands.value
          .single()
          .name,
      )

      gatewayAResponse.complete(commandResponse("gateway-a"))
      advanceUntilIdle()

      assertEquals(
        "gateway-b",
        controller.commands.value
          .single()
          .name,
      )
    }

  @Test
  fun sessionMutationsSendGatewayContractsAndRefresh() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("sessions.list", """{"sessions":[]}""")
          respond("sessions.delete", """{"deleted":true}""")
        }

      controller.patchSession(
        key = "main",
        ownerAgentId = "owner-a",
        expectedSessionId = "session-main",
        clearLabel = true,
        pinned = true,
        archived = false,
        unread = true,
      )
      controller.deleteSession("main", ownerAgentId = "main")

      val patch = requests.first { it.first == "sessions.patch" }.second.orEmpty()
      assertTrue(patch.contains("\"key\":\"main\""))
      assertTrue(patch.contains("\"agentId\":\"owner-a\""))
      assertTrue(patch.contains("\"expectedSessionId\":\"session-main\""))
      assertTrue(patch.contains("\"label\":null"))
      assertTrue(patch.contains("\"pinned\":true"))
      assertTrue(patch.contains("\"archived\":false"))
      assertTrue(patch.contains("\"unread\":true"))

      val delete = requests.first { it.first == "sessions.delete" }.second.orEmpty()
      assertTrue(delete.contains("\"key\":\"main\""))
      assertTrue(delete.contains("\"deleteTranscript\":true"))
      assertEquals(2, requests.count { it.first == "sessions.list" })
    }

  @Test
  fun sessionColorCanBeSetAndClearedWithoutOtherChanges() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("sessions.list", """{"sessions":[]}""")
        }

      assertTrue(controller.patchSession(key = "main", ownerAgentId = "owner-a", color = "purple"))
      assertTrue(controller.patchSession(key = "main", ownerAgentId = "owner-a", clearColor = true))

      val patches = requests.filter { it.first == "sessions.patch" }.map { json.parseToJsonElement(it.second!!).jsonObject }
      assertEquals(listOf(JsonPrimitive("purple"), JsonNull), patches.map { it["color"] })
      assertTrue(patches.all { it["agentId"] == JsonPrimitive("owner-a") })
      assertEquals(2, requests.count { it.first == "sessions.list" })
    }

  @Test
  fun manualSessionRenamePersistsExplicitLabel() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          gatewayAdvertisesMethod = { it == "sessions.patch" }
          respond("sessions.patch", "{}")
          respond("sessions.list", """{"sessions":[]}""")
        }
      val session =
        ChatSessionEntry(
          key = "agent:owner-a:claw-in-one-project:one",
          updatedAtMs = 1,
          sessionId = "session-one",
          ownerAgentId = "owner-a",
        )

      assertTrue(controller.setConversationLabel(session, "  Renamed chat  "))
      assertTrue(controller.setConversationLabel(session, null))

      val patches = requests.filter { it.first == "sessions.patch" }.map { it.second.orEmpty() }
      assertTrue(patches[0].contains("\"key\":\"agent:owner-a:claw-in-one-project:one\""))
      assertTrue(patches[0].contains("\"agentId\":\"owner-a\""))
      assertTrue(patches[0].contains("\"expectedSessionId\":\"session-one\""))
      assertTrue(patches[0].contains("\"label\":\"Renamed chat\""))
      assertTrue(patches[1].contains("\"label\":null"))
      assertFalse(controller.setConversationLabel(session, "x".repeat(CHAT_SESSION_LABEL_MAX_CHARS + 1)))
      assertEquals(2, requests.count { it.first == "sessions.patch" })
    }

  @Test
  fun conversationDeleteArchivesTheObservedSessionBeforeDeletingItsTranscript() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          cacheScope = { ChatCacheScope("gateway-a", 1) }
          respond("sessions.patch", "{}")
          respond("sessions.delete", """{"deleted":true}""")
          respond("sessions.list", """{"sessions":[]}""")
        }
      val session =
        ChatSessionEntry(
          key = "conversation-a",
          updatedAtMs = 1,
          sessionId = "session-a",
          ownerAgentId = "owner-a",
        )

      assertTrue(controller.deleteConversation(session))

      val mutationMethods = requests.map { it.first }.filter { it in setOf("sessions.patch", "sessions.delete") }
      assertEquals(listOf("sessions.patch", "sessions.delete"), mutationMethods)
      val archive = requests.single { it.first == "sessions.patch" }.second.orEmpty()
      assertTrue(archive.contains("\"expectedSessionId\":\"session-a\""))
      assertTrue(archive.contains("\"archived\":true"))
      val delete = requests.single { it.first == "sessions.delete" }.second.orEmpty()
      assertTrue(delete.contains("\"deleteTranscript\":true"))
      assertTrue(delete.contains("\"archivedOnly\":true"))
    }

  @Test
  fun archiveUsesObservedIdentityAndArchiveDeadline() =
    runTest {
      var archiveParams: String? = null
      var archiveTimeoutMs: Long? = null
      val controller =
        ChatController(
          scope = this,
          json = json,
          requestGateway = { method, _ ->
            check(method != "sessions.patch") { "archive must use its captured request lease" }
            if (method == "sessions.list") """{"sessions":[]}""" else "{}"
          },
          cacheScope = { ChatCacheScope("gateway-a", 1) },
          captureRequestLease = { capturedScope ->
            assertEquals(ChatCacheScope("gateway-a", 1), capturedScope)
            GatewaySession.RequestLease(endpointStableId = "gateway-a") { method, paramsJson, timeoutMs, withEnqueue ->
              withEnqueue {}
              assertEquals("sessions.patch", method)
              archiveParams = paramsJson
              archiveTimeoutMs = timeoutMs
              "{}"
            }
          },
        )

      assertTrue(
        controller.patchSession(
          key = "agent:main:side",
          expectedSessionId = "session-side",
          archived = true,
        ),
      )

      assertTrue(archiveParams.orEmpty().contains("\"expectedSessionId\":\"session-side\""))
      assertEquals(10 * 60_000L, archiveTimeoutMs)
    }

  @Test
  fun archiveWithoutObservedIdentityDoesNotDispatch() =
    runTest {
      val requests = mutableListOf<String>()
      val controller =
        ChatController(
          scope = this,
          json = json,
          requestGateway = { method, _ ->
            requests += method
            "{}"
          },
        )

      assertFalse(controller.patchSession(key = "agent:main:cached", archived = true))
      assertFalse(requests.contains("sessions.patch"))
    }

  @Test
  fun archivedSessionListAndOpenUnreadSessionUsePatchContracts() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond(
            "sessions.list",
            """{"sessions":[{"key":"main","sessionId":"session-main","unread":true}]}""",
          )
        }

      controller.refreshSessions(archived = true)
      advanceUntilIdle()
      assertTrue(
        requests
          .first { it.first == "sessions.list" }
          .second
          .orEmpty()
          .contains("\"archived\":true"),
      )
      assertEquals(
        "session-main",
        controller.sessions.value
          .single()
          .sessionId,
      )

      controller.switchSession("main")
      advanceUntilIdle()
      controller.switchSession("main")
      advanceUntilIdle()

      val patch = requests.single { it.first == "sessions.patch" }.second.orEmpty()
      assertTrue(patch.contains("\"key\":\"main\""))
      assertTrue(patch.contains("\"unread\":false"))
    }

  @Test
  fun sessionEventsApplyExplicitMetadataClears() =
    runTest {
      val controller =
        createScriptedChatController {
          respond("sessions.list", """{"sessions":[{"key":"main","label":"Named","color":" BLUE "}]}""")
        }

      controller.refreshSessions()
      advanceUntilIdle()
      assertEquals(
        "blue",
        controller.sessions.value
          .single()
          .color,
      )

      // Another client cleared the metadata; the gateway sends explicit nulls.
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","label":null,"color":null}}""",
      )
      advanceUntilIdle()
      val merged = controller.sessions.value.single()
      assertEquals(null, merged.label)
      assertEquals(null, merged.color)
    }

  @Test
  fun failedReadAcknowledgementUnlatchesForRetry() =
    runTest {
      var failPatches = true
      val (controller, requests) =
        chatControllerTestSetup {
          respond("sessions.patch") { paramsJson ->
            if (failPatches) throw RuntimeException("offline") else "{}"
          }
          respond("sessions.list", """{"sessions":[{"key":"main","unread":true}]}""")
        }

      controller.refreshSessions()
      advanceUntilIdle()
      controller.switchSession("main")
      advanceUntilIdle()
      assertEquals(1, requests.count { it.first == "sessions.patch" })

      // The failed acknowledgement unlatched; the next unread snapshot retries.
      failPatches = false
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":true}}""",
      )
      advanceUntilIdle()
      assertEquals(2, requests.count { it.first == "sessions.patch" })
    }

  @Test
  fun reopenedSessionAcknowledgementIsNotUnlatchedByAnOldFailure() =
    runTest {
      val old = CompletableDeferred<String>()
      val fresh = CompletableDeferred<String>()
      var patches = 0
      val (controller, requests) =
        chatControllerTestSetup {
          respond("sessions.list", """{"sessions":[{"key":"main","unread":true},{"key":"other","unread":false}]}""")
          respond("sessions.patch") { if (++patches == 1) old.await() else fresh.await() }
        }
      controller.refreshSessions()
      advanceUntilIdle()
      controller.switchSession("main")
      runCurrent()
      controller.switchSession("other")
      runCurrent()
      controller.switchSession("main")
      runCurrent()
      old.completeExceptionally(IllegalStateException("old visit failed"))
      runCurrent()
      controller.handleGatewayEvent("sessions.changed", """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":true}}""")
      runCurrent()
      assertEquals(2, requests.count { it.first == "sessions.patch" })
      assertFalse(controller.errorText.value == "old visit failed")
      fresh.complete("{}")
      advanceUntilIdle()
    }

  @Test
  fun gatewayResetRetiresAQueuedReadAcknowledgementBeforeDispatch() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("sessions.list", """{"sessions":[{"key":"main","unread":true}]}""")
        }
      controller.refreshSessions()
      advanceUntilIdle()
      controller.switchSession("main")
      controller.onGatewayScopeChanging()
      advanceUntilIdle()
      assertTrue(requests.none { it.first == "sessions.patch" })
    }

  @Test
  fun explicitMarkReadDoesNotUseTheAutomaticAcknowledgementCondition() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          gatewayAdvertisesCapability = { it == SESSION_UNREAD_ACK_CAPABILITY }
        }

      assertTrue(controller.patchSession(key = "main", unread = false))
      advanceUntilIdle()

      val patch = requests.single { it.first == "sessions.patch" }.second.orEmpty()
      assertTrue(patch.contains("\"unread\":false"))
      assertFalse(patch.contains("readIntent"))
      assertFalse(patch.contains("expectedMarkedUnreadAt"))
    }

  @Test
  fun archivingOrDeletingTheOpenSessionFallsBackToMain() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("chat.history", """{"sessionId":"session-side","messages":[]}""")
          respond("sessions.list", """{"sessions":[{"key":"agent:main:side","sessionId":"session-side"}]}""")
          respond("sessions.delete", """{"deleted":true}""")
        }

      controller.switchSession("agent:main:side")
      advanceUntilIdle()
      assertEquals("agent:main:side", controller.sessionKey.value)

      controller.patchSession(
        key = "agent:main:side",
        expectedSessionId = "session-side",
        archived = true,
      )
      advanceUntilIdle()
      assertEquals("main", controller.sessionKey.value)

      controller.switchSession("agent:main:side")
      advanceUntilIdle()
      controller.deleteSession("agent:main:side")
      advanceUntilIdle()
      assertEquals("main", controller.sessionKey.value)
    }

  @Test
  fun openSessionReacknowledgesUnreadOncePerEpisode() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("sessions.list", """{"sessions":[{"key":"main","unread":false}]}""")
        }

      controller.refreshSessions()
      advanceUntilIdle()
      controller.switchSession("main")
      advanceUntilIdle()
      assertEquals(0, requests.count { it.first == "sessions.patch" })

      // A run completes while the session stays open: the gateway flags it unread again.
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":true}}""",
      )
      advanceUntilIdle()
      assertEquals(1, requests.count { it.first == "sessions.patch" })
      assertFalse(
        requests
          .single { it.first == "sessions.patch" }
          .second
          .orEmpty()
          .contains("expectedMarkedUnreadAt"),
      )

      // Server-confirmed read resets the episode; a stale duplicate must not re-patch.
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":false}}""",
      )
      advanceUntilIdle()
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":true}}""",
      )
      advanceUntilIdle()
      assertEquals(2, requests.count { it.first == "sessions.patch" })
    }

  @Test
  fun manualUnreadOnOpenSessionSurvivesRunUpdatesUntilReactivation() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          gatewayAdvertisesCapability = { it == SESSION_UNREAD_ACK_CAPABILITY }
          respond(
            "sessions.list",
            """{"sessions":[{"key":"main","unread":false},{"key":"other","unread":false}]}""",
          )
        }

      controller.refreshSessions()
      advanceUntilIdle()
      controller.switchSession("main")
      advanceUntilIdle()

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":true,"markedUnreadAt":100}}""",
      )
      advanceUntilIdle()
      assertEquals(0, requests.count { it.first == "sessions.patch" })
      val retained = controller.sessions.value.first { it.key == "main" }
      assertEquals(true, retained.unread)
      assertEquals(100L, retained.markedUnreadAt)

      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":true,"markedUnreadAt":100,"hasActiveRun":true,"status":"running"}}""",
      )
      controller.handleGatewayEvent(
        "sessions.changed",
        """{"sessionKey":"main","session":{"key":"main","agentId":"main","unread":true,"markedUnreadAt":100,"hasActiveRun":false,"status":"done"}}""",
      )
      advanceUntilIdle()
      assertEquals(0, requests.count { it.first == "sessions.patch" })

      controller.switchSession("other")
      advanceUntilIdle()
      controller.switchSession("main")
      advanceUntilIdle()

      val patch = requests.single { it.first == "sessions.patch" }.second.orEmpty()
      assertTrue(patch.contains("\"unread\":false"))
      assertTrue(patch.contains("\"expectedMarkedUnreadAt\":100"))
      assertFalse(patch.contains("readIntent"))
    }

  @Test
  fun bareNewSlashCommandUsesGatewayChatCommandPath() =
    runTest {
      val (controller, requests) =
        chatControllerTestSetup {
          respond("chat.send", """{"runId":"run-new"}""")
          respond("health", "{}")
        }
      controller.handleGatewayEvent("health", null)

      assertTrue(controller.sendMessageAwaitAcceptance("/new", "off", emptyList()))

      val send = requests.single { it.first == "chat.send" }
      assertTrue(send.second.orEmpty().contains("\"message\":\"/new\""))
      assertTrue(requests.none { it.first == "sessions.create" })
    }

  private fun commandResponse(
    name: String,
    description: String? = null,
    acceptsArgs: Boolean = false,
  ): String {
    val descriptionJson = description?.let { ""","description":"$it"""" }.orEmpty()
    return """{"commands":[{"name":"$name"$descriptionJson,"textAliases":["/$name"],"acceptsArgs":$acceptsArgs}]}"""
  }
}
