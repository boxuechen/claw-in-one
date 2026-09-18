package ai.openclaw.app.clawhub

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawHubArtworkLoaderTest {
  @Test
  fun acceptsOnlyPinnedHttpsArtworkHostsByDefault() {
    assertTrue(isAllowedClawHubArtworkUrl("https://cdn.simpleicons.org/openclaw".toHttpUrl()))
    assertFalse(isAllowedClawHubArtworkUrl("http://cdn.simpleicons.org/openclaw".toHttpUrl()))
    assertFalse(isAllowedClawHubArtworkUrl("https://example.com/openclaw".toHttpUrl()))
    assertFalse(isAllowedClawHubArtworkUrl("https://user@cdn.simpleicons.org/openclaw".toHttpUrl()))
  }

  @Test
  fun cachesAValidatedArtworkPayloadInMemory() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(
          MockResponse()
            .setHeader("Content-Type", "image/svg+xml")
            .setBody("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"),
        )
        server.start()
        val loader =
          ClawHubArtworkLoader(
            httpClient = OkHttpClient(),
            urlPolicy = { true },
          )

        val first = loader.load(server.url("/icon").toString())
        val second = loader.load(server.url("/icon").toString())

        assertNotNull(first)
        assertEquals("image/svg+xml", first?.contentType)
        assertEquals(first?.bytes?.toList(), second?.bytes?.toList())
        assertEquals(1, server.requestCount)
      }
    }

  @Test
  fun rejectsUnexpectedContentAndOversizedBodies() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("no"))
        server.enqueue(
          MockResponse()
            .setHeader("Content-Type", "image/png")
            .setBody("x".repeat(512 * 1024 + 1)),
        )
        server.start()
        val loader =
          ClawHubArtworkLoader(
            httpClient = OkHttpClient(),
            urlPolicy = { true },
          )

        assertNull(loader.load(server.url("/wrong-type").toString()))
        assertNull(loader.load(server.url("/too-large").toString()))
      }
    }
}
