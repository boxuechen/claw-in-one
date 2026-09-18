package ai.openclaw.app.bootstrap

import ai.openclaw.app.supervisor.SUPERVISOR_CONTROL_PROTOCOL_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val BOOTSTRAP_HANDOFF_PROTOCOL_VERSION = 4
internal const val BOOTSTRAP_HANDOFF_MAX_BYTES = 256 * 1024
internal const val BOOTSTRAP_HANDOFF_MAX_LINE_BYTES = 16 * 1024
internal const val BOOTSTRAP_HANDOFF_MAX_AGE_SECONDS = 12 * 60 * 60L
internal const val BOOTSTRAP_HANDOFF_MAX_FUTURE_SKEW_SECONDS = 5 * 60L

internal enum class SupervisorProgress(
  val wireName: String,
) {
  BootstrapExecuted("bootstrap_executed"),
  InstallingSupervisor("installing_supervisor"),
  StartingSupervisor("starting_supervisor"),
}

internal sealed interface BootstrapHandoffEvent {
  val sequence: Long
  val timestampEpochSeconds: Long

  data class Progress(
    override val sequence: Long,
    override val timestampEpochSeconds: Long,
    val stage: SupervisorProgress,
  ) : BootstrapHandoffEvent

  data class SupervisorReady(
    override val sequence: Long,
    override val timestampEpochSeconds: Long,
    val protocolVersion: Int,
  ) : BootstrapHandoffEvent

  data class Failed(
    override val sequence: Long,
    override val timestampEpochSeconds: Long,
    val stage: SupervisorProgress,
    val exitCode: Int,
  ) : BootstrapHandoffEvent
}

internal enum class BootstrapHandoffVerificationError {
  Malformed,
  WrongRequest,
  AuthenticationFailed,
  Expired,
  UnsupportedVersion,
  InvalidPayloadEncoding,
  InvalidProgressPayload,
  InvalidFailurePayload,
  InvalidSupervisorFields,
  UnexpectedSupervisorProtocol,
}

internal sealed interface BootstrapHandoffVerificationResult {
  data class Accepted(
    val event: BootstrapHandoffEvent,
  ) : BootstrapHandoffVerificationResult

  data class Rejected(
    val error: BootstrapHandoffVerificationError,
  ) : BootstrapHandoffVerificationResult
}

private data class BootstrapHandoffEnvelope(
  val requestId: String,
  val protocolVersion: Int,
  val sequence: Long,
  val timestampEpochSeconds: Long,
  val stage: String,
  val payload: String,
  val hmac: String,
)

private val handoffJson = Json { ignoreUnknownKeys = false }
private val handoffRequestIdRegex = Regex("[0-9a-f]{32}")
private val handoffSecretRegex = Regex("[0-9a-f]{64}")
private val handoffMacRegex = Regex("[0-9a-f]{64}")
private val handoffEncodedPayloadRegex = Regex("[A-Za-z0-9_-]{2,16384}")

