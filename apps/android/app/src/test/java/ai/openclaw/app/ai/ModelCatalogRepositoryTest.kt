package ai.openclaw.app.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCatalogRepositoryTest {
  @Test
  fun incompatibleGatewayIsExplicitAndNeverQueried() =
    runTest {
      val transport = FakeTransport(AiGatewayConnection("gateway", 1, 1, emptySet(), "main", true))
      val repository = ModelCatalogRepository(this, transport, Json)

      repository.onConnectionChanged()
      advanceUntilIdle()

      val status = repository.state.value.status as ModelCatalogStatus.Incompatible
      assertEquals(requiredAiGatewayMethods, status.missingMethods)
      assertTrue(transport.requests.isEmpty())
    }

  @Test
  fun compatibleGatewayPublishesTypedCatalog() =
    runTest {
      val transport = FakeTransport(connection())
      val repository = ModelCatalogRepository(this, transport, Json)

      repository.onConnectionChanged()
      advanceUntilIdle()

      val ready = repository.state.value.status as ModelCatalogStatus.Ready
      assertEquals(
        "deepseek/chat",
        ready.snapshot.models
          .single()
          .id,
      )
      assertEquals(listOf("models.list"), transport.requests.map { it.first })
    }

  @Test
  fun connectionGenerationFencesAnOlderResponse() =
    runTest {
      val oldReply = CompletableDeferred<String>()
      val transport = FakeTransport(connection())
      transport.respond = { oldReply.await() }
      val repository = ModelCatalogRepository(this, transport, Json)

      repository.onConnectionChanged()
      runCurrent()
      transport.connection = connection(generation = 2)
      transport.respond = { catalog("deepseek/new") }
      repository.onConnectionChanged()
      advanceUntilIdle()
      oldReply.complete(catalog("deepseek/old"))
      advanceUntilIdle()

      val ready = repository.state.value.status as ModelCatalogStatus.Ready
      assertEquals(
        "deepseek/new",
        ready.snapshot.models
          .single()
          .id,
      )
    }

  @Test
  fun selectedChatScopesTheModelCatalog() =
    runTest {
      val transport = FakeTransport(connection())
      transport.respond = {
        """{"models":[{"id":"openai/gpt","name":"GPT","provider":"openai","available":true,"reasoning":true}],"providerOutcomes":[]}"""
      }
      val repository = ModelCatalogRepository(this, transport, Json)
      repository.selectSession("agent:main:chat-1")
      repository.onConnectionChanged()
      advanceUntilIdle()

      val request = transport.requests.single().second
      assertTrue(request.contains("\"sessionKey\":\"agent:main:chat-1\""))
      assertTrue(request.contains("\"view\":\"configured\""))
      assertFalse(request.contains("\"agentId\""))
    }

  private class FakeTransport(
    var connection: AiGatewayConnection?,
  ) : AiGatewayTransport {
    val requests = mutableListOf<Pair<String, String>>()
    var respond: suspend (String) -> String = { catalog("deepseek/chat") }

    override fun capture(): AiGatewayConnection? = connection

    override fun publish(
      connection: AiGatewayConnection,
      block: () -> Unit,
    ): Boolean {
      if (connection != this.connection) return false
      block()
      return true
    }

    override suspend fun request(
      connection: AiGatewayConnection,
      method: String,
      params: String,
      timeoutMs: Long,
    ): String {
      requests += method to params
      return respond(params)
    }
  }

  companion object {
    private fun connection(generation: Long = 1) =
      AiGatewayConnection(
        stableId = "gateway",
        generation = generation,
        catalogRevision = 1,
        methods = requiredAiGatewayMethods,
        defaultAgentId = "main",
        adminScope = true,
      )

    private fun catalog(id: String) = """{"models":[{"id":"$id","name":"Chat","provider":"deepseek","available":true,"reasoning":false}],"refreshFailed":false,"providerOutcomes":[]}"""
  }
}
