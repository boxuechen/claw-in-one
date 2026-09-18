package ai.openclaw.app.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val TEST_TIMEOUT_MS = 8_000L
private const val CONNECT_CHALLENGE_FRAME =
  """{"type":"event","event":"connect.challenge","payload":{"nonce":"android-test-nonce","ts":1700000000123}}"""

private class NoopDeviceAuthStore : DeviceAuthTokenStore {
  override fun loadEntry(
    gatewayId: String,
    deviceId: String,
    role: String,
  ): DeviceAuthEntry? = null

  override fun saveToken(
    gatewayId: String,
    deviceId: String,
    role: String,
    token: String,
    scopes: List<String>,
    replacesStoredToken: String?,
  ) = true

  override fun clearToken(
    gatewayId: String,
    deviceId: String,
    role: String,
    onlyIfToken: String?,
  ) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewaySessionImageArtifactTest {
  @Test
  fun managedImageDownloadUsesTicketWithoutGatewayBearer() =
    runBlocking {
      for (contextPath in listOf("", "/tenant/gw", "/tenant%2Fgw", "//tenant/gw")) {
        assertManagedImageDownload(contextPath)
      }
    }

  private suspend fun assertManagedImageDownload(contextPath: String) {
    val app = RuntimeEnvironment.getApplication()
    val json = Json { ignoreUnknownKeys = true }
    val connected = CompletableDeferred<Unit>()
    val imageRequest = CompletableDeferred<RecordedRequest>()
    val imageBytes = byteArrayOf(1, 2, 3, 4)
    val attachmentId = "11111111-1111-4111-8111-111111111111"
    val artifactId = "artifact_managed_image_$attachmentId"
    val imagePath = "/api/chat/media/outgoing/main/$attachmentId/full?mediaTicket=ticket"
    val server =
      MockWebServer().apply {
        dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
              if (request.path == "$contextPath$imagePath") {
                imageRequest.complete(request)
                return MockResponse()
                  .setHeader("Content-Type", "image/png")
                  .setBody(Buffer().write(imageBytes))
              }
              if (request.path != contextPath.ifEmpty { "/" }) return MockResponse().setResponseCode(404)
              return MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                  override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                  ) {
                    webSocket.send(CONNECT_CHALLENGE_FRAME)
                  }

                  override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                  ) {
                    val frame = json.parseToJsonElement(text).jsonObject
                    if (frame["type"]?.jsonPrimitive?.content != "req") return
                    val id = frame["id"]?.jsonPrimitive?.content ?: return
                    when (frame["method"]?.jsonPrimitive?.content) {
                      "connect" ->
                        webSocket.send(
                          """{"type":"res","id":"$id","ok":true,"payload":{"snapshot":{"sessionDefaults":{"mainSessionKey":"main"}}}}""",
                        )
                      "artifacts.download" ->
                        webSocket.send(
                          """{"type":"res","id":"$id","ok":true,"payload":{"artifact":{"id":"$artifactId","type":"image","mimeType":"image/png"},"url":"$imagePath"}}""",
                        )
                    }
                  }
                },
              )
            }
          }
        start()
      }
    val stableId = "test|127.0.0.1|${server.port}"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session =
      GatewaySession(
        scope = scope,
        identityStore = testDeviceIdentityStore(app),
        deviceAuthStore = NoopDeviceAuthStore(),
        onConnected = { connected.complete(Unit) },
        onDisconnected = {},
        onEvent = { _, _ -> },
      )

    try {
      session.connect(
        endpoint = GatewayEndpoint(stableId, "test", "127.0.0.1", server.port, tlsEnabled = false, contextPath = contextPath),
        token = "bootstrap-token",
        bootstrapToken = null,
        password = null,
        options =
          GatewayConnectOptions(
            role = "operator",
            scopes = listOf("operator.read"),
            caps = emptyList(),
            commands = emptyList(),
            permissions = emptyMap(),
            client = GatewayClientInfo("openclaw-android-test", "Android Test", "1.0.0-test", "android", "ui", "instance", "android", "test"),
          ),
      )
      withTimeout(TEST_TIMEOUT_MS) { connected.await() }

      val loaded = session.loadImageArtifact(stableId, "main", "main", artifactId)

      assertArrayEquals(imageBytes, loaded?.bytes)
      assertEquals("image/png", loaded?.mimeType)
      val request = withTimeout(TEST_TIMEOUT_MS) { imageRequest.await() }
      assertNull(request.getHeader("Authorization"))
      assertEquals("image/*", request.getHeader("Accept"))
    } finally {
      session.disconnectAndJoin()
      scope.cancel()
      server.shutdown()
    }
  }
}