internal fun verifyBootstrapHandoffLine(
  line: String,
  expectedRequestId: String,
  secretHex: String,
  nowEpochSeconds: Long,
): BootstrapHandoffVerificationResult {
  if (
    line.isBlank() ||
    line.toByteArray(StandardCharsets.UTF_8).size > BOOTSTRAP_HANDOFF_MAX_LINE_BYTES ||
    !expectedRequestId.matches(handoffRequestIdRegex) ||
    !secretHex.matches(handoffSecretRegex)
  ) {
    return BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.Malformed)
  }
  val envelope =
    parseBootstrapHandoffEnvelope(line)
      ?: return BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.Malformed)
  if (envelope.requestId != expectedRequestId) {
    return BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.WrongRequest)
  }
  if (envelope.protocolVersion != BOOTSTRAP_HANDOFF_PROTOCOL_VERSION) {
    return BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.UnsupportedVersion)
  }
  val expectedMac =
    bootstrapHandoffMac(
      secretHex = secretHex,
      requestId = envelope.requestId,
      protocolVersion = envelope.protocolVersion,
      sequence = envelope.sequence,
      timestampEpochSeconds = envelope.timestampEpochSeconds,
      stage = envelope.stage,
      payload = envelope.payload,
    )
  if (
    !envelope.hmac.matches(handoffMacRegex) ||
    !MessageDigest.isEqual(
      envelope.hmac.toByteArray(StandardCharsets.US_ASCII),
      expectedMac.toByteArray(StandardCharsets.US_ASCII),
    )
  ) {
    return BootstrapHandoffVerificationResult.Rejected(
      BootstrapHandoffVerificationError.AuthenticationFailed,
    )
  }
  if (
    envelope.timestampEpochSeconds <= 0 ||
    envelope.timestampEpochSeconds < nowEpochSeconds - BOOTSTRAP_HANDOFF_MAX_AGE_SECONDS ||
    envelope.timestampEpochSeconds > nowEpochSeconds + BOOTSTRAP_HANDOFF_MAX_FUTURE_SKEW_SECONDS
  ) {
    return BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.Expired)
  }
  if (envelope.sequence !in 1..SupervisorProgress.entries.size + 2L) {
    return BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.Malformed)
  }
  val payload =
    decodeBootstrapHandoffPayload(envelope.payload)
      ?: return BootstrapHandoffVerificationResult.Rejected(
        BootstrapHandoffVerificationError.InvalidPayloadEncoding,
      )
  return when (envelope.stage) {
    SUPERVISOR_READY_STAGE -> verifySupervisorReadyEvent(envelope, payload)
    FAILED_STAGE ->
      parseFailedEvent(envelope, payload)
        ?.let(BootstrapHandoffVerificationResult::Accepted)
        ?: BootstrapHandoffVerificationResult.Rejected(
          BootstrapHandoffVerificationError.InvalidFailurePayload,
        )
    else ->
      parseProgressEvent(envelope, payload)
        ?.let(BootstrapHandoffVerificationResult::Accepted)
        ?: BootstrapHandoffVerificationResult.Rejected(
          BootstrapHandoffVerificationError.InvalidProgressPayload,
        )
  }
}

private fun parseBootstrapHandoffEnvelope(line: String): BootstrapHandoffEnvelope? {
  val objectValue =
    runCatching { handoffJson.parseToJsonElement(line).jsonObject }
      .getOrNull()
      ?: return null
  if (
    objectValue.keys !=
    setOf("requestId", "protocolVersion", "sequence", "timestamp", "stage", "payload", "hmac")
  ) {
    return null
  }
  val requestId = objectValue.string("requestId") ?: return null
  val protocolVersion = objectValue["protocolVersion"]?.jsonPrimitive?.intOrNull ?: return null
  val sequence = objectValue["sequence"]?.jsonPrimitive?.longOrNull ?: return null
  val timestamp = objectValue["timestamp"]?.jsonPrimitive?.longOrNull ?: return null
  val stage = objectValue.string("stage") ?: return null
  val payload = objectValue.string("payload") ?: return null
  val hmac = objectValue.string("hmac") ?: return null
  if (
    !requestId.matches(handoffRequestIdRegex) ||
    !stage.matches(Regex("[a-z_]{3,48}")) ||
    !payload.matches(handoffEncodedPayloadRegex)
  ) {
    return null
  }
  return BootstrapHandoffEnvelope(
    requestId = requestId,
    protocolVersion = protocolVersion,
    sequence = sequence,
    timestampEpochSeconds = timestamp,
    stage = stage,
    payload = payload,
    hmac = hmac,
  )
}

private fun decodeBootstrapHandoffPayload(encoded: String): JsonObject? =
  runCatching {
    val bytes = Base64.getUrlDecoder().decode(encoded)
    check(bytes.size <= BOOTSTRAP_HANDOFF_MAX_LINE_BYTES)
    handoffJson.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)).jsonObject
  }.getOrNull()

private fun parseProgressEvent(
  envelope: BootstrapHandoffEnvelope,
  payload: JsonObject,
): BootstrapHandoffEvent.Progress? {
  if (payload.isNotEmpty()) return null
  val progress = supervisorProgressFromHandoffWire(envelope.stage) ?: return null
  return BootstrapHandoffEvent.Progress(
    sequence = envelope.sequence,
    timestampEpochSeconds = envelope.timestampEpochSeconds,
    stage = progress,
  )
}

