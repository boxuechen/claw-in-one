package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatMessageContent
import ai.openclaw.app.gateway.GatewayLoadedImage
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawTextButton
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal data class ChatBubbleContentProjection(
  val parts: List<ChatMessageContent>,
  val omittedImageCount: Int,
)

/** Pure transcript-to-bubble projection shared by Timeline admission and rendering. */
internal fun projectChatBubbleContent(content: List<ChatMessageContent>): ChatBubbleContentProjection {
  var imageCount = 0
  val parts =
    content.filter { part ->
      when (part.type) {
        "text" -> !part.text.isNullOrBlank()
        "image" -> {
          val visible = imageCount < 4
          imageCount += 1
          visible
        }
        else -> part.type == "file" || part.type == "unsupported"
      }
    }
  return ChatBubbleContentProjection(
    parts = parts,
    omittedImageCount = (imageCount - 4).coerceAtLeast(0),
  )
}

@Composable
internal fun ChatBubble(
  messageId: String?,
  role: String,
  live: Boolean,
  content: List<ChatMessageContent>,
  timestampMs: Long?,
  onReplyMessage: (String) -> Unit,
  imageResolverReady: Boolean,
  loadImage: suspend (String) -> GatewayLoadedImage?,
  senderLabel: String? = null,
  showQuickActions: Boolean,
  disclosure: @Composable () -> Unit = {},
) {
  val normalizedRole = role.trim().lowercase(Locale.US)
  val isUser = normalizedRole == "user"
  val peerSenderLabel = senderLabel?.trim()?.takeIf { isUser && it.isNotEmpty() }
  val speaker =
    when {
      isUser -> peerSenderLabel ?: nativeString("You")
      normalizedRole == "system" -> nativeString("System")
      else -> nativeString("OpenClaw")
    }
  val caption =
    when {
      live -> null
      normalizedRole == "system" -> nativeString("System")
      peerSenderLabel != null -> peerSenderLabel
      else -> null
    }
  val projection = projectChatBubbleContent(content)
  val displayableContent = projection.parts
  val omittedImageCount = projection.omittedImageCount
  if (displayableContent.isEmpty()) return

  val messageText = chatMessagePlainText(displayableContent)
  val collapsibleUserText = shouldUseUserMessageDisclosure(isUser, displayableContent)
  var userMessageExpanded by rememberSaveable(messageId, messageText) { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    ChatMessageActionHost(
      text = messageText,
      onReply = onReplyMessage,
      enabled = !live,
      showQuickActions = showQuickActions,
      modifier =
        Modifier
          .fillMaxWidth(if (isUser) 0.82f else 1f)
          .semantics(mergeDescendants = true) { contentDescription = speaker },
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (isUser) ClawTheme.radii.control else 0.dp),
        color = if (isUser) ClawTheme.colors.surfacePressed else Color.Transparent,
        contentColor = ClawTheme.colors.text,
        border = null,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
      ) {
        Column(
          modifier = if (isUser) Modifier.padding(horizontal = 14.dp, vertical = 10.dp) else Modifier.padding(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          caption?.let {
            Text(
              text = it,
              style = ClawTheme.type.caption.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
              color = ClawTheme.colors.text,
            )
          }
          if (collapsibleUserText && messageText.isNotBlank()) {
            ChatUserMessageText(
              textParts = displayableContent.mapNotNull { it.text },
              plainText = messageText,
              expanded = userMessageExpanded,
              onToggleExpanded = { userMessageExpanded = !userMessageExpanded },
            )
          }
          displayableContent.forEach { part ->
            when {
              part.type == "text" && !collapsibleUserText -> ChatText(text = part.text.orEmpty(), textColor = ClawTheme.colors.text, isStreaming = live)
              part.type == "text" -> Unit
              part.type == "image" && !part.base64.isNullOrBlank() ->
                ChatBase64Image(base64 = part.base64, mimeType = part.mimeType)
              part.type == "image" && !part.artifactId.isNullOrBlank() ->
                ChatManagedImage(
                  artifactId = part.artifactId,
                  label = part.fileName ?: nativeString("Image"),
                  resolverReady = imageResolverReady,
                  loadImage = loadImage,
                )
              else ->
                Text(
                  text = part.fileName ?: if (part.type == "unsupported") nativeString("Unsupported attachment") else nativeString("Attachment"),
                  style = ClawTheme.type.body,
                  color = ClawTheme.colors.textMuted,
                )
            }
          }
          if (omittedImageCount > 0) {
            Text(
              text = nativeString("Additional images hidden: \${omittedImageCount}", omittedImageCount),
              style = ClawTheme.type.caption,
              color = ClawTheme.colors.textMuted,
            )
          }
          if (messageId != null) {
            ChatMessageLinkPreview(messageId = messageId, role = normalizedRole, content = displayableContent)
          }
          disclosure()
        }
      }
    }
  }
}

@Composable
private fun ChatUserMessageText(
  textParts: List<String>,
  plainText: String,
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
) {
  val preview = ChatUserMessageDisclosurePolicy.collapsedPreview(plainText)
  if (preview != null && !expanded) {
    Text(
      text = preview,
      style = ClawTheme.type.body,
      color = ClawTheme.colors.text,
    )
  } else {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      textParts.forEach { text ->
        ChatMarkdown(text = text, textColor = ClawTheme.colors.text, isStreaming = false)
      }
    }
  }

  if (preview != null) {
    val toggleLabel = if (expanded) nativeString("Close") else nativeString("View all")
    ChatMessageDisclosureButton(toggleLabel, onToggleExpanded)
  }
}

@Composable
private fun ChatText(
  text: String,
  textColor: Color,
  isStreaming: Boolean,
) {
  ChatMarkdown(text = text, textColor = textColor, isStreaming = isStreaming)
}

@Composable
internal fun ChatNotice(
  title: String,
  body: String,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(ClawTheme.radii.panel),
    color = ClawTheme.colors.surface,
    contentColor = ClawTheme.colors.text,
    border = BorderStroke(1.dp, ClawTheme.colors.border),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Box(modifier = Modifier.size(6.dp).background(ClawTheme.colors.warning, CircleShape))
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text(text = body, style = ClawTheme.type.caption, color = ClawTheme.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
      if (actionLabel != null && onAction != null) {
        ClawTextButton(text = actionLabel, onClick = onAction)
      }
    }
  }
}
