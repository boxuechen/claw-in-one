package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewayClientInfo
import ai.openclaw.app.gateway.GatewayRequestNotEnqueued
import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodePresenceTest {
  private data class Call(
    val connection: GatewaySession.RequestLease,
    val params: String,
  )

  private class Fixture(
    scope: CoroutineScope,
  ) {
    var current: GatewaySession.RequestLease? = null
    var now = 1_000L
    var name = "Pixel"
    var metadataReads = 0
    val calls = mutableListOf<Call>()
    val unhandled = mutableListOf<String>()
    val owner =
      NodePresence(
        scope,
        captureConnection = { current },
        clientInfo = {
          metadataReads++
          GatewayClientInfo("android", name, "test-version", "android", "node", null, "android", "Pixel 8")
        },
        platform = { "Android test" },
        nowMs = { now },
        onUnhandled = unhandled::add,
      )

    fun connect(reply: suspend () -> String = { """{"handled":true}""" }): GatewaySession.RequestLease {
      lateinit var lease: GatewaySession.RequestLease
      lease =
        GatewaySession.RequestLease("gateway", isCurrentImpl = { current === lease }) { method, params, timeout, _ ->
          if (current !== lease) throw GatewayRequestNotEnqueued("retired")
          assertEquals("node.event", method)
          assertEquals(8_000L, timeout)
          calls += Call(lease, requireNotNull(params))
          reply()
        }
      current = lease
      return lease
    }
  }

  @Test
  fun offlineDoesNotReadMetadataOrScheduleRequests() =
    runTest {
      val fixture = Fixture(backgroundScope)
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Connect)
      runCurrent()
      assertEquals(0, fixture.metadataReads)
      assertTrue(fixture.calls.isEmpty())
    }

  @Test
  fun queuedNotificationCannotAdoptReplacementSocket() =
    runTest {
      val fixture = Fixture(backgroundScope)
      fixture.connect()
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background)
      val replacement = fixture.connect()
      runCurrent()
      assertTrue(fixture.calls.isEmpty())
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Connect)
      runCurrent()
      assertEquals(listOf(replacement), fixture.calls.map { it.connection })
    }

  @Test
  fun payloadUsesCapturedClientMetadataAndCanonicalNodeEnvelope() =
    runTest {
      val fixture = Fixture(backgroundScope)
      fixture.connect()
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Connect)
      fixture.name = "Changed after capture"
      runCurrent()
      val envelope = Json.parseToJsonElement(fixture.calls.single().params).jsonObject
      assertEquals(setOf("event", "payloadJSON"), envelope.keys)
      assertEquals(NodePresenceAliveBeacon.EVENT_NAME, envelope["event"]?.jsonPrimitive?.content)
      val payload = Json.parseToJsonElement(envelope.getValue("payloadJSON").jsonPrimitive.content).jsonObject
      assertEquals("Pixel", payload["displayName"]?.jsonPrimitive?.content)
      assertEquals("connect", payload["trigger"]?.jsonPrimitive?.content)
      assertEquals("1000", payload["sentAtMs"]?.jsonPrimitive?.content)
    }

  @Test
  fun recentSuccessOnlyThrottlesTheSamePhysicalConnection() =
    runTest {
      val fixture = Fixture(backgroundScope)
      fixture.connect()
      repeat(3) { fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true) }
      runCurrent()
      assertEquals(1, fixture.calls.size)
      fixture.now += NodePresenceAliveBeacon.MIN_SUCCESS_INTERVAL_MS
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
      runCurrent()
      assertEquals(2, fixture.calls.size)
      fixture.connect()
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
      runCurrent()
      assertEquals(3, fixture.calls.size)
    }

  @Test
  fun lateSuccessCannotSuppressTheReplacementNotification() =
    runTest {
      val fixture = Fixture(backgroundScope)
      val release = CompletableDeferred<Unit>()
      fixture.connect {
        release.await()
        """{"handled":true}"""
      }
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Connect)
      runCurrent()
      val replacement = fixture.connect()
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
      release.complete(Unit)
      runCurrent()
      assertEquals(2, fixture.calls.size)
      assertEquals(replacement, fixture.calls.last().connection)
    }

  @Test
  fun unknownOrFailedOutcomeDoesNotStartRetriesOrClaimSuccess() =
    runTest {
      val fixture = Fixture(backgroundScope)
      var attempts = 0
      fixture.connect {
        attempts++
        when (attempts) {
          1 -> error("lost response")
          2 -> """{"handled":false,"reason":"not\nhandled"}"""
          else -> """{"handled":true}"""
        }
      }
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
      runCurrent()
      assertEquals(1, attempts)
      assertTrue(fixture.unhandled.isEmpty())
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
      runCurrent()
      assertEquals(listOf("not handled"), fixture.unhandled)
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
      runCurrent()
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Background, throttleRecentSuccess = true)
      runCurrent()
      assertEquals(3, attempts)
    }

  @Test
  fun ownerCancellationEndsInFlightWorkWithoutResending() =
    runTest {
      val fixture = Fixture(backgroundScope)
      val entered = CompletableDeferred<Unit>()
      val ended = CompletableDeferred<Unit>()
      fixture.connect {
        entered.complete(Unit)
        try {
          CompletableDeferred<String>().await()
        } finally {
          ended.complete(Unit)
        }
      }
      fixture.owner.publish(NodePresenceAliveBeacon.Trigger.Connect)
      runCurrent()
      assertTrue(entered.isCompleted)
      assertFalse(ended.isCompleted)
      backgroundScope.cancel()
      runCurrent()
      assertTrue(ended.isCompleted)
      assertEquals(1, fixture.calls.size)
    }
}
