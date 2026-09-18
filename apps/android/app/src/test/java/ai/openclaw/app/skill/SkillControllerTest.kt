package ai.openclaw.app.skill

import ai.openclaw.app.CLAWHUB_INSTALL_REQUEST_TIMEOUT_MS
import ai.openclaw.app.extensions.ExtensionGatewayConnection
import ai.openclaw.app.extensions.ExtensionGatewayTransport
import ai.openclaw.app.gateway.GatewayErrorDetails
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SkillControllerTest {
  @Test
  fun installPreservesGatewayMessageAndAuditWarning() =
    runTest {
      val transport = FakeTransport(this)
      val controller = controller(transport)
      transport.handler = { method, params ->
        when (method) {
          "skills.install" -> {
            assertEquals(
              Json.parseToJsonElement(
                """{"source":"clawhub","slug":"@alice/alpha","version":"1.2.3","timeoutMs":120000}""",
              ),
              Json.parseToJsonElement(params),
            )
            """{"message":"Installed @alice/alpha.","warning":"  ClawHub audit details.\n "}"""
          }
          "skills.status" -> skillsStatus(installed = false)
          else -> error("unexpected method $method")
        }
      }

      controller.feature.actions.installClawHub("@alice/alpha", "1.2.3")
      advanceUntilIdle()

      assertEquals(CLAWHUB_INSTALL_REQUEST_TIMEOUT_MS, transport.timeouts["skills.install"])
      assertEquals(
        "Installed @alice/alpha.\n\nClawHub audit details.",
        controller.state.value.clawHub.messageText,
      )
      assertNull(controller.state.value.clawHub.errorText)
      assertTrue(
        controller.state.value.clawHub.installingSlugs
          .isEmpty(),
      )
    }

  @Test
  fun rejectionPreservesGatewayAuditWarning() =
    runTest {
      val transport = FakeTransport(this)
      val controller = controller(transport)
      transport.handler = { method, _ ->
        when (method) {
          "skills.install" ->
            throw GatewayRequestRejected(
              GatewaySession.ErrorShape(
                code = "UNAVAILABLE",
                message = "Blocked by ClawHub.",
                details =
                  GatewayErrorDetails(
                    code = null,
                    canRetryWithDeviceToken = false,
                    recommendedNextStep = null,
                    clawhubWarning = "  ClawHub audit details.\n ",
                  ),
              ),
            )
          "skills.status" -> skillsStatus(installed = false)
          else -> error("unexpected method $method")
        }
      }

      controller.feature.actions.installClawHub("@alice/alpha", "1.2.3")
      advanceUntilIdle()

      assertEquals(
        "Blocked by ClawHub.\n\nClawHub audit details.",
        controller.state.value.clawHub.errorText,
      )
      assertNull(controller.state.value.clawHub.messageText)
    }

  @Test
  fun unknownOutcomeUsesReadbackAndNeverRetriesWrite() =
    runTest {
      val transport = FakeTransport(this)
      val controller = controller(transport)
      val installs = AtomicInteger()
      transport.handler = { method, _ ->
        when (method) {
          "skills.install" -> {
            installs.incrementAndGet()
            throw GatewayRequestOutcomeUnknown("response lost")
          }
          "skills.status" -> skillsStatus(installed = true)
          else -> error("unexpected method $method")
        }
      }

      controller.feature.actions.installClawHub("registry-slug", "1.2.3")
      advanceUntilIdle()

      assertEquals(1, installs.get())
      assertEquals("Installed registry-slug.", controller.state.value.clawHub.messageText)
      assertFalse(
        controller.state.value.clawHub.errorText
          .orEmpty()
          .contains("unknown"),
      )
    }

  @Test
  fun replacementConnectionCannotPublishInFlightInstall() =
    runTest {
      val transport = FakeTransport(this)
      val controller = controller(transport)
      val requestStarted = CompletableDeferred<Unit>()
      val releaseRequest = CompletableDeferred<Unit>()
      transport.handler = { method, _ ->
        check(method == "skills.install")
        requestStarted.complete(Unit)
        releaseRequest.await()
        """{"message":"Installed stale skill."}"""
      }

      controller.feature.actions.installClawHub("registry-slug", "1.2.3")
      runCurrent()
      requestStarted.await()
      transport.connection = transport.connection?.copy(catalogRevision = 2)
      controller.onConnectionChanged()
      releaseRequest.complete(Unit)
      advanceUntilIdle()

      assertEquals(SkillState(connected = true, adminScope = true, installMethodsAvailable = true), controller.state.value)
    }

  private fun controller(transport: FakeTransport): SkillController =
    SkillController(
      scope = transport.scope,
      transport = transport,
      json = Json { ignoreUnknownKeys = true },
    ).also(SkillController::onConnectionChanged)

  private class FakeTransport(
    val scope: CoroutineScope,
  ) : ExtensionGatewayTransport {
    var connection: ExtensionGatewayConnection? =
      ExtensionGatewayConnection(
        stableId = "gateway",
        generation = 1,
        catalogRevision = 1,
        methods = setOf("skills.status", "skills.update", "skills.search", "skills.detail", "skills.install"),
        adminScope = true,
      )
    val timeouts = mutableMapOf<String, Long>()
    var handler: suspend (String, String) -> String = { _, _ -> error("handler not set") }

    override fun capture(): ExtensionGatewayConnection? = connection

    override fun publish(
      connection: ExtensionGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (this.connection != connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: ExtensionGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      check(this.connection == connection)
      timeouts[method] = timeoutMs
      return handler(method, params)
    }
  }

  private fun skillsStatus(installed: Boolean): String =
    if (!installed) {
      """{"managedSkillsDir":"/tmp/skills","skills":[]}"""
    } else {
      """{"managedSkillsDir":"/tmp/skills","skills":[{"skillKey":"custom-key",
        "name":"Installed skill","source":"openclaw-managed","disabled":false,"eligible":true,
        "blockedByAllowlist":false,"blockedByAgentFilter":false,"bundled":false,
        "clawhub":{"status":"linked","valid":true,"slug":"registry-slug",
        "installedVersion":"1.2.3"}}]}"""
    }
}
