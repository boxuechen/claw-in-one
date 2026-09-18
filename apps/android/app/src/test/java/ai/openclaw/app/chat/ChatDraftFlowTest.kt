package ai.openclaw.app.chat

import ai.openclaw.app.permissions.SessionPermissionConnection
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.permissions.SessionPermissionsController
import ai.openclaw.app.permissions.SessionPermissionsTransport
import ai.openclaw.app.ui.chat.ChatComposerStateStore
import ai.openclaw.app.ui.chat.chatComposerTextDraftsFromSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftFlowTest {
  @Test fun navigationSnapshotKeepsTheSelectedOwnerButExcludesLocalDrafts() =
    runTest {
      val f = Fixture(this)
      f.chat.switchSession(" agent:alice:topic ", "not-alice")
      assertEquals(ChatNavigationSelection("phone", "agent:alice:topic", "alice"), f.chat.captureNavigationSelection())
      assertTrue(f.chat.startNewDraft())
      assertNull(f.chat.captureNavigationSelection())
      assertTrue(f.creates.isEmpty())
      assertTrue(f.sends.isEmpty())
    }

  private class Fixture(
    scope: TestScope,
  ) : SessionPermissionsTransport {
    val requests = mutableListOf<Pair<String, JsonObject>>()
    private val connection = SessionPermissionConnection("phone", 1, 1, setOf("operator.admin"), setOf("sessions.create", "sessions.describe", "sessions.patch"))
    var created: JsonObject? = null
    var loseCreateResponse = false
    var beforeCreate: suspend () -> Unit = {}
    val permissions = SessionPermissionsController(scope.backgroundScope, this)
    val chat =
      ChatController(
        scope = scope.backgroundScope,
        json = chatControllerTestJson,
        cacheScope = { ChatCacheScope("phone", 1) },
        currentDefaultAgentId = { "main" },
        gatewayAdvertisesMethod = {
          it in
            setOf(
              "sessions.create",
              "sessions.describe",
              "sessions.title.prepare",
              "chat.history",
              "chat.send",
              "health",
            )
        },
        requestGateway = { method, params -> request(connection, method, params ?: "{}") },
        createDraftSession = permissions::create,
        prepareSessionSend = { gateway, key, agent -> permissions.prepareSend(SessionPermissionTarget(checkNotNull(gateway), key, agent)) != null },
      )

    val draft get() = checkNotNull(chat.drafts.find("phone", chat.sessionKey.value))
    val creates get() = requests.filter { it.first == "sessions.create" }
    val sends get() = requests.filter { it.first == "chat.send" }

    override fun capture() = connection

    override fun publish(
      connection: SessionPermissionConnection,
      block: () -> Unit,
    ): Boolean {
      if (connection != this.connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: SessionPermissionConnection,
      method: String,
      params: String,
    ): String {
      val parsed = Json.parseToJsonElement(params).jsonObject
      requests += method to parsed
      return when (method) {
        "sessions.create" -> {
          beforeCreate()
          created = parsed
          if (loseCreateResponse) {
            loseCreateResponse = false
            error("response lost after creation")
          }
          buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("key", parsed.getValue("key"))
            put("entry", row(parsed.getValue("key").jsonPrimitive.content))
          }.toString()
        }
        "sessions.describe" ->
          buildJsonObject {
            put("session", if (created == null) JsonNull else row(parsed.getValue("key").jsonPrimitive.content))
          }.toString()
        "chat.history" ->
          buildJsonObject {
            put("sessionId", JsonPrimitive("session-1"))
            put("messages", Json.parseToJsonElement("[]"))
            put("sessionInfo", row(parsed.getValue("sessionKey").jsonPrimitive.content))
          }.toString()
        "chat.send" -> """{"runId":"run-1"}"""
        "sessions.title.prepare" -> """{"title":"Prepared by Gateway"}"""
        "sessions.list" -> """{"sessions":[]}"""
        else -> "{}"
      }
    }

    fun row(key: String) =
      buildJsonObject {
        put("key", JsonPrimitive(key))
        put("sessionId", JsonPrimitive("session-1"))
        put("permissionMode", created?.get("permissionMode") ?: JsonPrimitive("guarded"))
        put("permissionModePending", JsonPrimitive(false))
        created?.get("model")?.let { put("model", it) }
        created?.get("thinkingLevel")?.let { put("thinkingLevel", it) }
        created?.get("displayName")?.let { put("displayName", it) }
      }
  }

  @Test fun newChatAndLocalSelectionsNeverCreateOrReadAPretendSession() =
    runTest {
      val f = Fixture(this)
      assertTrue(f.chat.startNewDraft())
      val key = f.chat.sessionKey.value
      assertTrue(
        f.chat.messages.value
          .isEmpty(),
      )
      assertNull(f.chat.sessionId.value)
      assertTrue(f.chat.drafts.choose(f.draft.intent.target, SessionPermissionMode.Full))
      assertTrue(f.chat.setSessionModelAwait(key, "deepseek/deepseek-chat"))
      f.chat.setThinkingLevel("low")
      assertTrue(f.requests.isEmpty())
      f.chat.load(key)
      f.chat.refresh()
      runCurrent()
      assertTrue(f.requests.none { it.first in setOf("sessions.create", "sessions.patch", "sessions.describe", "chat.history", "chat.send") })
      assertEquals(key, f.chat.sessionKey.value)
      assertEquals("deepseek/deepseek-chat", f.draft.intent.modelRef)
      assertEquals("deepseek/deepseek-chat", f.chat.selectedModelRef.value)
      assertEquals("low", f.draft.intent.thinkingLevel)
    }

  @Test fun firstSendCreatesWithPermissionsAndModelThenUsesTheSameComposerOwner() =
    runTest {
      val f = Fixture(this)
      f.chat.handleGatewayEvent("health", null)
      assertTrue(f.chat.startNewDraft())
      val intent = f.draft.intent
      f.chat.drafts.choose(intent.target, SessionPermissionMode.Full)
      f.chat.setSessionModelAwait(intent.target.key, "deepseek/deepseek-chat")
      f.chat.setThinkingLevel("low")
      assertTrue(f.chat.sendMessageForOwnerAwaitAcceptance("first message", "off", emptyList(), ChatComposerOwner("phone", "main", intent.target.key), "message-once"))
      val create = f.creates.single().second
      val send = f.sends.single().second
      assertEquals(JsonPrimitive("full"), create["permissionMode"])
      assertEquals(JsonPrimitive("deepseek/deepseek-chat"), create["model"])
      assertEquals(JsonPrimitive("low"), create["thinkingLevel"])
      assertEquals(JsonPrimitive(intent.target.key), create["key"])
      assertEquals(JsonPrimitive(intent.target.key), send["sessionKey"])
      assertEquals(JsonPrimitive("message-once"), send["idempotencyKey"])
      assertTrue(create.keys.none { it in setOf("message", "task", "attachments", "parentSessionKey", "worktree") })
      assertTrue(f.requests.indexOfFirst { it.first == "sessions.describe" } < f.requests.indexOfFirst { it.first == "chat.send" })
      assertEquals(ChatDraftPhase.Created, f.draft.phase)
    }

  @Test fun exactPreparedGatewayTitleIsFrozenIntoFirstSessionCreation() =
    runTest {
      val f = Fixture(this)
      f.chat.handleGatewayEvent("health", null)
      assertTrue(f.chat.startNewDraft())
      val candidate =
        ChatSessionTitleCandidate(
          target = f.draft.intent.target,
          catalogRevision = 0,
          message = "Prepare the release checklist",
        )

      f.chat.sessionTitlePreparationFeature.stage(candidate)
      advanceTimeBy(1_000)
      runCurrent()
      assertTrue(
        f.chat.sendMessageForOwnerAwaitAcceptance(
          message = candidate.message,
          thinkingLevel = "off",
          attachments = emptyList(),
          expectedOwner = ChatComposerOwner("phone", "main", candidate.target.key),
          idempotencyKey = "message-with-title",
        ),
      )

      assertEquals(JsonPrimitive("Prepared by Gateway"), f.creates.single().second["displayName"])
      assertEquals(1, f.requests.count { it.first == "sessions.title.prepare" })
    }

  @Test fun lostCreateResponseDoesNotSendAndReadOnlyRecoveryNeverResends() =
    runTest {
      val f = Fixture(this)
      f.chat.handleGatewayEvent("health", null)
      f.chat.startNewDraft()
      val intent = f.draft.intent
      f.loseCreateResponse = true
      assertFalse(f.chat.sendMessageAwaitAcceptance("keep this text", "off", emptyList()))
      assertEquals(ChatDraftPhase.Unconfirmed, f.draft.phase)
      assertTrue(f.sends.isEmpty())
      val ref = f.permissions.reconcileCreation(intent.target)
      assertNotNull(ref)
      assertTrue(f.chat.drafts.confirm(intent, checkNotNull(ref)))
      runCurrent()
      assertTrue(f.sends.isEmpty())
      assertTrue(f.chat.sendMessageAwaitAcceptance("keep this text", "off", emptyList()))
      assertEquals(1, f.creates.size)
      assertEquals(1, f.sends.size)
    }

  @Test fun explicitRetryOfUnknownCreationDoesNotAllocateAnotherSession() =
    runTest {
      val f = Fixture(this)
      f.chat.handleGatewayEvent("health", null)
      f.chat.startNewDraft()
      val key = f.chat.sessionKey.value
      f.loseCreateResponse = true
      assertFalse(f.chat.sendMessageAwaitAcceptance("hello", "off", emptyList()))
      assertTrue(f.chat.sendMessageAwaitAcceptance("hello", "off", emptyList()))
      assertEquals(key, f.chat.sessionKey.value)
      assertEquals(1, f.creates.size)
      assertEquals(1, f.sends.size)
    }

  @Test fun duplicateSendAndSelectionChangeDuringCreationCannotSendInAnotherChat() =
    runTest {
      val f = Fixture(this)
      val entered = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      f.beforeCreate = {
        entered.complete(Unit)
        release.await()
      }
      f.chat.handleGatewayEvent("health", null)
      f.chat.startNewDraft()
      val firstTarget = f.draft.intent.target
      val first = async { f.chat.sendMessageAwaitAcceptance("first", "off", emptyList()) }
      entered.await()
      assertFalse(f.chat.sendMessageAwaitAcceptance("duplicate", "off", emptyList()))
      assertTrue(f.chat.startNewDraft())
      val secondKey = f.chat.sessionKey.value
      release.complete(Unit)
      assertFalse(first.await())
      assertEquals(secondKey, f.chat.sessionKey.value)
      assertTrue(f.sends.isEmpty())
      assertEquals(1, f.creates.size)
      assertEquals(
        firstTarget.key,
        f.creates
          .single()
          .second
          .getValue("key")
          .jsonPrimitive.content,
      )
      assertEquals(SessionPermissionMode.Standard, f.draft.intent.mode)
    }

  @Test fun draftReconnectCompletesHealthRecoveryWithoutCreatingHistory() =
    runTest {
      val f = Fixture(this)
      f.chat.handleGatewayEvent("health", null)
      f.chat.startNewDraft()
      val key = f.chat.sessionKey.value
      f.chat.onDisconnected("connection changed")
      assertFalse(f.chat.healthOk.value)
      f.chat.onGatewayConnected()
      runCurrent()
      assertTrue(f.chat.healthOk.value)
      assertEquals(key, f.chat.sessionKey.value)
      assertTrue(f.requests.none { it.first in setOf("sessions.create", "chat.history", "chat.send") })
      assertTrue(f.chat.sendMessageAwaitAcceptance("explicit send after reconnect", "off", emptyList()))
      assertEquals(1, f.creates.size)
      assertEquals(1, f.sends.size)
    }

  @Test fun processRecreationDoesNotMoveAnUnknownFirstSendIntoTheNewBlankChat() =
    runTest {
      val first = Fixture(this)
      first.chat.handleGatewayEvent("health", null)
      first.chat.startNewDraft()
      first.chat.drafts.choose(first.draft.intent.target, SessionPermissionMode.Full)
      val owner = ChatComposerOwner("phone", "main", first.draft.intent.target.key)
      var saved = arrayListOf<String>()
      val composer = ChatComposerStateStore(onDraftSnapshotChanged = { saved = it })
      composer.textDrafts[owner] = "first message"
      val pending = checkNotNull(composer.beginSend(owner).request)
      first.loseCreateResponse = true
      assertFalse(first.chat.sendMessageForOwnerAwaitAcceptance(pending.message, "off", emptyList(), owner, pending.commandId))
      // Simulate process loss before the UI completion resolves its admission checkpoint.
      // Gateway creation survived, but no message was journaled or sent.
      val recreated = Fixture(this)
      recreated.created = first.created
      val restored = ChatComposerStateStore(initialDrafts = chatComposerTextDraftsFromSnapshot(saved))
      recreated.chat.startNewDraft()
      val blankOwner = ChatComposerOwner("phone", "main", recreated.draft.intent.target.key)
      assertEquals(SessionPermissionMode.Standard, recreated.draft.intent.mode)
      assertEquals("", restored.textDrafts[blankOwner])
      assertEquals("", restored.textDrafts[owner])
      assertTrue(recreated.requests.isEmpty())
      restored.resolveRecoveredSend(pending.commandId, owner, admitted = false)
      assertEquals("first message", restored.textDrafts[owner])
      assertEquals("", restored.textDrafts[blankOwner])

      // Explicitly reopening the original server Chat reads actual Full, not the new
      // draft's Standard choice. Readback cannot create a second Session or send the text.
      recreated.chat.load(owner.sessionKey)
      runCurrent()
      val permission = recreated.permissions.refresh(first.draft.intent.target)
      assertEquals(SessionPermissionMode.Full, permission.confirmedMode)
      assertTrue(recreated.creates.isEmpty())
      assertTrue(recreated.sends.isEmpty())
      assertTrue(recreated.chat.sendMessageForOwnerAwaitAcceptance(restored.textDrafts[owner], "off", emptyList(), owner, pending.commandId))
      assertTrue(recreated.creates.isEmpty())
      assertEquals(1, recreated.sends.size)
      assertEquals(JsonPrimitive(owner.sessionKey), recreated.sends.single().second["sessionKey"])
    }

  @Test fun processRecreationDoesNotRestoreInputAlreadyOwnedByTheOutbox() =
    runTest {
      val first = Fixture(this)
      first.chat.startNewDraft()
      val owner = ChatComposerOwner("phone", "main", first.draft.intent.target.key)
      var saved = arrayListOf<String>()
      val composer = ChatComposerStateStore(onDraftSnapshotChanged = { saved = it })
      composer.textDrafts[owner] = "journaled input"
      val pending = checkNotNull(composer.beginSend(owner).request)
      val recreated = Fixture(this)
      recreated.chat.startNewDraft()
      val blankOwner = ChatComposerOwner("phone", "main", recreated.draft.intent.target.key)
      val restored = ChatComposerStateStore(initialDrafts = chatComposerTextDraftsFromSnapshot(saved))
      assertEquals(listOf(pending.commandId), restored.recoveredSends().map { it.commandId })
      restored.resolveRecoveredSend(pending.commandId, owner, admitted = true)
      assertEquals("", restored.textDrafts[owner])
      assertEquals("", restored.textDrafts[blankOwner])
      assertTrue(restored.sendStates.value.isEmpty())
      runCurrent()
      assertTrue(recreated.requests.isEmpty())
    }

  @Test fun offlineDraftCannotEnterAnOutboxBeforeSessionCreationIsConfirmed() =
    runTest {
      val f = Fixture(this)
      assertTrue(f.chat.startNewDraft())
      assertFalse(f.chat.sendMessageAwaitAcceptance("wait for connection", "off", emptyList()))
      assertTrue(f.requests.isEmpty())
      assertEquals(ChatDraftPhase.Editing, f.draft.phase)
    }
}
