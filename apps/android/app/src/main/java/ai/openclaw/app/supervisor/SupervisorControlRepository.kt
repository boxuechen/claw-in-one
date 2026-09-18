package ai.openclaw.app.supervisor

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

private const val SUPERVISOR_CONTROL_KEY = "supervisor_control_active_v5"

internal data class SupervisorActivation(
  val supervisorId: String,
  val secretHex: String,
  val commandUri: String,
  val statusUri: String,
) {
  fun isValid(): Boolean =
    supervisorId.matches(Regex("[0-9a-f]{32}")) &&
      secretHex.matches(Regex("[0-9a-f]{64}")) &&
      listOf(commandUri, statusUri).all(::isSupervisorContentUri)
}

@Serializable
internal data class SupervisorControlSession(
  val supervisorId: String,
  val secretHex: String,
  val commandUri: String,
  val statusUri: String,
  val lastCommandSequence: Long = 0,
  val pendingCommandSequence: Long? = null,
  val pendingCommand: SupervisorCommand? = null,
  val lastSupervisorBootId: String? = null,
  val lastStatusSequence: Long = 0,
  val lastStatusCommandSequence: Long = 0,
  val lastStatusTimestampEpochSeconds: Long = 0,
  val lastStatusStage: SupervisorStatusStage? = null,
  val lastGatewayGeneration: String? = null,
  val lastEnsureAttemptId: Long? = null,
  val lastStatusExitCode: Int = 0,
  val lastStatusPlanId: String? = null,
  val lastStatusSelectedCapabilities: List<DevelopmentCapability> = emptyList(),
  val lastStatusResolvedComponents: List<CapabilityComponent> = emptyList(),
  val lastStatusReadyCapabilities: List<DevelopmentCapability> = emptyList(),
  val lastStatusCurrentComponent: CapabilityComponent? = null,
  val lastStatusCompletedBytes: Long? = null,
  val lastStatusTotalBytes: Long? = null,
) {
  fun isValid(): Boolean =
    supervisorId.matches(Regex("[0-9a-f]{32}")) &&
      secretHex.matches(Regex("[0-9a-f]{64}")) &&
      listOf(commandUri, statusUri).all(::isSupervisorContentUri) &&
      lastCommandSequence >= 0 &&
      (
        (pendingCommandSequence == null && pendingCommand == null) ||
          (pendingCommandSequence in 1..lastCommandSequence && pendingCommand != null)
      ) &&
      lastStatusSequence >= 0 &&
      lastStatusCommandSequence in 0..lastCommandSequence &&
      lastStatusTimestampEpochSeconds >= 0 &&
      lastStatusExitCode in 0..255 &&
      (lastSupervisorBootId == null || lastSupervisorBootId.matches(Regex("[0-9a-f]{32}"))) &&
      (lastGatewayGeneration == null || lastGatewayGeneration.matches(Regex("[0-9a-f]{32}"))) &&
      (lastEnsureAttemptId == null || lastEnsureAttemptId > 0) &&
      ((lastGatewayGeneration == null) == (lastEnsureAttemptId == null)) &&
      (
        (
          lastStatusPlanId == null &&
            lastStatusSelectedCapabilities.isEmpty() &&
            lastStatusResolvedComponents.isEmpty()
        ) ||
          (
            lastStatusPlanId?.matches(Regex("[0-9a-f]{32}")) == true &&
              lastStatusSelectedCapabilities ==
              DevelopmentCapability.entries.filter(lastStatusSelectedCapabilities::contains) &&
              lastStatusResolvedComponents == resolveComponents(lastStatusSelectedCapabilities)
          )
      ) &&
      lastStatusReadyCapabilities ==
      DevelopmentCapability.entries.filter(lastStatusReadyCapabilities::contains) &&
      lastStatusReadyCapabilities.all(lastStatusSelectedCapabilities::contains) &&
      (
        lastStatusCurrentComponent == null ||
          lastStatusCurrentComponent in lastStatusResolvedComponents
      ) &&
      (
        (lastStatusCompletedBytes == null && lastStatusTotalBytes == null) ||
          (
            lastStatusCompletedBytes != null &&
              lastStatusTotalBytes != null &&
              lastStatusCompletedBytes in 0..lastStatusTotalBytes &&
              lastStatusTotalBytes > 0
          )
      ) &&
      (
        lastStatusStage == null ||
          (
            (
              lastStatusStage == SupervisorStatusStage.DownloadingNode ||
                lastStatusStage == SupervisorStatusStage.DownloadingOpenClaw ||
                lastStatusStage == SupervisorStatusStage.DownloadingComponent
            ) ==
              (lastStatusCompletedBytes != null)
          )
      ) &&
      (
        (lastStatusSequence == 0L && lastStatusStage == null && lastSupervisorBootId == null) ||
          (lastStatusSequence > 0 && lastStatusTimestampEpochSeconds > 0 && lastStatusStage != null && lastSupervisorBootId != null)
      )

  fun lastStatus(): SupervisorStatus? =
    lastStatusStage?.let { stage ->
      SupervisorStatus(
        supervisorBootId = checkNotNull(lastSupervisorBootId),
        eventSequence = lastStatusSequence,
        commandSequence = lastStatusCommandSequence,
        timestampEpochSeconds = lastStatusTimestampEpochSeconds,
        stage = stage,
        gatewayGeneration = lastGatewayGeneration,
        ensureAttemptId = lastEnsureAttemptId,
        exitCode = lastStatusExitCode,
        planId = lastStatusPlanId,
        selectedCapabilities = lastStatusSelectedCapabilities,
        resolvedComponents = lastStatusResolvedComponents,
        readyCapabilities = lastStatusReadyCapabilities,
        currentComponent = lastStatusCurrentComponent,
        completedBytes = lastStatusCompletedBytes,
        totalBytes = lastStatusTotalBytes,
      )
    }

  override fun toString(): String =
    "SupervisorControlSession(supervisorId=$supervisorId, secretHex=<redacted>, " +
      "lastCommandSequence=$lastCommandSequence, lastStatusSequence=$lastStatusSequence, " +
      "pendingCommandSequence=$pendingCommandSequence, pendingCommand=$pendingCommand, " +
      "lastStatusStage=$lastStatusStage)"
}

