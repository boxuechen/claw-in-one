package ai.openclaw.app.ui.chat

import ai.openclaw.app.PendingAssistantAutoSend
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatMessageContent
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenTest {
  @Test
  fun jumpToLatestReservesItsTouchTargetBelowMessages() {
    assertEquals(0.dp, chatReaderListBottomInset(showJumpToLatest = false))
    assertEquals(56.dp, chatReaderListBottomInset(showJumpToLatest = true))
  }

  @Test
  fun longUserMessagesProduceABoundedPlainTextPreview() {
    assertNull(ChatUserMessageDisclosurePolicy.collapsedPreview("Short prompt"))
    assertNull(ChatUserMessageDisclosurePolicy.collapsedPreview(List(12) { "line" }.joinToString("\n")))
    assertNull(ChatUserMessageDisclosurePolicy.collapsedPreview("a".repeat(700)))
    assertEquals(
      List(12) { "line" }.joinToString("\n") + "…",
      ChatUserMessageDisclosurePolicy.collapsedPreview(List(13) { "line" }.joinToString("\n")),
    )
    assertEquals(
      "a".repeat(700) + "…",
      ChatUserMessageDisclosurePolicy.collapsedPreview("a".repeat(701)),
    )
  }

  @Test
  fun disclosureDoesNotReorderMixedUserContent() {
    val mixedContent =
      listOf(
        ChatMessageContent(type = "text", text = "a".repeat(701)),
        ChatMessageContent(type = "image", fileName = "photo.png", base64 = "AAAA"),
        ChatMessageContent(type = "text", text = "caption"),
      )

    assertFalse(shouldUseUserMessageDisclosure(isUser = true, content = mixedContent))
  }

  @Test
  fun composerTrailingActionPreservesRunStopPrecedence() {
    assertEquals(
      ChatComposerTrailingAction.Stop,
      resolveChatComposerTrailingAction(runActive = true, sendEnabled = true),
    )
    assertEquals(
      ChatComposerTrailingAction.Send,
      resolveChatComposerTrailingAction(runActive = false, sendEnabled = true),
    )
    assertEquals(
      ChatComposerTrailingAction.None,
      resolveChatComposerTrailingAction(runActive = false, sendEnabled = false),
    )
  }

  @Test
  fun resolvesPendingAssistantAutoSendOnlyWhenChatIsReady() {
    val owner = ChatComposerOwner(gatewayStableId = "gateway", agentId = "main", sessionKey = "agent:main:device")
    val pending = PendingAssistantAutoSend(prompt = "  summarize mail  ", owner = owner)
    assertNull(
      resolvePendingAssistantAutoSend(
        pending = pending,
        currentOwner = owner,
        healthOk = false,
        pendingRunCount = 0,
      ),
    )
    assertNull(
      resolvePendingAssistantAutoSend(
        pending = pending,
        currentOwner = owner,
        healthOk = true,
        pendingRunCount = 1,
      ),
    )
    assertNull(
      resolvePendingAssistantAutoSend(
        pending = pending,
        currentOwner = owner.copy(sessionKey = "agent:main:other"),
        healthOk = true,
        pendingRunCount = 0,
      ),
    )
    assertEquals(
      pending,
      resolvePendingAssistantAutoSend(
        pending = pending,
        currentOwner = owner,
        healthOk = true,
        pendingRunCount = 0,
      ),
    )
  }

  @Test
  fun initialChatLoadUsesMainWhenNoSessionIsSelected() {
    assertEquals(
      "agent:ops:device",
      resolveInitialChatLoadSessionKey(
        sessionKey = "main",
        mainSessionKey = "agent:ops:device",
      ),
    )
  }

  @Test
  fun initialChatLoadPreservesSelectedSession() {
    assertNull(
      resolveInitialChatLoadSessionKey(
        sessionKey = "session:history",
        mainSessionKey = "agent:ops:device",
      ),
    )
  }

  @Test
  fun healthyEmptyChatShowsStarterStateInsteadOfLoadingPlaceholder() {
    assertFalse(
      showChatLoadingPlaceholder(
        historyLoading = true,
        healthOk = true,
        gatewayOffline = false,
      ),
    )
    assertTrue(
      showChatLoadingPlaceholder(
        historyLoading = true,
        healthOk = false,
        gatewayOffline = false,
      ),
    )
  }
}
