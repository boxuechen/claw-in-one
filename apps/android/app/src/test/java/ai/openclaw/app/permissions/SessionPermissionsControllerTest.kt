package ai.openclaw.app.permissions

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionPermissionsControllerTest {
  private val a = SessionPermissionTarget("phone", "agent:main:chat-a", "main")
  private val b = SessionPermissionTarget("phone", "agent:main:chat-b", "main")

  @Test fun featureReadsAndChoosesThroughTheSingleOwnerWithoutCreatingOrSending() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(backgroundScope, fake)
      val feature = owner.feature
      assertSame(owner.states, feature.states)
      assertSame(feature, owner.feature)
      assertTrue(fake.requests.isEmpty())
      assertNull(feature.reconcileCreation(a))
      assertTrue(fake.requests.isEmpty())
      assertEquals(SessionPermissionMode.Standard, feature.refresh(a).confirmedMode)
      val choices =
        listOf(
          SessionPermissionMode.ReadOnly,
          SessionPermissionMode.Standard,
          SessionPermissionMode.Workspace,
          SessionPermissionMode.Full,
          SessionPermissionMode.Default,
        )
      choices.forEach { mode ->
        assertTrue(feature.choose(a, mode))
        assertEquals(
          mode,
          feature.states.value
            .getValue(a)
            .confirmedMode,
        )
      }
      assertEquals(choices.size, fake.patches.size)
      assertTrue(fake.requests.all { it.first in setOf("sessions.describe", "sessions.patch") })
      assertFalse(feature.states.value.containsKey(b))
    }

  @Test fun retainedPermissionFeatureCannotChooseOnAReplacementOwner() =
    runTest {
      val firstTransport = Fake()
      val first = SessionPermissionsController(backgroundScope, firstTransport)
      val secondTransport = Fake()
      val second = SessionPermissionsController(backgroundScope, secondTransport)
      first.feature.refresh(a)
      second.feature.refresh(a)
      firstTransport.connection = null
      first.onConnectionChanged()

      assertFalse(first.feature.choose(a, SessionPermissionMode.Full))

      assertTrue(firstTransport.patches.isEmpty())
      assertTrue(secondTransport.patches.isEmpty())
      assertEquals(
        SessionPermissionMode.Standard,
        second.feature.states.value
          .getValue(a)
          .confirmedMode,
      )
    }

  private class Fake : SessionPermissionsTransport {
    var connection: SessionPermissionConnection? =
      SessionPermissionConnection(
        "phone",
        1,
        1,
        setOf("operator.admin"),
        setOf("sessions.describe", "sessions.patch", "sessions.create"),
      )
    var mode: String? = "guarded"
    var projectId: String? = null
    var sessionRoot: String? = null
    var displayName: String? = null
    var redactProjectIdFromRead = false
    var sessionId = "session-a"
    var pending = false
    val requests = mutableListOf<Pair<String, JsonObject>>()
    var onPatch: suspend (JsonObject) -> String = { params ->
      mode =
        params
          .getValue("permissionMode")
          .takeUnless { it == JsonNull }
          ?.jsonPrimitive
          ?.content
      mutation(params.getValue("key").jsonPrimitive.content)
    }
    var onRead: (suspend (JsonObject) -> String)? = null
    var onCreate: suspend (JsonObject) -> String = { params ->
      mode = params.getValue("permissionMode").jsonPrimitive.content
      projectId = params["projectId"]?.jsonPrimitive?.content
      sessionRoot = projectId?.let { "/workspace/$it" }
      displayName = params["displayName"]?.jsonPrimitive?.content
      mutation(params.getValue("key").jsonPrimitive.content)
    }

    override fun capture() = connection

    override fun publish(
      connection: SessionPermissionConnection,
      block: () -> Unit,
    ): Boolean {
      if (this.connection != connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: SessionPermissionConnection,
      method: String,
      params: String,
    ): String {
      val parsed = Json.parseToJsonElement(params) as JsonObject
      requests += method to parsed
      return when (method) {
        "sessions.patch" -> onPatch(parsed)
        "sessions.create" -> onCreate(parsed)
        "sessions.describe" -> onRead?.invoke(parsed) ?: row(parsed.getValue("key").jsonPrimitive.content)
        else -> error("Unexpected method $method")
      }
    }

    fun row(key: String) = """{"session":{"key":"$key","sessionId":"$sessionId","permissionMode":${mode?.let { "\"$it\"" } ?: "null"},"projectId":${projectId?.takeUnless { redactProjectIdFromRead }?.let { "\"$it\"" } ?: "null"},"sessionRoot":${sessionRoot?.let { "\"$it\"" } ?: "null"},"displayName":${displayName?.let { "\"$it\"" } ?: "null"},"permissionModePending":$pending}}"""

    fun mutation(key: String) = """{"ok":true,"key":"$key","entry":{"sessionId":"$sessionId","permissionMode":${mode?.let { "\"$it\"" } ?: "null"},"projectId":${projectId?.let { "\"$it\"" } ?: "null"},"lifecycleRevision":"revision-2"}}"""

    val patches get() = requests.filter { it.first == "sessions.patch" }
  }

  @Test fun creationSettingsAreFrozenAndMustMatchCanonicalReadback() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      var model = "other-model"
      var displayName = "Other title"
      fake.onRead = { params ->
        """{"session":{"key":${params.getValue("key")},"sessionId":"session-a","permissionMode":"full","modelProvider":"deepseek","model":"$model","thinkingLevel":"off","displayName":"$displayName","permissionModePending":false}}"""
      }
      val draft =
        SessionPermissionDraft(
          target = a,
          idempotencyKey = "same-create",
          mode = SessionPermissionMode.Full,
          modelRef = "deepseek/deepseek-chat",
          thinkingLevel = "off",
          displayName = "Release checklist",
        )
      assertNull(owner.create(draft))
      val request = fake.requests.single { it.first == "sessions.create" }.second
      assertEquals(JsonPrimitive(draft.modelRef), request["model"])
      assertEquals(JsonPrimitive("off"), request["thinkingLevel"])
      assertEquals(JsonPrimitive("Release checklist"), request["displayName"])
      assertNull(owner.reconcileCreation(a))
      model = "deepseek-chat"
      assertNull(owner.reconcileCreation(a))
      displayName = "Release checklist"
      assertNotNull(owner.reconcileCreation(a))
      assertEquals(1, fake.requests.count { it.first == "sessions.create" })
    }

  @Test fun draftIntentDoesNotCreateAndFirstAdmissionContainsNoMessage() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      val draft = SessionPermissionDraft(a, "create-once", SessionPermissionMode.Full)
      assertTrue(fake.requests.isEmpty())
      val ref = owner.create(draft)
      assertNotNull(ref)
      assertEquals(SessionPermissionMode.Full, owner.state(a).confirmedMode)
      val creation = fake.requests.single { it.first == "sessions.create" }.second
      assertEquals(setOf("key", "agentId", "idempotencyKey", "permissionMode"), creation.keys)
      assertEquals(JsonPrimitive("create-once"), creation["idempotencyKey"])
      assertEquals(ref?.sessionId, owner.create(draft)?.sessionId)
      assertEquals(1, fake.requests.count { it.first == "sessions.create" })
    }

  @Test fun workspaceCreationConfirmsTheExactProjectBeforeFirstSend() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      val draft =
        SessionPermissionDraft(
          target = a,
          idempotencyKey = "workspace-create-once",
          mode = SessionPermissionMode.Workspace,
          projectId = "project-1",
          projectRoot = "/workspace/project-1",
        )
      val ref = owner.create(draft)
      assertNotNull(ref)
      val creation = fake.requests.single { it.first == "sessions.create" }.second
      assertEquals(JsonPrimitive("workspace"), creation["permissionMode"])
      assertEquals(JsonPrimitive("project-1"), creation["projectId"])
      assertEquals(SessionPermissionMode.Workspace, owner.state(a).confirmedMode)

      fake.projectId = "other-project"
      assertNull(owner.create(draft))
    }

  @Test fun workspaceCreationUsesExactRootWhenReadProjectionRedactsProjectId() =
    runTest {
      val fake = Fake()
      fake.redactProjectIdFromRead = true
      val owner = SessionPermissionsController(this, fake)
      val draft =
        SessionPermissionDraft(
          target = a,
          idempotencyKey = "workspace-redacted-project",
          mode = SessionPermissionMode.Workspace,
          projectId = "project-1",
          projectRoot = "/workspace/project-1",
        )

      val ref = owner.create(draft)
      assertEquals("project-1", ref?.projectId)
      assertEquals("/workspace/project-1", ref?.projectRoot)
      assertEquals("project-1", owner.prepareSend(a)?.projectId)

      fake.sessionRoot = "/workspace/other-project"
      assertNull(owner.create(draft))
    }

  @Test fun unknownCreateReadbackUsesSameIdentityAndNeverSendsOrRepeatsCreation() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      val draft = SessionPermissionDraft(a, "create-once", SessionPermissionMode.Full)
      fake.onCreate = {
        fake.mode = "full"
        throw GatewayRequestOutcomeUnknown("Lost create response")
      }
      assertNull(owner.create(draft))
      assertEquals(SessionPermissionPhase.Unconfirmed, owner.state(a).phase)
      assertNotNull(owner.reconcileCreation(a))
      assertEquals(SessionPermissionPhase.Ready, owner.state(a).phase)
      assertEquals(listOf("sessions.create", "sessions.describe"), fake.requests.map { it.first })
    }

  @Test fun changedPermissionOrReplacedSessionCannotBeRecreatedThroughAnOldDraft() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      val draft = SessionPermissionDraft(a, "create-once", SessionPermissionMode.Full)
      assertNotNull(owner.create(draft))
      assertTrue(owner.choose(a, SessionPermissionMode.Standard))
      assertNull(owner.create(draft))
      assertNull(owner.reconcileCreation(a))
      fake.mode = "full"
      fake.sessionId = "replacement"
      assertNull(owner.create(draft))
      assertEquals(1, fake.requests.count { it.first == "sessions.create" })
      assertEquals(1, fake.patches.size)
    }

  @Test fun unknownCreationKeepsPayloadFixedAndMismatchDoesNotAdmitFirstSend() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      val draft = SessionPermissionDraft(a, "create-once", SessionPermissionMode.Full)
      fake.onCreate = { throw GatewayRequestOutcomeUnknown("No response") }
      assertNull(owner.create(draft))
      assertNull(owner.reconcileCreation(a)) // readback is Standard, not requested Full
      assertNull(owner.create(draft)) // existing row cannot be overwritten by replaying old intent
      assertNull(owner.create(draft.copy(mode = SessionPermissionMode.Standard)))
      assertNull(owner.prepareSend(a))
      assertEquals(1, fake.requests.count { it.first == "sessions.create" })
    }

  @Test fun fullRequiresActualAdminScopeAndOtherModesStayTruthful() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      for ((wire, expected) in listOf(null to SessionPermissionMode.Default, "read-only" to SessionPermissionMode.ReadOnly, "workspace" to SessionPermissionMode.Workspace)) {
        fake.mode = wire
        assertEquals(expected, owner.refresh(a).confirmedMode)
      }
      fake.connection = fake.connection!!.copy(scopes = setOf("operator.write"))
      owner.refresh(a)
      assertFalse(owner.choose(a, SessionPermissionMode.Full))
      assertEquals(SessionPermissionFailure.MissingAuthority, owner.state(a).failure)
      assertTrue(fake.patches.isEmpty())
      listOf(SessionPermissionMode.ReadOnly, SessionPermissionMode.Default, SessionPermissionMode.Standard).forEach { mode ->
        assertTrue(owner.choose(a, mode))
        assertEquals(mode, owner.state(a).confirmedMode)
      }
    }

  @Test fun changeConfirmsAckAndReadbackWithoutOptimisticFull() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      owner.refresh(a)
      val release = CompletableDeferred<Unit>()
      fake.onPatch = {
        release.await()
        fake.mode = "full"
        fake.mutation(a.key)
      }
      val write = async { owner.choose(a, SessionPermissionMode.Full) }
      runCurrent()
      assertEquals(SessionPermissionPhase.Applying, owner.state(a).phase)
      assertEquals(SessionPermissionMode.Standard, owner.state(a).confirmedMode)
      assertFalse(owner.choose(a, SessionPermissionMode.Full))
      release.complete(Unit)
      assertTrue(write.await())
      assertTrue(owner.state(a).readyToSend)
      assertEquals(SessionPermissionMode.Full, owner.state(a).confirmedMode)
      assertEquals("revision-2", owner.state(a).ref?.lifecycleRevision)
      val params = fake.patches.single().second
      assertEquals(JsonPrimitive("session-a"), params["expectedSessionId"])
      assertEquals(JsonPrimitive("guarded"), params["expectedPermissionMode"])
      assertFalse("expectedLifecycleRevision" in params)
      assertFalse("execSecurity" in params || "execAsk" in params)
    }

  @Test fun savedThenApplicationFailureStaysUnconfirmedUntilAcknowledgedStopAndReadback() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      owner.refresh(a)
      fake.onPatch = {
        fake.mode = "full"
        throw GatewayRequestRejected(GatewaySession.ErrorShape("UNAVAILABLE", "Saved but application failed"))
      }
      assertFalse(owner.choose(a, SessionPermissionMode.Full))
      assertEquals(SessionPermissionMode.Full, owner.state(a).savedMode)
      assertEquals(SessionPermissionMode.Standard, owner.state(a).confirmedMode)
      assertEquals(SessionPermissionPhase.Unconfirmed, owner.refresh(a).phase)
      assertNull(owner.prepareSend(a))
      assertFalse(owner.choose(a, SessionPermissionMode.Standard))
      assertEquals(1, fake.patches.size)
      assertTrue(owner.reconcileStopped(checkNotNull(owner.state(a).ref)))
      assertNotNull(owner.prepareSend(a))
    }

  @Test fun lostResponseAndReconnectNeverRepeatWriteOrRestoreConfirmedAuthority() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      owner.refresh(a)
      fake.onPatch = {
        fake.mode = "full"
        throw GatewayRequestOutcomeUnknown("Response lost")
      }
      owner.choose(a, SessionPermissionMode.Full)
      val oldRef = checkNotNull(owner.state(a).ref)
      fake.connection = null
      owner.onConnectionChanged()
      fake.connection = oldRef.connection.copy(generation = 2)
      owner.onConnectionChanged()
      assertEquals(SessionPermissionPhase.Unconfirmed, owner.refresh(a).phase)
      assertNull(owner.prepareSend(a))
      assertFalse(owner.reconcileStopped(oldRef))
      assertEquals(1, fake.patches.size)
    }

  @Test fun timeoutWithAnUnchangedRowIsNotProofThatTheWriteDidNotRun() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      owner.refresh(a)
      fake.onPatch = { throw GatewayRequestOutcomeUnknown("Server may still be saving") }
      assertFalse(owner.choose(a, SessionPermissionMode.Full))
      assertEquals(SessionPermissionMode.Standard, owner.state(a).savedMode)
      assertEquals(SessionPermissionPhase.Unconfirmed, owner.refresh(a).phase)
      assertNull(owner.prepareSend(a))
      assertEquals(1, fake.patches.size)
    }

  @Test fun rejectedConflictUsesOriginalExpectedModeAndRequiresFreshChoice() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      owner.refresh(a)
      fake.mode = "workspace" // another client changed the saved field after our displayed read
      fake.onPatch = { throw GatewayRequestRejected(GatewaySession.ErrorShape("INVALID_REQUEST", "Session changed", rawDetailsJson = """{"reason":"session-changed"}""")) }
      assertFalse(owner.choose(a, SessionPermissionMode.Full))
      assertEquals(JsonPrimitive("guarded"), fake.patches.single().second["expectedPermissionMode"])
      assertEquals(SessionPermissionMode.Workspace, owner.state(a).savedMode)
      assertEquals(SessionPermissionMode.Workspace, owner.state(a).confirmedMode)
      assertEquals(SessionPermissionFailure.IdentityChanged, owner.state(a).failure)
      assertNull(owner.state(a).requestedMode)
      assertEquals(1, fake.patches.size)
    }

  @Test fun pendingReadAndStaleSocketCannotConfirmOrAffectAnotherChat() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      owner.refresh(a)
      fake.pending = true
      assertEquals(SessionPermissionPhase.Applying, owner.refresh(a).phase)
      assertNull(owner.prepareSend(a))
      fake.pending = false
      assertEquals(SessionPermissionPhase.Ready, owner.refresh(a).phase)
      owner.refresh(b)
      val release = CompletableDeferred<Unit>()
      fake.onRead = {
        val old = fake.row(a.key)
        release.await()
        old
      }
      val read = async { owner.refresh(a) }
      runCurrent()
      fake.connection = fake.connection!!.copy(generation = 2)
      owner.onConnectionChanged()
      release.complete(Unit)
      read.await()
      assertEquals(SessionPermissionPhase.Unavailable, owner.state(a).phase)
      assertEquals(SessionPermissionPhase.Unavailable, owner.state(b).phase)
    }

  @Test fun creationOrPatchCannotInventIdentityOrMode() {
    val codec = SessionPermissionsCodec()
    assertTrue(runCatching { codec.describe("""{"session":{"key":"agent:main:a","sessionId":"s","permissionMode":"admin"}}""") }.isFailure)
    assertTrue(runCatching { codec.describe("""{"session":{"key":"agent:main:a","sessionId":"s","permissionMode":true}}""") }.isFailure)
    assertTrue(runCatching { codec.mutation("""{"ok":true,"key":"agent:main:a"}""") }.isFailure)
    val ref = SessionPermissionRef(a, Fake().connection!!, "session-a", "revision-1")
    val params = Json.parseToJsonElement(codec.patchParams(ref, SessionPermissionMode.Default, SessionPermissionMode.Full)) as JsonObject
    assertEquals(JsonNull, params["expectedPermissionMode"])
    assertEquals(JsonPrimitive("revision-1"), params["expectedLifecycleRevision"])
    val clearParams = Json.parseToJsonElement(codec.patchParams(ref, SessionPermissionMode.Workspace, SessionPermissionMode.Default)) as JsonObject
    assertEquals(JsonPrimitive("workspace"), clearParams["expectedPermissionMode"])
    assertEquals(JsonNull, clearParams["permissionMode"])
    assertEquals(setOf("key"), (Json.parseToJsonElement(codec.describeParams(a)) as JsonObject).keys)
  }

  @Test fun cancellationAfterWriteDispatchCannotLeaveAnOptimisticallyRecoverableApplyingState() =
    runTest {
      val fake = Fake()
      val owner = SessionPermissionsController(this, fake)
      owner.refresh(a)
      val written = CompletableDeferred<Unit>()
      val blockRead = CompletableDeferred<Unit>()
      fake.onPatch = {
        fake.mode = "full"
        written.complete(Unit)
        fake.mutation(a.key)
      }
      fake.onRead = {
        blockRead.await()
        fake.row(a.key)
      }
      val write = async { owner.choose(a, SessionPermissionMode.Full) }
      written.await()
      write.cancel()
      write.join()
      fake.onRead = null
      assertEquals(SessionPermissionPhase.Unconfirmed, owner.refresh(a).phase)
      assertEquals(1, fake.patches.size)
    }
}
