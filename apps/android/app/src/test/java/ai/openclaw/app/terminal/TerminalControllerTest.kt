package ai.openclaw.app.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {
  @Test
  fun sharedPageCredentialNeedsNoSetupCode() =
    runTest {
      val transport = FakeTransport(connection(), admin = false)
      val controller = controller(transport)
      val page = GatewayControlPage("https://127.0.0.1", "shared", null, "pin")
      controller.replacePage(page)

      val access = controller.feature.actions.prepareAccess()

      assertEquals(page, access.page)
      assertEquals(0, transport.requests.get())
    }

  @Test
  fun deviceSetupCodeProducesEphemeralAccessWithoutChangingPage() =
    runTest {
      val transport = FakeTransport(connection(), admin = true)
      transport.response = setupResponse("""{"url":"wss://127.0.0.1","token":"temporary-token","bootstrapToken":"bootstrap-token"}""")
      val controller = controller(transport)
      val page = GatewayControlPage("https://127.0.0.1", null, null, "pin")
      controller.replacePage(page)

      val access = controller.feature.actions.prepareAccess()

      assertEquals("temporary-token", access.page.token)
      assertEquals("bootstrap-token", access.bootstrapToken)
      assertEquals(page, controller.page.value)
      assertEquals(1, transport.requests.get())
    }

  @Test
  fun adminScopeIsRequiredForDeviceSetupCode() =
    runTest {
      val controller = controller(FakeTransport(connection(), admin = false))
      controller.replacePage(GatewayControlPage("https://127.0.0.1", null, null, null))

      val result = runCatching { controller.feature.actions.prepareAccess() }

      assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

  @Test
  fun connectionReplacementCancelsLateSetupCode() =
    runTest {
      val response = CompletableDeferred<String>()
      val transport = FakeTransport(connection("gateway-a"), admin = true)
      transport.responseProvider = { response.await() }
      val controller = controller(transport)
      controller.replacePage(GatewayControlPage("https://127.0.0.1", null, null, null))

      val pending = async { runCatching { controller.feature.actions.prepareAccess() } }
      runCurrent()
      transport.connection = connection("gateway-b")
      response.complete(setupResponse("""{"url":"wss://127.0.0.1","token":"late-token"}"""))

      assertTrue(pending.await().isFailure)
    }

  @Test
  fun stateHidesPageWhileDisconnectedAndReconnectIsExplicit() =
    runTest {
      val connected = MutableStateFlow(false)
      val admin = MutableStateFlow(false)
      var reconnects = 0
      val controller =
        TerminalController(
          scope = backgroundScope,
          connected = connected,
          adminScope = admin,
          transport = FakeTransport(null, admin = false),
          reconnect = { reconnects++ },
        )
      controller.replacePage(GatewayControlPage("https://127.0.0.1", "token", null, null))
      runCurrent()

      assertNull(controller.state.value.page)
      controller.feature.actions.reconnect()
      assertEquals(1, reconnects)
    }

  private fun kotlinx.coroutines.test.TestScope.controller(transport: FakeTransport): TerminalController {
    val connected = MutableStateFlow(transport.connection != null)
    val admin = MutableStateFlow(transport.admin)
    return TerminalController(backgroundScope, connected, admin, transport, reconnect = {})
  }

  private fun connection(stableId: String = "gateway") = TerminalGatewayConnection(stableId, 1)

  private fun setupResponse(payload: String): String {
    val code = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
    return """{"setupCode":"$code"}"""
  }

  private class FakeTransport(
    var connection: TerminalGatewayConnection?,
    var admin: Boolean,
  ) : TerminalGatewayTransport {
    val requests = AtomicInteger()
    var response = ""
    var responseProvider: suspend () -> String = { response }

    override fun capture(): TerminalGatewayConnection? = connection

    override fun hasAdminScope(): Boolean = admin

    override suspend fun requestSetupCode(connection: TerminalGatewayConnection): String {
      check(this.connection == connection)
      requests.incrementAndGet()
      return responseProvider()
    }
  }
}