private fun isSupervisorContentUri(value: String): Boolean =
  runCatching {
    val uri = value.toUri()
    uri.scheme == "content" && !uri.authority.isNullOrBlank()
  }.getOrDefault(false)

internal sealed interface SupervisorCommandWriteResult {
  data class Written(
    val sequence: Long,
  ) : SupervisorCommandWriteResult

  data object MissingSession : SupervisorCommandWriteResult

  data object CheckpointFailed : SupervisorCommandWriteResult

  data object WriteFailed : SupervisorCommandWriteResult
}

internal sealed interface SupervisorStatusReadResult {
  data class Updated(
    val status: SupervisorStatus,
  ) : SupervisorStatusReadResult

  data class Unchanged(
    val status: SupervisorStatus?,
  ) : SupervisorStatusReadResult

  data class Stale(
    val lastVerifiedStatus: SupervisorStatus?,
  ) : SupervisorStatusReadResult

  data object MissingSession : SupervisorStatusReadResult

  data object Missing : SupervisorStatusReadResult

  data object TooLarge : SupervisorStatusReadResult

  data object InvalidEncoding : SupervisorStatusReadResult

  data class VerificationFailed(
    val error: SupervisorControlVerificationError,
  ) : SupervisorStatusReadResult

  data object SequenceViolation : SupervisorStatusReadResult

  data object CheckpointFailed : SupervisorStatusReadResult

  data object Failed : SupervisorStatusReadResult
}

internal interface SupervisorControlRepository {
  fun load(): SupervisorControlSession?

  fun activate(activation: SupervisorActivation): Boolean

  fun writeCommand(
    command: SupervisorCommand,
    nowEpochSeconds: Long,
  ): SupervisorCommandWriteResult

  fun readStatus(nowEpochSeconds: Long): SupervisorStatusReadResult

  fun clear()
}

