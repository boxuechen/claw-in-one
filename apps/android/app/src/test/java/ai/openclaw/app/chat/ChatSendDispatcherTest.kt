package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewayRequestOutcomeUnknown
import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendDispatcherTest {
  private class Harness {
    val json = Json { ignoreUnknownKeys = true }
    val owner = ChatComposerOwner("gateway", "alice", "main")
    val admissions = mutableListOf<Pair<ChatComposerOwner, Long>>()
    val frames = mutableListOf<JsonObject>()
    var requests = 0
    var allowed = true
    var beforeEnqueue: suspend () -> Unit = {}
    var response: suspend () -> String = { """{"runId":"canonical-run","status":"started"}""" }
    val dispatcher =
      ChatSendDispatcher(json) { owner, revision, enqueue ->
        admissions += owner to revision
        if (allowed) enqueue()
        allowed
      }
    val lease =
      GatewaySession.RequestLease("gateway") { method, params, timeout, enqueue ->
        requests++
        assertEquals("chat.send", method)
        assertEquals(15_000L, timeout)
        val captured = json.parseToJsonElement(requireNotNull(params)).jsonObject
        beforeEnqueue()
        enqueue { frames += captured }
        response()
      }

    fun request(attachments: List<OutgoingAttachment> = emptyList()) =
      ChatSendRequest(
        owner,
        sessionKey = "agent:alice:main",
        text = "hello\n你好",
        thinking = "high",
        idempotencyKey = "client-key",
        attachments = attachments,
      )

    suspend fun send(request: ChatSendRequest = request()) = dispatcher.send(request, lease, 7)
  }

  @Test
  fun oneCapturedRequestEncodesExactIdentityWithoutServerRunTimeout() =
    runTest {
      val h = Harness()
      val result = h.send() as ChatSendResult.Response
      assertEquals("canonical-run", result.ack.runId)
      assertEquals("started", result.ack.normalizedStatus)
      assertFalse(result.ack.isTerminal)
      assertEquals(1, h.requests)
      assertEquals(listOf(h.owner to 7L), h.admissions)
      assertEquals(
        mapOf(
          "sessionKey" to JsonPrimitive("agent:alice:main"),
          "agentId" to JsonPrimitive("alice"),
          "message" to JsonPrimitive("hello\n你好"),
          "thinking" to JsonPrimitive("high"),
          "idempotencyKey" to JsonPrimitive("client-key"),
        ),
        h.frames.single(),
      )
      // Admission uses the captured owner even when the durable wire key has been pinned.
      assertEquals(
        "main",
        h.admissions
          .single()
          .first.sessionKey,
      )
    }

  @Test
  fun attachmentListIsSnapshottedAndOnlyWireFieldsAreSent() =
    runTest {
      val h = Harness()
      val attachment = OutgoingAttachment("file", "application/pdf", "report.pdf", "AQID")
      val input = mutableListOf(attachment)
      val request = h.request(input)
      input.clear()
      h.send(request)
      assertEquals(listOf(attachment), request.attachments)
      assertEquals(
        JsonArray(
          listOf(
            JsonObject(
              mapOf(
                "type" to JsonPrimitive("file"),
                "mimeType" to JsonPrimitive("application/pdf"),
                "fileName" to JsonPrimitive("report.pdf"),
                "content" to JsonPrimitive("AQID"),
              ),
            ),
          ),
        ),
        h.frames.single()["attachments"],
      )
    }

  @Test
  fun missingCapturedConnectionNeverAcquiresAnotherLeaseOrEnqueues() =
    runTest {
      val h = Harness()
      assertEquals(ChatSendResult.NotEnqueued("Chat connection unavailable"), h.dispatcher.send(h.request(), null, 7))
      assertEquals(0, h.requests)
      assertTrue(h.admissions.isEmpty())
      assertTrue(h.frames.isEmpty())
    }

  @Test
  fun stopIsCheckedAtPhysicalEnqueueAfterTransportWaiting() =
    runTest {
      val h = Harness()
      val gate = CompletableDeferred<Unit>()
      h.beforeEnqueue = { gate.await() }
      val pending = async { h.send() }
      runCurrent()
      assertTrue(h.admissions.isEmpty())
      h.allowed = false
      gate.complete(Unit)
      assertEquals(ChatSendResult.NotEnqueued(OUTBOX_CHAT_STOPPED_ERROR), pending.await())
      assertEquals(listOf(h.owner to 7L), h.admissions)
      assertTrue(h.frames.isEmpty())
      assertEquals(1, h.requests)
    }

  @Test
  fun capturedLeaseRetirementDoesNotRecaptureOrRetry() =
    runTest {
      val h = Harness()
      h.beforeEnqueue = { throw GatewayRequestNotEnqueued("gateway request lease changed") }
      assertEquals(ChatSendResult.NotEnqueued("gateway request lease changed"), h.send())
      runCurrent()
      assertEquals(1, h.requests)
      assertTrue(h.frames.isEmpty())
      assertTrue(h.admissions.isEmpty())
    }

  @Test
  fun definitiveGatewayErrorIsTransmittedRejectionNotNonEnqueue() =
    runTest {
      val h = Harness()
      h.response = { throw GatewayRequestRejected(GatewaySession.ErrorShape("UNAVAILABLE", "cached run failed")) }
      assertEquals(ChatSendResult.Rejected("UNAVAILABLE: cached run failed"), h.send())
      assertEquals(1, h.frames.size)
      runCurrent()
      assertEquals(1, h.requests)
    }

  @Test
  fun lostResponseRemainsUnknownWithoutAutomaticSend() =
    runTest {
      val h = Harness()
      h.response = { throw GatewayRequestOutcomeUnknown("connection lost") }
      assertEquals(ChatSendResult.OutcomeUnknown, h.send())
      runCurrent()
      assertEquals(1, h.requests)
      assertEquals(1, h.frames.size)
    }

  @Test
  fun unexpectedTransportFailureCannotBecomeSafeToRetry() =
    runTest {
      val h = Harness()
      h.response = { error("unexpected failure") }
      assertEquals(ChatSendResult.Failed("unexpected failure"), h.send())
      runCurrent()
      assertEquals(1, h.requests)
      assertEquals(1, h.frames.size)
    }

  @Test
  fun cancelledInFlightRequestPropagatesInsteadOfBecomingAResponse() =
    runTest {
      val h = Harness()
      val reply = CompletableDeferred<String>()
      h.response = { reply.await() }
      val pending = async { h.send() }
      runCurrent()
      assertEquals(1, h.frames.size)
      pending.cancel()
      try {
        pending.await()
        error("Expected cancellation")
      } catch (_: CancellationException) {
        assertTrue(pending.isCancelled)
      }
      runCurrent()
      assertEquals(1, h.requests)
    }

  @Test
  fun malformedAndIncompleteAcksPreserveReceivedEvidenceWithoutInventingStatus() =
    runTest {
      val h = Harness()
      for (payload in listOf("not json", "{}", "null", "[]", """{"runId":42,"status":false}""")) {
        h.response = { payload }
        val result = h.send() as ChatSendResult.Response
        assertNull(result.ack.runId)
        assertNull(result.ack.status)
        assertFalse(result.ack.isTerminal)
      }
      assertEquals(5, h.requests)
    }

  @Test
  fun terminalAndInFlightStatusesRetainTheirDistinctAckSemantics() =
    runTest {
      val h = Harness()
      for (status in listOf("ok", "error", "timeout", "in_flight")) {
        h.response = { """{"runId":"actual-id","status":" $status "}""" }
        val ack = (h.send() as ChatSendResult.Response).ack
        assertEquals("actual-id", ack.runId)
        assertEquals(status, ack.normalizedStatus)
        assertEquals(status == "ok", ack.isTerminalSuccess)
        assertEquals(status == "error" || status == "timeout", ack.isTerminalFailure)
      }
    }

  @Test
  fun simultaneousRequestsKeepTheirOwnOwnerRevisionPayloadAndResponse() =
    runTest {
      val h = Harness()
      val gate = CompletableDeferred<Unit>()
      val a = ChatComposerOwner("gateway", "alice", "agent:alice:a")
      val b = ChatComposerOwner("gateway", "bob", "agent:bob:b")
      val lease =
        GatewaySession.RequestLease("gateway") { _, params, _, enqueue ->
          val key = h.json.parseToJsonElement(requireNotNull(params)).jsonObject["idempotencyKey"]
          if (key == JsonPrimitive("a")) gate.await()
          enqueue {}
          """{"runId":$key,"status":"started"}"""
        }

      fun request(
        owner: ChatComposerOwner,
        id: String,
      ) = ChatSendRequest(owner, owner.sessionKey, id, "off", id, emptyList())
      val first = async { h.dispatcher.send(request(a, "a"), lease, 4) }
      runCurrent()
      val second = h.dispatcher.send(request(b, "b"), lease, 9) as ChatSendResult.Response
      assertEquals("b", second.ack.runId)
      gate.complete(Unit)
      assertEquals("a", (first.await() as ChatSendResult.Response).ack.runId)
      assertEquals(listOf(b to 9L, a to 4L), h.admissions)
    }
}