private fun verifySupervisorReadyEvent(
  envelope: BootstrapHandoffEnvelope,
  payload: JsonObject,
): BootstrapHandoffVerificationResult {
  if (payload.keys != setOf("protocolVersion")) {
    return BootstrapHandoffVerificationResult.Rejected(
      BootstrapHandoffVerificationError.InvalidSupervisorFields,
    )
  }
  val protocolVersion = payload["protocolVersion"]?.jsonPrimitive?.intOrNull
  if (protocolVersion == null || protocolVersion <= 0) {
    return BootstrapHandoffVerificationResult.Rejected(
      BootstrapHandoffVerificationError.InvalidSupervisorFields,
    )
  }
  if (protocolVersion != SUPERVISOR_CONTROL_PROTOCOL_VERSION) {
    return BootstrapHandoffVerificationResult.Rejected(
      BootstrapHandoffVerificationError.UnexpectedSupervisorProtocol,
    )
  }
  return BootstrapHandoffVerificationResult.Accepted(
    BootstrapHandoffEvent.SupervisorReady(
      sequence = envelope.sequence,
      timestampEpochSeconds = envelope.timestampEpochSeconds,
      protocolVersion = protocolVersion,
    ),
  )
}

private fun parseFailedEvent(
  envelope: BootstrapHandoffEnvelope,
  payload: JsonObject,
): BootstrapHandoffEvent.Failed? {
  if (payload.keys != setOf("stage", "exitCode")) return null
  val stage = payload.string("stage")?.let(::supervisorProgressFromHandoffWire) ?: return null
  val exitCode = payload["exitCode"]?.jsonPrimitive?.intOrNull ?: return null
  if (exitCode !in 1..255) return null
  return BootstrapHandoffEvent.Failed(
    sequence = envelope.sequence,
    timestampEpochSeconds = envelope.timestampEpochSeconds,
    stage = stage,
    exitCode = exitCode,
  )
}

internal fun signBootstrapHandoffEnvelope(
  secretHex: String,
  requestId: String,
  sequence: Long,
  timestampEpochSeconds: Long,
  stage: String,
  payloadJson: String,
): String {
  require(secretHex.matches(handoffSecretRegex))
  require(requestId.matches(handoffRequestIdRegex))
  val payload =
    Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray(StandardCharsets.UTF_8))
  val hmac =
    bootstrapHandoffMac(
      secretHex = secretHex,
      requestId = requestId,
      protocolVersion = BOOTSTRAP_HANDOFF_PROTOCOL_VERSION,
      sequence = sequence,
      timestampEpochSeconds = timestampEpochSeconds,
      stage = stage,
      payload = payload,
    )
  return buildString {
    append('{')
    append("\"requestId\":\"").append(requestId).append("\",")
    append("\"protocolVersion\":").append(BOOTSTRAP_HANDOFF_PROTOCOL_VERSION).append(',')
    append("\"sequence\":").append(sequence).append(',')
    append("\"timestamp\":").append(timestampEpochSeconds).append(',')
    append("\"stage\":\"").append(stage).append("\",")
    append("\"payload\":\"").append(payload).append("\",")
    append("\"hmac\":\"").append(hmac).append("\"}")
  }
}

internal fun bootstrapHandoffMac(
  secretHex: String,
  requestId: String,
  protocolVersion: Int,
  sequence: Long,
  timestampEpochSeconds: Long,
  stage: String,
  payload: String,
): String {
  val canonical =
    listOf(
      requestId,
      protocolVersion.toString(),
      sequence.toString(),
      timestampEpochSeconds.toString(),
      stage,
      payload,
    ).joinToString("\n")
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(secretHex.hexToBytes(), "HmacSHA256"))
  return mac
    .doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun String.hexToBytes(): ByteArray =
  chunked(2)
    .map { value -> value.toInt(16).toByte() }
    .toByteArray()

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.content

internal fun supervisorProgressToHandoffWire(progress: SupervisorProgress): String = progress.wireName

private fun supervisorProgressFromHandoffWire(value: String): SupervisorProgress? = SupervisorProgress.entries.firstOrNull { supervisorProgressToHandoffWire(it) == value }

internal const val SUPERVISOR_READY_STAGE = "supervisor_ready"
internal const val FAILED_STAGE = "failed"
