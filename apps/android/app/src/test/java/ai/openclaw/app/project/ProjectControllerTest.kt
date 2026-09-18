package ai.openclaw.app.project

import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectControllerTest {
  @Test fun composerCommitCreatesOneProjectBindsWorkspaceAndActivatesExactlyOnce() =
    runTest {
      val harness = Harness(this)
      assertTrue(
        harness.controller.feature.actions
          .newProject(),
      )
      assertTrue(
        harness.controller.feature.actions
          .requestComposer(),
      )
      val editing = harness.controller.feature.destination.value.dialog as ProjectNameDialogState.Editing
      assertEquals("New Project", editing.value)

      harness.controller.feature.actions
        .confirmName()
      harness.controller.feature.actions
        .confirmName()
      runCurrent()

      assertEquals(
        listOf(PROJECT_CREATE_METHOD, PROJECT_CATALOG_METHOD, PROJECT_CAPABILITIES_METHOD),
        harness.transport.calls.map { it.first },
      )
      assertEquals(listOf("session-local" to "project-1"), harness.bindings)
      assertEquals(listOf("session-local"), harness.opened)
      val activation = requireNotNull(harness.controller.feature.destination.value.activation)
      assertEquals("session-local", activation.sessionKey)
      harness.controller.feature.actions
        .consumeActivation(activation.id)
      assertEquals(null, harness.controller.feature.destination.value.activation)
    }

  @Test fun customConflictStaysInTheSameDialogWithoutBindingAChat() =
    runTest {
      val harness = Harness(this)
      harness.transport.createFailure =
        GatewayRequestRejected(GatewaySession.ErrorShape("name_conflict", "Name already exists"))
      harness.controller.feature.actions
        .newProject()
      harness.controller.feature.actions
        .requestComposer()
      harness.controller.feature.actions
        .updateName("Tiny Memo")
      harness.controller.feature.actions
        .confirmName()
      runCurrent()

      val editing = harness.controller.feature.destination.value.dialog as ProjectNameDialogState.Editing
      assertEquals("Tiny Memo", editing.value)
      assertEquals(ProjectNameOrigin.Custom, editing.origin)
      assertEquals("Name already exists. Choose another.", editing.inlineError)
      assertTrue(editing.errorRevision > 0)
      assertTrue(harness.bindings.isEmpty())
      assertTrue(harness.opened.isEmpty())
    }

  @Test fun unknownCreateOutcomeReadsBackTheSameIntentInsteadOfCreatingAgain() =
    runTest {
      val harness = Harness(this)
      harness.transport.createFailure = GatewayRequestOutcomeUnknown("ack lost")
      harness.controller.feature.actions
        .newProject()
      harness.controller.feature.actions
        .requestComposer()
      harness.controller.feature.actions
        .confirmName()
      runCurrent()

      assertEquals(
        listOf(PROJECT_CREATE_METHOD, PROJECT_READ_METHOD, PROJECT_CATALOG_METHOD, PROJECT_CAPABILITIES_METHOD),
        harness.transport.calls.map { it.first },
      )
      assertEquals(1, harness.bindings.size)
      assertTrue(harness.controller.feature.destination.value.activation != null)
    }

  @Test fun confirmedProjectRetriesOnlyTheMissingWorkspaceChatSetup() =
    runTest {
      val harness = Harness(this)
      harness.allowBinding = false
      harness.controller.feature.actions
        .newProject()
      harness.controller.feature.actions
        .requestComposer()
      harness.controller.feature.actions
        .confirmName()
      runCurrent()
      assertTrue(harness.controller.feature.destination.value.dialog is ProjectNameDialogState.FinishSetup)
      assertTrue(harness.opened.isEmpty())

      harness.allowBinding = true
      harness.controller.feature.actions
        .finishSetup()
      assertEquals(listOf("session-local"), harness.opened)
      assertTrue(harness.controller.feature.destination.value.dialog is ProjectNameDialogState.Hidden)
      assertEquals(1, harness.transport.calls.count { it.first == PROJECT_CREATE_METHOD })
    }

  @Test fun validationAndDefaultAllocationUseCanonicalProjectRules() {
    assertEquals("New Project 3", allocateDefaultName(listOf(" new project ", "NEW PROJECT 2")))
    assertTrue(validateProjectName("bad/name") != null)
    assertTrue(validateProjectName("\u0000") != null)
    assertEquals(null, validateProjectName(" 示例项目 "))
  }

  @Test fun competingChatIsNotAdmittedAndCanReturnToTheExactOwner() =
    runTest {
      val harness = Harness(this)
      harness.controller.feature.actions
        .refreshCatalog()
      runCurrent()
      harness.controller.feature.actions
        .newChat("project-1")
      harness.transport.lease =
        """{"lease":{"projectId":"project-1","sessionKey":"agent:main:owner","sessionId":"owner-session","runId":"owner-run","generation":7}}"""
      assertFalse(harness.controller.admitRunForSession("session-local"))
      assertEquals(
        "agent:main:owner",
        harness.controller.feature.destination.value.runConflict
          ?.ownerSessionKey,
      )
      harness.controller.feature.actions
        .returnToRunOwner()
      assertEquals(listOf("session-local", "agent:main:owner"), harness.opened)
    }

  private class Harness(
    scope: kotlinx.coroutines.CoroutineScope,
  ) {
    val transport = FakeTransport()
    val bindings = mutableListOf<Pair<String, String>>()
    val opened = mutableListOf<String>()
    var allowBinding = true
    val controller =
      ProjectController(
        scope = scope,
        transport = transport,
        createLocalDraft = { _, _ -> "session-local" },
        bindLocalDraft = { sessionKey, projectId, _ ->
          bindings += sessionKey to projectId
          allowBinding
        },
        openSession = { sessionKey, _ -> opened += sessionKey },
        newId = generateSequence(1) { it + 1 }.map { "identifier-$it" }.iterator()::next,
      )
  }

  private class FakeTransport : ProjectTransport {
    val calls = mutableListOf<Pair<String, String>>()
    var createFailure: Throwable? = null
    var lease = """{"lease":null}"""
    private var intentId = "intent"
    private var nameRevision = "revision"

    override fun capture() =
      ProjectConnection(
        gatewayId = "gateway",
        generation = 1,
        methods =
          setOf(
            PROJECT_CREATE_METHOD,
            PROJECT_READ_METHOD,
            PROJECT_CATALOG_METHOD,
            PROJECT_CAPABILITIES_METHOD,
            PROJECT_RUN_LEASE_STATUS_METHOD,
          ),
        admin = true,
      )

    override suspend fun request(
      connection: ProjectConnection,
      method: String,
      params: String,
    ): String {
      calls += method to params
      if (method == PROJECT_CREATE_METHOD) {
        val request = Json.parseToJsonElement(params).jsonObject
        intentId = request.getValue("intentId").jsonPrimitive.content
        nameRevision = request.getValue("nameRevision").jsonPrimitive.content
        createFailure?.let { throw it }
      }
      return when (method) {
        PROJECT_CREATE_METHOD,
        PROJECT_READ_METHOD,
        -> creationJson()
        PROJECT_CATALOG_METHOD -> """{"revision":1,"projects":[$projectJson],"sessionBindings":{}}"""
        PROJECT_CAPABILITIES_METHOD ->
          """{"revision":"android-kotlin-compose-v1:1|flutter:none","readyCapabilities":["android_kotlin"],"profiles":{}}"""
        PROJECT_RUN_LEASE_STATUS_METHOD -> lease
        else -> error("Unexpected $method")
      }
    }

    private val projectJson =
      """{"id":"project-1","displayName":"New Project","repoRoot":"/workspace/project-1","source":"registered","agentId":"main"}"""

    private fun creationJson() = """{"intentId":"$intentId","nameRevision":"$nameRevision","phase":"registered","naming":"default","displayName":"New Project","repoRoot":"/workspace/project-1","project":$projectJson}"""
  }
}
