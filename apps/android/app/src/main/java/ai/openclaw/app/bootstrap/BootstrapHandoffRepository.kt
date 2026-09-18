package ai.openclaw.app.bootstrap

import ai.openclaw.app.SecurePrefs
import android.content.Context
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val ACTIVE_HANDOFF_KEY = "bootstrap_handoff_active_v4"

@Serializable
internal data class BootstrapHandoffSession(
  val requestId: String,
  val handoffSecretHex: String,
  val supervisorSecretHex: String,
  val bootstrapUri: String,
  val environmentUri: String,
  val eventsUri: String,
  val supervisorCommandUri: String,
  val supervisorStatusUri: String,
  val command: String,
  val createdAtEpochSeconds: Long,
  val lastSequence: Long = 0,
  val lastProgressOrdinal: Int = -1,
) {
  fun isValid(): Boolean =
    requestId.matches(Regex("[0-9a-f]{32}")) &&
      handoffSecretHex.matches(Regex("[0-9a-f]{64}")) &&
      supervisorSecretHex.matches(Regex("[0-9a-f]{64}")) &&
      handoffSecretHex != supervisorSecretHex &&
      listOf(
        bootstrapUri,
        environmentUri,
        eventsUri,
        supervisorCommandUri,
        supervisorStatusUri,
      ).all(::isOwnedContentUri) &&
      command.length in 32..2048 &&
      !command.contains('\n') &&
      createdAtEpochSeconds > 0 &&
      lastSequence in 0..SupervisorProgress.entries.size.toLong() &&
      lastProgressOrdinal in -1 until SupervisorProgress.entries.size &&
      lastSequence == (lastProgressOrdinal + 1).toLong()

  override fun toString(): String =
    "BootstrapHandoffSession(requestId=$requestId, handoffSecretHex=<redacted>, " +
      "supervisorSecretHex=<redacted>, " +
      "lastSequence=$lastSequence, lastProgressOrdinal=$lastProgressOrdinal)"
}

private fun isOwnedContentUri(value: String): Boolean =
  runCatching {
    val uri = value.toUri()
    uri.scheme == "content" && !uri.authority.isNullOrBlank()
  }.getOrDefault(false)

internal sealed interface BootstrapHandoffReadResult {
  data class Content(
    val completeLines: List<String>,
  ) : BootstrapHandoffReadResult

  data object Missing : BootstrapHandoffReadResult

  data object TooLarge : BootstrapHandoffReadResult

  data object InvalidEncoding : BootstrapHandoffReadResult

  data object Failed : BootstrapHandoffReadResult
}

internal interface BootstrapHandoffRepository {
  fun loadActive(): BootstrapHandoffSession?

  fun saveActive(session: BootstrapHandoffSession): Boolean

  fun readEvents(session: BootstrapHandoffSession): BootstrapHandoffReadResult

  fun checkpoint(
    requestId: String,
    sequence: Long,
    progress: SupervisorProgress,
  ): Boolean

  fun discardActive(requestId: String? = null)
}

internal class MediaStoreBootstrapHandoffRepository(
  context: Context,
  private val preferences: SecurePrefs,
) : BootstrapHandoffRepository {
  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = false }

  override fun loadActive(): BootstrapHandoffSession? {
    val encoded = preferences.getString(ACTIVE_HANDOFF_KEY) ?: return null
    return runCatching { json.decodeFromString<BootstrapHandoffSession>(encoded) }
      .getOrNull()
      ?.takeIf(BootstrapHandoffSession::isValid)
  }

  override fun saveActive(session: BootstrapHandoffSession): Boolean {
    if (!session.isValid()) return false
    return preferences.putStringSynchronously(ACTIVE_HANDOFF_KEY, json.encodeToString(session))
  }

  override fun readEvents(session: BootstrapHandoffSession): BootstrapHandoffReadResult {
    if (!session.isValid()) return BootstrapHandoffReadResult.Failed
    return try {
      val input =
        appContext.contentResolver.openInputStream(session.eventsUri.toUri())
          ?: return BootstrapHandoffReadResult.Missing
      val bytes =
        input.use {
          val output = ByteArrayOutputStream()
          val buffer = ByteArray(8 * 1024)
          var total = 0
          while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            total += count
            if (total > BOOTSTRAP_HANDOFF_MAX_BYTES) return BootstrapHandoffReadResult.TooLarge
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
      decodeCompleteBootstrapHandoffLines(bytes)
    } catch (_: java.io.FileNotFoundException) {
      BootstrapHandoffReadResult.Missing
    } catch (_: Exception) {
      BootstrapHandoffReadResult.Failed
    }
  }

  override fun checkpoint(
    requestId: String,
    sequence: Long,
    progress: SupervisorProgress,
  ): Boolean {
    val current = loadActive() ?: return false
    if (
      current.requestId != requestId ||
      sequence != current.lastSequence + 1 ||
      progress.ordinal != current.lastProgressOrdinal + 1
    ) {
      return false
    }
    return saveActive(
      current.copy(
        lastSequence = sequence,
        lastProgressOrdinal = progress.ordinal,
      ),
    )
  }

  override fun discardActive(requestId: String?) {
    val current = loadActive()
    if (requestId != null && current?.requestId != requestId) return
    if (current != null) {
      listOf(current.environmentUri, current.eventsUri).forEach { encodedUri ->
        runCatching { appContext.contentResolver.delete(encodedUri.toUri(), null, null) }
      }
    }
    preferences.removeSynchronously(ACTIVE_HANDOFF_KEY)
  }
}

internal fun decodeCompleteBootstrapHandoffLines(bytes: ByteArray): BootstrapHandoffReadResult {
  if (bytes.size > BOOTSTRAP_HANDOFF_MAX_BYTES) return BootstrapHandoffReadResult.TooLarge
  val content =
    runCatching {
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    }.getOrElse { return BootstrapHandoffReadResult.InvalidEncoding }
  val lastNewline = content.lastIndexOf('\n')
  val lines =
    if (lastNewline < 0) {
      emptyList()
    } else {
      content.substring(0, lastNewline).split('\n')
    }
  return BootstrapHandoffReadResult.Content(lines)
}