internal class MediaStoreSupervisorControlRepository(
  context: Context,
  private val preferences: SecurePrefs,
) : SupervisorControlRepository {
  private val appContext = context.applicationContext
  private val json = Json { ignoreUnknownKeys = false }

  @Synchronized
  override fun load(): SupervisorControlSession? = loadUnlocked()

  @Synchronized
  override fun activate(activation: SupervisorActivation): Boolean {
    if (!activation.isValid()) return false
    val current = loadUnlocked()
    if (current != null && current.supervisorId == activation.supervisorId) {
      return current.secretHex == activation.secretHex &&
        current.commandUri == activation.commandUri &&
        current.statusUri == activation.statusUri
    }
    return saveUnlocked(
      SupervisorControlSession(
        supervisorId = activation.supervisorId,
        secretHex = activation.secretHex,
        commandUri = activation.commandUri,
        statusUri = activation.statusUri,
      ),
    )
  }

  @Synchronized
  override fun writeCommand(
    command: SupervisorCommand,
    nowEpochSeconds: Long,
  ): SupervisorCommandWriteResult {
    val current = loadUnlocked() ?: return SupervisorCommandWriteResult.MissingSession
    val sequence = current.lastCommandSequence + 1
    if (
      !saveUnlocked(
        current.copy(
          lastCommandSequence = sequence,
          pendingCommandSequence = sequence,
          pendingCommand = command,
        ),
      )
    ) {
      return SupervisorCommandWriteResult.CheckpointFailed
    }
    val content =
      signSupervisorCommand(
        secretHex = current.secretHex,
        supervisorId = current.supervisorId,
        sequence = sequence,
        timestampEpochSeconds = nowEpochSeconds,
        command = command,
      )
    val written =
      runCatching {
        appContext.contentResolver.openOutputStream(current.commandUri.toUri(), "wt")?.use { output ->
          output.write(content.toByteArray(StandardCharsets.UTF_8))
        } ?: error("Supervisor command file is missing")
      }.isSuccess
    if (!written) {
      val checkpointed =
        loadUnlocked()?.let { saved ->
          saveUnlocked(saved.copy(pendingCommandSequence = null, pendingCommand = null))
        } == true
      return if (checkpointed) {
        SupervisorCommandWriteResult.WriteFailed
      } else {
        SupervisorCommandWriteResult.CheckpointFailed
      }
    }
    return SupervisorCommandWriteResult.Written(sequence)
  }

  @Synchronized
  override fun readStatus(nowEpochSeconds: Long): SupervisorStatusReadResult {
    val current = loadUnlocked() ?: return SupervisorStatusReadResult.MissingSession
    val bytes =
      try {
        val input =
          appContext.contentResolver.openInputStream(current.statusUri.toUri())
            ?: return SupervisorStatusReadResult.Missing
        input.use {
          val output = ByteArrayOutputStream()
          val buffer = ByteArray(1024)
          var total = 0
          while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            total += count
            if (total > SUPERVISOR_CONTROL_MAX_BYTES) return SupervisorStatusReadResult.TooLarge
            output.write(buffer, 0, count)
          }
          output.toByteArray()
        }
      } catch (_: java.io.FileNotFoundException) {
        return SupervisorStatusReadResult.Missing
      } catch (_: Exception) {
        return SupervisorStatusReadResult.Failed
      }
    if (bytes.isEmpty()) return SupervisorStatusReadResult.Unchanged(current.lastStatus())
    val line =
      runCatching {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString()
      }.getOrElse { return SupervisorStatusReadResult.InvalidEncoding }
    if (!line.endsWith('\n') || line.dropLast(1).contains('\n')) {
      return SupervisorStatusReadResult.InvalidEncoding
    }
    val verified =
      verifySupervisorStatus(
        line = line,
        expectedSupervisorId = current.supervisorId,
        secretHex = current.secretHex,
        nowEpochSeconds = nowEpochSeconds,
      )
    val status =
      when (verified) {
        is SupervisorStatusVerificationResult.Accepted -> verified.status
        is SupervisorStatusVerificationResult.Rejected -> {
          if (verified.error == SupervisorControlVerificationError.Expired) {
            return SupervisorStatusReadResult.Stale(current.lastStatus())
          }
          return SupervisorStatusReadResult.VerificationFailed(verified.error)
        }
      }
    if (status.eventSequence < current.lastStatusSequence) {
      return SupervisorStatusReadResult.SequenceViolation
    }
    if (status.commandSequence > current.lastCommandSequence) {
      return SupervisorStatusReadResult.SequenceViolation
    }
    if (status.eventSequence == current.lastStatusSequence) {
      return SupervisorStatusReadResult.Unchanged(status)
    }
    val pendingFinished =
      current.pendingCommandSequence?.let { pendingSequence ->
        current.pendingCommand?.let { pendingCommand ->
          status.commandSequence >= pendingSequence && pendingCommand.isTerminalAt(status.stage)
        }
      } == true
    val updated =
      current.copy(
        lastStatusSequence = status.eventSequence,
        lastSupervisorBootId = status.supervisorBootId,
        lastStatusCommandSequence = status.commandSequence,
        lastStatusTimestampEpochSeconds = status.timestampEpochSeconds,
        lastStatusStage = status.stage,
        lastGatewayGeneration = status.gatewayGeneration,
        lastEnsureAttemptId = status.ensureAttemptId,
        lastStatusExitCode = status.exitCode,
        lastStatusPlanId = status.planId,
        lastStatusSelectedCapabilities = status.selectedCapabilities,
        lastStatusResolvedComponents = status.resolvedComponents,
        lastStatusReadyCapabilities = status.readyCapabilities,
        lastStatusCurrentComponent = status.currentComponent,
        lastStatusCompletedBytes = status.completedBytes,
        lastStatusTotalBytes = status.totalBytes,
        pendingCommandSequence = current.pendingCommandSequence.takeUnless { pendingFinished },
        pendingCommand = current.pendingCommand.takeUnless { pendingFinished },
      )
    if (!saveUnlocked(updated)) return SupervisorStatusReadResult.CheckpointFailed
    return SupervisorStatusReadResult.Updated(status)
  }

  @Synchronized
  override fun clear() {
    val current = loadUnlocked()
    if (current != null) {
      listOf(current.commandUri, current.statusUri).forEach { value ->
        runCatching { appContext.contentResolver.delete(value.toUri(), null, null) }
      }
    }
    preferences.removeSynchronously(SUPERVISOR_CONTROL_KEY)
  }

  private fun loadUnlocked(): SupervisorControlSession? {
    val encoded = preferences.getString(SUPERVISOR_CONTROL_KEY) ?: return null
    return runCatching { json.decodeFromString<SupervisorControlSession>(encoded) }
      .getOrNull()
      ?.takeIf(SupervisorControlSession::isValid)
  }

  private fun saveUnlocked(session: SupervisorControlSession): Boolean {
    if (!session.isValid()) return false
    return preferences.putStringSynchronously(SUPERVISOR_CONTROL_KEY, json.encodeToString(session))
  }
}
