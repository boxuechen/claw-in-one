package ai.openclaw.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionSelectionTest {
  @Test
  fun featureSharesSelectionAndOwnerChecksStayWithSelection() {
    val h = Harness()
    val feature = h.owner.feature
    assertSame(h.owner.key, feature.key)
    assertSame(h.owner.ownerAgentId, feature.ownerAgentId)
    assertSame(h.owner.generation, feature.generation)
    assertSame(h.owner.defaultOwner, feature.defaultOwner)
    assertFalse(h.owner.isCurrentComposerOwner(ChatComposerOwner("gateway", "alice", "main")))
    h.owner.select("agent:alice:topic", "alice")
    val expected = ChatComposerOwner("gateway", "alice", "agent:alice:topic")
    assertTrue(h.owner.isCurrentComposerOwner(expected))
    assertFalse(h.owner.isCurrentComposerOwner(expected.copy(agentId = "bob")))
    h.gateway = ChatCacheScope("other", 2)
    assertFalse(h.owner.isCurrentComposerOwner(expected))
  }

  @Test
  fun selectionRoundTripAdvancesButSameSelectionAndRecoveryDoNot() {
    val h = Harness()
    assertTrue(h.owner.select("topic-a", "alice"))
    assertEquals(1L, h.owner.generation.value)
    assertFalse(h.owner.select("topic-a", " alice "))
    h.owner.normalizeCurrentKey()
    assertEquals(1L, h.owner.generation.value)
    h.owner.select("topic-b", "alice")
    h.owner.select("topic-a", "alice")
    assertEquals(3L, h.owner.generation.value)
    h.owner.select("topic-a", "bob")
    assertEquals(4L, h.owner.generation.value)
  }

  @Test
  fun qualifiedOwnerWinsAndUnqualifiedSelectionRetainsItsCapturedOwner() {
    val h = Harness()
    h.owner.select("agent:alice:topic", "bob")
    assertEquals("alice", h.owner.ownerAgentId.value)
    h.owner.select("topic", "alice")
    h.defaultAgent = "charlie"
    assertEquals("alice", h.owner.resolveOwner("topic"))
    assertEquals("bob", h.owner.resolveOwner("agent:bob:topic"))
    assertFalse(h.owner.tracksDefaultAgent("topic"))
  }

  @Test
  fun mainAliasBindingDoesNotTakeOverAnExplicitTopic() {
    val h = Harness()
    assertEquals("agent:alice:main", h.owner.bindMainKey(" agent:alice:main "))
    h.owner.select("agent:alice:main", null)
    assertEquals("agent:alice:main", h.owner.normalizeKey("main"))
    h.owner.select("topic", "alice")
    assertNull(h.owner.bindMainKey("agent:bob:main"))
    assertEquals("topic", h.owner.key.value)
    assertEquals("agent:bob:main", h.owner.normalizeKey(""))
    h.owner.resetMainKey()
    assertEquals("main", h.owner.mainKey)
  }

  @Test
  fun verifiedDefaultIsRetainedOfflineOnlyForItsGateway() {
    val h = Harness()
    assertEquals(GatewayDefaultAgentOwner("gateway", "alice"), h.owner.defaultOwner.value)
    h.defaultAgent = null
    assertEquals("alice", h.owner.effectiveDefaultAgentId())
    h.gateway = ChatCacheScope("other", 2)
    assertNull(h.owner.effectiveDefaultAgentId())
    h.owner.recordDefaultAgent("other", "bob")
    assertEquals("bob", h.owner.effectiveDefaultAgentId())
    h.owner.forgetDefaultAgent("gateway")
    assertEquals("bob", h.owner.effectiveDefaultAgentId())
    h.owner.forgetDefaultAgent("other")
    assertNull(h.owner.effectiveDefaultAgentId())
    assertNull(h.owner.defaultOwner.value)
  }

  @Test
  fun catalogOwnerSurvivesSameAgentChatSwitchButNotDefaultRevisionOrReconnect() {
    val h = Harness()
    val captured = h.owner.captureCatalogOwner()!!
    h.owner.select("topic", null)
    assertTrue(h.owner.isCurrent(captured))
    h.defaultRevision += 1
    assertFalse(h.owner.isCurrent(captured))
    val refreshed = h.owner.captureCatalogOwner()!!
    h.gateway = h.gateway.copy(connectionGeneration = 2)
    assertFalse(h.owner.isCurrent(refreshed))
  }

  @Test
  fun explicitOwnerIgnoresDefaultChangesButNotAnotherSelectedOwner() {
    val h = Harness()
    h.owner.select("topic", "alice")
    val captured = h.owner.captureCatalogOwner()!!
    h.defaultAgent = "bob"
    h.defaultRevision += 1
    assertTrue(h.owner.isCurrent(captured))
    h.owner.select("topic", "bob")
    assertFalse(h.owner.isCurrent(captured))
  }

  private class Harness {
    var gateway = ChatCacheScope("gateway", 1)
    var defaultAgent: String? = "alice"
    var defaultRevision = 1L
    val owner = ChatSessionSelection(Any(), { gateway }, { defaultAgent }, { defaultRevision })
  }
}
