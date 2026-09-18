package ai.openclaw.app.ui.chat

import ai.openclaw.app.PendingAssistantAutoSend
import ai.openclaw.app.chat.ChatComposerOwner
import ai.openclaw.app.chat.ChatMessageContent
import ai.openclaw.app.chat.ChatSessionEntry
import ai.openclaw.app.chat.ChatThinkingLevelOption
import ai.openclaw.app.chat.ChatThinkingLevelSelection
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.localizedUppercase
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Returns a pending assistant prompt only when chat can accept it immediately. */
internal fun resolvePendingAssistantAutoSend(
  pending: PendingAssistantAutoSend?,
  currentOwner: ChatComposerOwner,
  healthOk: Boolean,
  pendingRunCount: Int,
): PendingAssistantAutoSend? {
  val queued = pending ?: return null
  if (queued.prompt.isBlank() || queued.owner != currentOwner) return null
  if (!healthOk || pendingRunCount > 0) return null
  return queued
}

/** Chooses the session key to load for initial chat hydration, if any. */
internal fun resolveInitialChatLoadSessionKey(
  sessionKey: String,
  mainSessionKey: String,
): String? {
  val current = sessionKey.trim()
  val main = mainSessionKey.trim().ifEmpty { "main" }
  if (current.isNotEmpty() && current != "main" && current != main) return null
  return main
}

/** Reserves a viewport strip so the jump-to-latest target never covers chat content. */
internal fun chatReaderListBottomInset(showJumpToLatest: Boolean): Dp =
  if (showJumpToLatest) {
    56.dp
  } else {
    0.dp
  }

internal enum class ChatComposerTrailingAction {
  Stop,
  Send,
  None,
}

internal fun resolveChatComposerTrailingAction(
  runActive: Boolean,
  sendEnabled: Boolean,
): ChatComposerTrailingAction =
  when {
    runActive -> ChatComposerTrailingAction.Stop
    sendEnabled -> ChatComposerTrailingAction.Send
    else -> ChatComposerTrailingAction.None
  }

internal object ChatUserMessageDisclosurePolicy {
  const val collapsedLineLimit = 12
  const val collapsedCharacterLimit = 700

  fun collapsedPreview(text: String): String? {
    var end = minOf(text.length, collapsedCharacterLimit)
    if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
      end -= 1
    }
    var lineCount = 1
    for (index in 0 until end) {
      if (text[index] != '\n') continue
      if (lineCount == collapsedLineLimit) {
        end = index
        break
      }
      lineCount += 1
    }
    if (end == text.length) return null
    return text.substring(0, end).trimEnd() + "…"
  }
}

internal fun shouldUseUserMessageDisclosure(
  isUser: Boolean,
  content: List<ChatMessageContent>,
): Boolean =
  isUser &&
    content.isNotEmpty() &&
    content.all { it.type == "text" } &&
    ChatUserMessageDisclosurePolicy.collapsedPreview(chatMessagePlainText(content)) != null

internal fun canStartNewChat(
  pendingRunCount: Int,
  hasQueuedMessage: Boolean,
  gatewayReady: Boolean,
): Boolean = gatewayReady && pendingRunCount == 0 && !hasQueuedMessage

@OptIn(ExperimentalMaterial3Api::class)
internal fun isActiveSessionChoice(
  choiceKey: String,
  sessionKey: String,
  mainSessionKey: String,
): Boolean {
  val mainKey = mainSessionKey.trim().ifEmpty { "main" }
  val current = sessionKey.trim().let { if (it == "main" && mainKey != "main") mainKey else it }
  return choiceKey == current
}

internal data class ChatContextUsage(
  val totalTokens: Long?,
  val totalTokensFresh: Boolean?,
  val contextTokens: Long?,
)

internal fun resolveChatContextUsage(
  sessionKey: String,
  mainSessionKey: String,
  sessions: List<ChatSessionEntry>,
): ChatContextUsage {
  val entry =
    sessions.firstOrNull {
      isActiveSessionChoice(
        choiceKey = it.key,
        sessionKey = sessionKey,
        mainSessionKey = mainSessionKey,
      )
    }
  return ChatContextUsage(
    totalTokens = entry?.totalTokens,
    totalTokensFresh = entry?.totalTokensFresh,
    contextTokens = entry?.contextTokens,
  )
}

internal fun userFacingChatError(error: String): String {
  val lower = error.lowercase(Locale.US)
  return when {
    lower.contains("not connected") -> nativeString("Not connected")
    lower.contains("unauthorized") || lower.contains("auth") -> nativeString("Authentication needed")
    else -> error
  }
}

internal fun contextMeterWidth(usage: ChatContextUsage): Float? {
  if (usage.totalTokensFresh == false) return null
  val total = usage.totalTokens?.takeIf { it >= 0L } ?: return null
  val context = usage.contextTokens?.takeIf { it > 0L } ?: return null
  return (total.toDouble() / context.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

internal fun contextMeterThinkingLabel(value: String): String {
  val normalized = value.trim().lowercase(Locale.US).ifEmpty { "off" }
  return when (normalized) {
    "off" -> nativeString("Off")
    "low" -> nativeString("Low")
    "medium" -> nativeString("Medium")
    "high" -> nativeString("High")
    else -> normalized
  }
}

internal fun chatThinkingSupported(
  selection: ChatThinkingLevelSelection,
  fallbackSupported: Boolean,
): Boolean =
  if (selection.isGatewayProvided) {
    selection.options.any { it.id.trim().lowercase(Locale.US) != "off" }
  } else {
    fallbackSupported
  }

internal fun chatThinkingOptionRows(options: List<ChatThinkingLevelOption>): List<List<ChatThinkingLevelOption>> {
  if (options.isEmpty()) return emptyList()
  if (options.size <= 4) return listOf(options)
  return options.chunked((options.size + 1) / 2)
}

internal fun chatThinkingOptionLabel(
  option: ChatThinkingLevelOption,
  languageTag: String? = null,
): String {
  val id = option.id.trim()
  val rawLabel = option.label.trim().ifEmpty { id }
  val localizedLabel =
    if (rawLabel.equals(id, ignoreCase = true)) {
      when (id.lowercase(Locale.US)) {
        "off" -> nativeString("Off")
        "minimal" -> nativeString("Minimal")
        "low" -> nativeString("Low")
        "medium" -> nativeString("Medium")
        "high" -> nativeString("High")
        "xhigh" -> nativeString("Xhigh")
        "adaptive" -> nativeString("Adaptive")
        "max" -> nativeString("Max")
        "ultra" -> nativeString("Ultra")
        else -> rawLabel
      }
    } else {
      rawLabel
    }
  return localizedUppercase(localizedLabel.take(1), languageTag) + localizedLabel.drop(1)
}
