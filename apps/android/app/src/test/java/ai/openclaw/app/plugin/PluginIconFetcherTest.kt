package ai.openclaw.app.plugin

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PluginIconFetcherTest {
  @Test
  fun retriesAuthorizedGatewayRouteWithoutExposingCredentialInUrl() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
          MockResponse()
            .setHeader("Content-Type", "image/png")
            .setBody("bounded-image"),
        )
        server.start()

        val payload =
          PluginIconFetcher().fetch(
            GatewayPluginIconAccess(
              baseUrl = server.url("/control").toString(),
              authCandidates = listOf("stale", "fresh"),
              tlsFingerprintSha256 = null,
            ),
            "@scope/plugin",
          )

        assertNotNull(payload)
        assertEquals("image/png", payload?.contentType)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer stale", first.getHeader("Authorization"))
        assertEquals("Bearer fresh", second.getHeader("Authorization"))
        assertEquals(first.path, second.path)
        checkNotNull(first.path).also { path ->
          assertEquals("/control/__openclaw__/plugin-icon/%40scope%2Fplugin", path)
          assertEquals(false, path.contains("stale"))
          assertEquals(false, path.contains("fresh"))
        }
      }
    }

  @Test
  fun rejectsNonImageResponse() =
    runTest {
      MockWebServer().use { server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("not an icon"))
        server.start()

        val payload =
          PluginIconFetcher().fetch(
            GatewayPluginIconAccess(
              baseUrl = server.url("/").toString(),
              authCandidates = emptyList(),
              tlsFingerprintSha256 = null,
            ),
            "plugin",
          )

        assertNull(payload)
      }
    }
}
