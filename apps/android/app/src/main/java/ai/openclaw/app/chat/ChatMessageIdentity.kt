package ai.openclaw.app.chat

/**
 * Keep Compose item identity stable across history refreshes by matching existing messages to incoming copies.
 */
internal fun reconcileMessageIds(
  previous: List<ChatMessage>,
  incoming: List<ChatMessage>,
): List<ChatMessage> {
  if (previous.isEmpty() || incoming.isEmpty()) return incoming

  val messagesByKey = LinkedHashMap<String, ArrayDeque<ChatMessage>>()
  for (message in previous) {
    val key = messageIdentityKey(message) ?: continue
    messagesByKey.getOrPut(key) { ArrayDeque() }.addLast(message)
  }

  return incoming.map { message ->
    val key = messageIdentityKey(message) ?: return@map message
    val matches = messagesByKey[key] ?: return@map message
    val previousMessage = matches.removeFirstOrNull() ?: return@map message
    if (matches.isEmpty()) {
      messagesByKey.remove(key)
    }
    message.copy(
      id = previousMessage.id,
      entryId = message.entryId,
    )
  }
}

internal fun mergeOptimisticMessages(
  incoming: List<ChatMessage>,
  optimistic: Collection<ChatMessage>,
): List<ChatMessage> {
  if (optimistic.isEmpty()) return incoming

  val missingOptimistic = retainUnmatchedOptimisticMessages(incoming = incoming, optimistic = optimistic)
  if (missingOptimistic.isEmpty()) return incoming

  return (incoming + missingOptimistic).sortedWith(compareBy<ChatMessage> { it.timestampMs ?: Long.MAX_VALUE }.thenBy { it.id })
}

internal fun retainUnmatchedOptimisticMessages(
  incoming: List<ChatMessage>,
  optimistic: Collection<ChatMessage>,
): List<ChatMessage> {
  if (optimistic.isEmpty()) return emptyList()

  val unmatchedIncoming = incoming.toMutableList()
  return optimistic.filter { message ->
    val matchIndex =
      unmatchedIncoming.indexOfFirst { incomingMessage ->
        incomingMessageConsumesOptimistic(incomingMessage, message)
      }
    if (matchIndex >= 0) {
      unmatchedIncoming.removeAt(matchIndex)
      false
    } else {
      true
    }
  }
}

/**
 * Message identity used only for refresh reconciliation; it avoids exposing gateway ids as UI keys.
 */
internal fun messageIdentityKey(message: ChatMessage): String? {
  val idempotencyKey = message.idempotencyKey?.trim().orEmpty()
  if (idempotencyKey.isNotEmpty()) {
    return listOf(message.role.trim().lowercase(), idempotencyKey).joinToString(separator = "|")
  }
  val contentKey = messageContentIdentityKey(message) ?: return null
  val timestamp = message.timestampMs?.toString().orEmpty()
  if (timestamp.isEmpty() && contentKey.isEmpty()) return null
  return listOf(contentKey, timestamp).joinToString(separator = "|")
}

private fun optimisticMessageIdentityKey(message: ChatMessage): String? = messageContentIdentityKey(message)

internal fun incomingMessageConsumesOptimistic(
  incoming: ChatMessage,
  optimistic: ChatMessage,
): Boolean {
  val optimisticIdempotencyKey = optimistic.idempotencyKey?.trim().orEmpty()
  if (optimisticIdempotencyKey.isNotEmpty()) {
    return incoming.idempotencyKey?.trim() == optimisticIdempotencyKey
  }
  if (optimisticMessageIdentityKey(incoming) != optimisticMessageIdentityKey(optimistic)) return false
  val incomingTimestamp = incoming.timestampMs ?: return false
  val optimisticTimestamp = optimistic.timestampMs ?: return true
  return incomingTimestamp >= optimisticTimestamp
}

internal fun messageContentIdentityKey(message: ChatMessage): String? {
  val role = message.role.trim().lowercase()
  if (role.isEmpty()) return null

  val contentFingerprint =
    message.content.joinToString(separator = "\u001E") { part ->
      listOf(
        part.type.trim().lowercase(),
        part.text?.trim().orEmpty(),
        part.mimeType
          ?.trim()
          ?.lowercase()
          .orEmpty(),
        part.fileName?.trim().orEmpty(),
        part.artifactId?.trim().orEmpty(),
        part.base64
          ?.hashCode()
          ?.toString()
          .orEmpty(),
      ).joinToString(separator = "\u001F")
    }

  return listOf(role, contentFingerprint).joinToString(separator = "|")
}
