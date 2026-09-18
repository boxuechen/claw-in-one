package ai.openclaw.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSessionTitleTest {
  @Test
  fun titlePriorityIsManualThenGatewayGeneratedThenDerived() {
    val session =
      ChatSessionEntry(
        key = "agent:main:topic:one",
        updatedAtMs = 1,
        label = "Manual name",
        displayName = "Generated name",
        derivedTitle = "Derived name",
      )

    assertEquals(
      ChatSessionTitle("Manual name", ChatSessionTitleSource.Manual),
      resolveChatSessionTitle(session) { "Unnamed" },
    )
    assertEquals(
      ChatSessionTitle("Generated name", ChatSessionTitleSource.DisplayName),
      resolveChatSessionTitle(session.copy(label = null)) { "Unnamed" },
    )
    assertEquals(
      ChatSessionTitle("Derived name", ChatSessionTitleSource.Derived),
      resolveChatSessionTitle(session.copy(label = null, displayName = null)) { "Unnamed" },
    )
  }

  @Test
  fun blankOrIdentityFieldsCannotLeakIntoPresentation() {
    val session =
      ChatSessionEntry(
        key = "agent:main:topic:one",
        updatedAtMs = 1,
        label = "  ",
        displayName = "agent:main:topic:one",
        derivedTitle = "",
      )

    assertEquals(
      ChatSessionTitle("Unnamed", ChatSessionTitleSource.Fallback),
      resolveChatSessionTitle(session) { "Unnamed" },
    )
  }

  @Test
  fun materializedProjectDraftKeyRemainsAProductConversation() {
    assertEquals(
      true,
      ChatSessionEntry(
        key = "agent:main:claw-in-one-project-draft:stable-id",
        updatedAtMs = 1,
        sessionId = "session-1",
      ).isClawInOneConversation(),
    )
  }

  @Test
  fun explicitLabelWinsEvenWhenItMatchesTheTechnicalKey() {
    val key = "agent:main:topic:one"
    assertEquals(
      ChatSessionTitle(key, ChatSessionTitleSource.Manual),
      resolveChatSessionTitle(
        ChatSessionEntry(key = key, updatedAtMs = 1, label = key),
      ) { "Unnamed" },
    )
  }
}
