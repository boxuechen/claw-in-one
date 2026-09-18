package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatMessageContent
import ai.openclaw.app.chat.ChatOutboxItem
import ai.openclaw.app.chat.ChatOutboxStatus
import ai.openclaw.app.chat.parseChatMessageContent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatMessageViewsTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun transcriptBubblesExposeSpeakerWithoutReplacingMessageText() {
    val messages =
      listOf(
        Triple("user", "user body", false),
        Triple("user", "peer body", false),
        Triple("assistant", "assistant body", false),
        Triple("system", "system body", false),
        Triple("assistant", "live body", true),
      )

    composeRule.setContent {
      Column {
        messages.forEachIndexed { index, (role, body, live) ->
          ChatBubble(
            messageId = "message-$index",
            role = role,
            live = live,
            showQuickActions = role == "assistant" && !live,
            content = listOf(ChatMessageContent(type = "text", text = body)),
            timestampMs = null,
            onReplyMessage = {},
            imageResolverReady = false,
            loadImage = { null },
            senderLabel =
              when (body) {
                "peer body" -> "  Alex (Slack)  "
                "assistant body", "system body", "live body" -> "Spoofed sender"
                else -> null
              },
          )
        }
      }
    }

    val userBubble = composeRule.onNode(hasContentDescription("You") and hasText("user body")).assertExists()
    composeRule.onNode(hasContentDescription("Alex (Slack)") and hasText("peer body")).assertExists()
    composeRule.onNodeWithText("Alex (Slack)", useUnmergedTree = true).assertIsDisplayed()
    val assistantBubble = composeRule.onNode(hasContentDescription("OpenClaw") and hasText("assistant body")).assertExists()
    composeRule.onNode(hasContentDescription("System") and hasText("system body")).assertExists()
    composeRule.onNode(hasContentDescription("OpenClaw") and hasText("live body")).assertExists()
    listOf(userBubble, assistantBubble).forEach { bubble ->
      val semantics = bubble.fetchSemanticsNode().config
      assertTrue(semantics.isMergingSemanticsOfDescendants)
      assertTrue(SemanticsActions.OnLongClick in semantics)
    }
    composeRule.onAllNodesWithText("You", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("OpenClaw", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("Spoofed sender", useUnmergedTree = true).assertCountEquals(0)
    composeRule.onAllNodesWithText("System", useUnmergedTree = true).assertCountEquals(1)
    composeRule.onAllNodesWithText("OpenClaw · Live", useUnmergedTree = true).assertCountEquals(0)
    listOf("Copy response", "Share", "More actions").forEach { label ->
      composeRule.onNode(hasContentDescription(label) and hasClickAction()).assertIsDisplayed()
    }

    userBubble.performSemanticsAction(SemanticsActions.OnLongClick) { action -> action() }
    listOf("Select text", "Reply").forEach { label ->
      composeRule.onNode(hasText(label) and hasClickAction()).assertExists()
    }
    composeRule.onNodeWithText("Select text").performClick()
    composeRule.onAllNodesWithText("user body").assertCountEquals(1)
    composeRule.runOnIdle {
      val reader = nativeReaders().single()
      assertEquals("user body", reader.text.toString())
      assertTrue(reader.isShown && reader.width > 0 && reader.height > 0)
      assertTrue(reader.getGlobalVisibleRect(Rect()))
    }
    composeRule.onNode(hasText("Done") and hasClickAction()).performClick()
    composeRule.runOnIdle { assertTrue(nativeReaders().isEmpty()) }

    assistantBubble.performSemanticsAction(SemanticsActions.OnLongClick) { action -> action() }
    composeRule.onNode(hasText("Reply") and hasClickAction()).assertExists()
  }

  @Test
  fun outboxAndUnsupportedContentExposeSafeLabels() {
    composeRule.setContent {
      Column {
        ChatOutboxBubble(
          item =
            ChatOutboxItem(
              id = "outbox-1",
              sessionKey = "main",
              text = "queued body",
              thinkingLevel = "low",
              createdAtMs = 0L,
              status = ChatOutboxStatus.Queued,
              retryCount = 0,
              lastError = null,
              ownerAgentId = "main",
            ),
          onRetry = {},
          onDelete = {},
        )
        ChatBubble(
          messageId = "unsupported-content",
          role = "assistant",
          live = false,
          showQuickActions = true,
          content =
            listOf(
              ChatMessageContent(
                type = "unsupported",
                mimeType = "application/octet-stream",
                fileName = "result.bin",
              ),
            ),
          timestampMs = null,
          onReplyMessage = {},
          imageResolverReady = false,
          loadImage = { null },
        )
      }
    }

    composeRule
      .onNode(
        hasContentDescription("You") and
          hasText("queued body") and
          hasAnyDescendant(hasText("Delete") and hasClickAction()),
      ).assertExists()
    composeRule
      .onNode(
        hasContentDescription("OpenClaw") and hasText("result.bin"),
      ).assertExists()
  }

  @Test
  fun attachmentOnlyAssistantTurnShowsDocumentFilenameWithoutLoadingItsUrl() {
    var artifactRequests = 0

    composeRule.setContent {
      ChatBubble(
        messageId = "document-message",
        role = "assistant",
        live = false,
        showQuickActions = true,
        content =
          listOf(
            ChatMessageContent(
              type = "file",
              mimeType = "application/pdf",
              fileName = "quarterly-report.pdf",
            ),
          ),
        timestampMs = null,
        onReplyMessage = {},
        imageResolverReady = false,
        loadImage = {
          artifactRequests += 1
          null
        },
      )
    }

    composeRule
      .onNode(hasContentDescription("OpenClaw") and hasText("quarterly-report.pdf"))
      .assertIsDisplayed()
    assertEquals(0, artifactRequests)
  }

  @Test
  fun omittedImageOnlyTurnsRemainVisibleWithoutLoadingBeyondTheImageCap() {
    val omittedImage =
      requireNotNull(
        parseChatMessageContent(
          Json.parseToJsonElement(
            """{"type":"image","mimeType":"image/png","omitted":true,"bytes":5}""",
          ),
        ),
      )
    var artifactRequests = 0

    composeRule.setContent {
      Column {
        listOf(
          listOf(omittedImage),
          (1..5).map { index -> omittedImage.copy(fileName = "redacted-$index.png") },
        ).forEachIndexed { index, images ->
          ChatBubble(
            messageId = "omitted-images-$index",
            role = "assistant",
            live = false,
            showQuickActions = true,
            content = images,
            timestampMs = null,
            onReplyMessage = {},
            imageResolverReady = true,
            loadImage = {
              artifactRequests += 1
              null
            },
          )
        }
      }
    }

    composeRule.onNode(hasContentDescription("OpenClaw") and hasText("Attachment")).assertIsDisplayed()
    (1..4).forEach { index -> composeRule.onNodeWithText("redacted-$index.png").assertIsDisplayed() }
    composeRule.onAllNodesWithText("redacted-5.png").assertCountEquals(0)
    composeRule.onNodeWithText("Additional images hidden: 1").assertIsDisplayed()
    assertEquals(0, artifactRequests)
  }

  @Test
  fun managedImageCompositionRequestsItsArtifact() {
    val artifactId = "artifact_managed_image_11111111-1111-4111-8111-111111111111"
    val requested = mutableListOf<String>()

    composeRule.setContent {
      ChatBubble(
        messageId = "managed-image",
        role = "assistant",
        live = false,
        showQuickActions = true,
        content =
          listOf(
            ChatMessageContent(
              type = "image",
              mimeType = "image/png",
              artifactId = artifactId,
              fileName = "Managed image",
            ),
          ),
        timestampMs = null,
        onReplyMessage = {},
        imageResolverReady = true,
        loadImage = { requestedArtifactId ->
          requested += requestedArtifactId
          null
        },
      )
    }
    composeRule.waitUntil(timeoutMillis = 5_000) { requested.isNotEmpty() }

    assertEquals(listOf(artifactId), requested)
  }

  @Test
  fun systemRowsRenderNoticeLabelAndDividerMetric() {
    composeRule.setContent {
      Column {
        ChatSystemNoticeRow(
          ChatTimelineItem.SystemNotice(
            key = "system-notice:1:0",
            label = "System · restart recovery",
            body = "Turn interrupted by a gateway restart — asked the agent to resume and finish the response.",
          ),
        )
        ChatSystemDividerRow(
          ChatTimelineItem.SystemDivider(
            key = "divider:compaction:checkpoint-1",
            kind = SystemDividerKind.Compaction,
            label = "Compacted history",
            metric = "saved 875.3k tokens",
          ),
        )
      }
    }

    composeRule.onNodeWithText("System · restart recovery").assertIsDisplayed()
    composeRule
      .onNodeWithText("Turn interrupted by a gateway restart — asked the agent to resume and finish the response.")
      .assertIsDisplayed()
    composeRule.onNodeWithText("Compacted history").assertIsDisplayed()
    composeRule.onNodeWithText("saved 875.3k tokens").assertIsDisplayed()
  }

  private fun nativeReaders(): List<TextView> {
    fun descendants(view: View): Sequence<View> =
      sequence {
        yield(view)
        if (view is ViewGroup) {
          for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
      }
    return WindowInspector
      .getGlobalWindowViews()
      .asSequence()
      .flatMap(::descendants)
      .filterIsInstance<TextView>()
      .filter { it.isTextSelectable && !it.onCheckIsTextEditor() }
      .toList()
  }
}
