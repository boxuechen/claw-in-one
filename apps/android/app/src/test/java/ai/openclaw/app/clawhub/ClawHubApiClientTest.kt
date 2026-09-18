package ai.openclaw.app.clawhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ClawHubApiClientTest {
  @Test
  fun buildsEncodedAnonymousGetAndDecodesOptionalPackageFields() =
    withServer { server ->
      server.enqueue(
        jsonResponse(
          """
          {
            "items": [{
              "name": "@openclaw/whatsapp",
              "displayName": "WhatsApp",
              "isOfficial": true,
              "categories": ["channels"],
              "unknownFutureField": {"safe": true}
            }],
            "nextCursor": "cursor with / separators"
          }
          """.trimIndent(),
        ),
      )

      val result =
        client(server).get(
          ClawHubGetRequest(
            pathSegments = listOf("api", "v1", "plugins"),
            queryParameters =
              listOf(
                "sort" to "recommended",
                "isOfficial" to "true",
                "cursor" to "cursor with / separators",
              ),
          ),
        ) as ClawHubApiResult.Success

      val request = server.takeRequest()
      assertEquals("GET", request.method)
      assertEquals("application/json", request.getHeader("Accept"))
      assertEquals("Claw-In-One/test", request.getHeader("User-Agent"))
      assertNull(request.getHeader("Authorization"))
      assertEquals(
        "/api/v1/plugins?sort=recommended&isOfficial=true&cursor=cursor%20with%20%2F%20separators",
        request.path,
      )
      assertEquals(ClawHubResponseSource.Network, result.document.source)

      val page =
        ClawHubJson.decodeFromJsonElement(
          ClawHubPackagePageWire.serializer(),
          result.document.body,
        )
      assertEquals("cursor with / separators", page.nextCursor)
      assertEquals("@openclaw/whatsapp", page.items.single().name)
      assertTrue(page.items.single().isOfficial == true)
      assertNull(page.items.single().summary)
    }

  @Test
  fun reusesFreshMemoryResponseAndExpiresItWithoutPersistingCatalogState() =
    withServer { server ->
      server.enqueue(jsonResponse("""{"items":[{"name":"first"}]}"""))
      server.enqueue(jsonResponse("""{"items":[{"name":"second"}]}"""))
      var now = 10L
      val cache =
        ClawHubMemoryCache(
          maxEntries = 2,
          maxAgeMillis = 50,
          monotonicMillis = { now },
        )
      val client = client(server, cache)
      val request = ClawHubGetRequest(listOf("api", "v1", "plugins"))

      val first = client.get(request) as ClawHubApiResult.Success
      val cached = client.get(request) as ClawHubApiResult.Success
      now = 61L
      val refreshed = client.get(request) as ClawHubApiResult.Success

      assertEquals(ClawHubResponseSource.Network, first.document.source)
      assertEquals(ClawHubResponseSource.Memory, cached.document.source)
      assertEquals(ClawHubResponseSource.Network, refreshed.document.source)
      assertEquals(2, server.requestCount)
    }

  @Test
  fun reportsRateLimitWithSecondsOrHttpDateWithoutAutomaticRetry() =
    withServer { server ->
      server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12"))
      server.enqueue(
        MockResponse()
          .setResponseCode(429)
          .setHeader("Retry-After", "Thu, 01 Jan 1970 00:16:45 GMT"),
      )
      val client =
        OkHttpClawHubApiClient(
          httpClient = OkHttpClient(),
          baseUrl = server.url("/"),
          userAgent = "Claw-In-One/test",
          nowEpochMillis = { 1_000_000L },
        )

      val seconds = client.get(ClawHubGetRequest(listOf("seconds"))) as ClawHubApiResult.Failure
      val date = client.get(ClawHubGetRequest(listOf("date"))) as ClawHubApiResult.Failure

      assertEquals(12L, (seconds.error as ClawHubApiError.RateLimited).retryAfterSeconds)
      assertEquals(5L, (date.error as ClawHubApiError.RateLimited).retryAfterSeconds)
      assertEquals(2, server.requestCount)
    }

  @Test
  fun boundsResponsesAndSanitizesPlainTextHttpErrors() =
    withServer { server ->
      server.enqueue(
        MockResponse()
          .setResponseCode(503)
          .setBody(" unavailable\nplease retry "),
      )
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "application/json")
          .setBody("x".repeat(2 * 1024 * 1024 + 1)),
      )
      val client = client(server)

      val unavailable = client.get(ClawHubGetRequest(listOf("unavailable"))) as ClawHubApiResult.Failure
      val oversized = client.get(ClawHubGetRequest(listOf("oversized"))) as ClawHubApiResult.Failure

      assertEquals(
        ClawHubApiError.Http(statusCode = 503, safeMessage = "unavailable please retry"),
        unavailable.error,
      )
      assertEquals(ClawHubApiError.ResponseTooLarge, oversized.error)
    }

  @Test
  fun rejectsNonObjectAndNonJsonSuccessResponses() =
    withServer { server ->
      server.enqueue(jsonResponse("[]"))
      server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("{}"))
      val client = client(server)

      val array = client.get(ClawHubGetRequest(listOf("array"))) as ClawHubApiResult.Failure
      val html = client.get(ClawHubGetRequest(listOf("html"))) as ClawHubApiResult.Failure

      assertEquals(ClawHubApiError.InvalidResponse, array.error)
      assertEquals(ClawHubApiError.InvalidResponse, html.error)
    }

  @Test
  fun coroutineCancellationCancelsAnActiveCall() =
    withServer { server ->
      coroutineScope {
        server.enqueue(
          jsonResponse("{}")
            .setHeadersDelay(30, TimeUnit.SECONDS),
        )
        val request = async { client(server).get(ClawHubGetRequest(listOf("slow"))) }
        assertTrue(withContext(Dispatchers.IO) { server.takeRequest(1, TimeUnit.SECONDS) } != null)
        delay(100)

        withTimeout(1_000) {
          request.cancelAndJoin()
        }
        assertTrue(request.isCancelled)
        assertFalse(request.isCompleted && !request.isCancelled)
      }
    }

  private fun client(
    server: MockWebServer,
    cache: ClawHubMemoryCache = ClawHubMemoryCache(),
  ): OkHttpClawHubApiClient =
    OkHttpClawHubApiClient(
      httpClient = OkHttpClient(),
      baseUrl = server.url("/"),
      userAgent = "Claw-In-One/test",
      memoryCache = cache,
    )

  private fun jsonResponse(body: String): MockResponse =
    MockResponse()
      .setHeader("Content-Type", "application/json")
      .setBody(body)

  private fun withServer(block: suspend (MockWebServer) -> Unit) =
    runBlocking {
      MockWebServer().use { server ->
        server.start()
        block(server)
      }
    }
}
