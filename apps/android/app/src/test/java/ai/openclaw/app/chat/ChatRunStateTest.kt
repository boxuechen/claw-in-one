package ai.openclaw.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRunStateTest {
  private fun snapshot(
    local: Set<String> = setOf("local"),
    advertised: List<String> = emptyList(),
    hasAdvertised: Boolean = advertised.isNotEmpty(),
    session: String = "chat-a",
    startedAt: Long? = null,
    clocks: Map<String, String> = emptyMap(),
  ) = ChatRunSnapshot(session, local, advertised, hasAdvertised, startedAt, clocks)

  @Test
  fun projectionDeduplicatesOwnershipAndKeepsOptimisticClock() {
    val state = ChatRunState()
    state.publish(snapshot(advertised = listOf("remote", "local"), clocks = mapOf("local" to "message")))
    assertEquals(1, state.pendingCount.value)
    assertEquals(2, state.presentation.value.count)
    assertEquals("local", state.presentation.value.runId)
    assertEquals("message", state.presentation.value.clockKey)
  }

  @Test
  fun usageIsOrderedAndCumulative() {
    val state = ChatRunState()
    assertTrue(state.recordUsage("local", 2, 12))
    assertFalse(state.recordUsage("local", 1, 90))
    assertFalse(state.recordUsage("local", 3, 8))
    state.publish(snapshot())
    assertEquals(12L, state.presentation.value.outputTokens)
  }

  @Test
  fun terminalCannotBeResurrectedByLateUsageOrStart() {
    val state = ChatRunState()
    state.recordUsage("local", 1, 10)
    assertTrue(state.applyLifecycle("local", 2, terminal = true))
    assertFalse(state.applyLifecycle("local", 3, terminal = false))
    assertFalse(state.recordUsage("local", 4, 50))
    state.clearUnownedNonterminal("local", advertised = false)
    state.publish(snapshot(advertised = listOf("local")))
    assertEquals(1, state.pendingCount.value)
    assertEquals(0, state.presentation.value.count)
    assertNull(state.presentation.value.outputTokens)
    state.prune(emptySet())
    state.recordUsage("local", 1, 7)
    state.publish(snapshot())
    assertEquals(7L, state.presentation.value.outputTokens)
  }

  @Test
  fun telemetryGapRetainsOwnershipAndSequenceButDropsUnprovenUsage() {
    val state = ChatRunState()
    state.recordUsage("local", 10, 90)
    state.invalidateIncomplete()
    assertFalse(state.recordUsage("local", 9, 100))
    state.publish(snapshot())
    assertEquals(1, state.presentation.value.count)
    assertNull(state.presentation.value.outputTokens)
    assertTrue(state.recordUsage("local", 11, 100))
  }

  @Test
  fun acknowledgementTransfersTelemetryWithoutChangingMessageClock() {
    val state = ChatRunState()
    state.recordUsage("local", 2, 10)
    state.recordUsage("remote", 3, 8)
    state.transfer("local", "remote")
    state.publish(snapshot(local = setOf("remote"), clocks = mapOf("remote" to "message")))
    assertEquals("remote", state.presentation.value.runId)
    assertEquals("message", state.presentation.value.clockKey)
    assertEquals(10L, state.presentation.value.outputTokens)
    assertFalse(state.recordUsage("remote", 2, 20))
    state.retire("remote")
    state.recordUsage("local", 1, 40)
    state.transfer("local", "remote")
    state.publish(snapshot(local = setOf("remote")))
    assertEquals(0, state.presentation.value.count)
  }

  @Test
  fun unknownAdvertisedRunUsesSelectedSessionAndStartTime() {
    val state = ChatRunState()
    state.publish(snapshot(local = emptySet(), hasAdvertised = true, startedAt = 100))
    assertEquals(1, state.presentation.value.count)
    assertNull(state.presentation.value.runId)
    assertEquals("chat-a:active:100", state.presentation.value.clockKey)
    state.publish(snapshot(local = emptySet(), hasAdvertised = true, session = "chat-b"))
    assertEquals("chat-b:active", state.presentation.value.clockKey)
    state.publish(snapshot(local = emptySet()))
    assertEquals(ChatActiveRunPresentation(), state.presentation.value)
  }

  @Test
  fun prunedOrClearedTelemetryCannotLeakIntoAnotherSelection() {
    val state = ChatRunState()
    state.recordUsage("local", 1, 20)
    state.recordUsage("remote", 1, 30)
    state.prune(setOf("remote"))
    state.publish(snapshot())
    assertNull(state.presentation.value.outputTokens)
    state.publish(snapshot(local = setOf("remote")))
    assertEquals(30L, state.presentation.value.outputTokens)
    state.clear()
    state.publish(snapshot(local = setOf("remote")))
    assertNull(state.presentation.value.outputTokens)
  }
}
