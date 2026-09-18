package ai.openclaw.app.bootstrap

import ai.openclaw.app.supervisor.SUPERVISOR_CONTROL_PROTOCOL_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapHandoffProtocolTest {
  private val requestId = "a".repeat(32)
  private val secret = "b".repeat(64)
  private val now = 1_788_400_000L

  @Test
  fun signedProgressIsAccepted() {
    val line = signedProgress(now)

    assertEquals(
      BootstrapHandoffVerificationResult.Accepted(
        BootstrapHandoffEvent.Progress(1, now, SupervisorProgress.BootstrapExecuted),
      ),
      verify(line),
    )
  }

  @Test
  fun protocolStageNamesAreExplicitAndStable() {
    assertEquals(
      listOf("bootstrap_executed", "installing_supervisor", "starting_supervisor"),
      SupervisorProgress.entries.map(::supervisorProgressToHandoffWire),
    )
  }

  @Test
  fun signedReadyResultCarriesOnlyTheSupervisorProtocol() {
    val line =
      signBootstrapHandoffEnvelope(
        secretHex = secret,
        requestId = requestId,
        sequence = 4,
        timestampEpochSeconds = now,
        stage = SUPERVISOR_READY_STAGE,
        payloadJson = """{"protocolVersion":$SUPERVISOR_CONTROL_PROTOCOL_VERSION}""",
      )

    assertEquals(
      BootstrapHandoffVerificationResult.Accepted(
        BootstrapHandoffEvent.SupervisorReady(4, now, SUPERVISOR_CONTROL_PROTOCOL_VERSION),
      ),
      verify(line),
    )
  }

  @Test
  fun unexpectedSupervisorProtocolFailsClosed() {
    val line =
      signBootstrapHandoffEnvelope(
        secretHex = secret,
        requestId = requestId,
        sequence = 4,
        timestampEpochSeconds = now,
        stage = SUPERVISOR_READY_STAGE,
        payloadJson = """{"protocolVersion":${SUPERVISOR_CONTROL_PROTOCOL_VERSION + 1}}""",
      )

    assertEquals(
      BootstrapHandoffVerificationResult.Rejected(
        BootstrapHandoffVerificationError.UnexpectedSupervisorProtocol,
      ),
      verify(line),
    )
  }

  @Test
  fun modifiedPayloadFailsAuthenticationBeforeItCanBeUsed() {
    val line = signedProgress(now).replace("bootstrap_executed", "starting_supervisor")

    assertEquals(
      BootstrapHandoffVerificationResult.Rejected(
        BootstrapHandoffVerificationError.AuthenticationFailed,
      ),
      verify(line),
    )
  }

  @Test
  fun anotherRequestCannotReplayAnAuthenticatedEvent() {
    val line =
      signBootstrapHandoffEnvelope(
        secretHex = secret,
        requestId = "c".repeat(32),
        sequence = 1,
        timestampEpochSeconds = now,
        stage = "bootstrap_executed",
        payloadJson = "{}",
      )

    assertEquals(
      BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.WrongRequest),
      verify(line),
    )
  }

  @Test
  fun unknownEnvelopeFieldsAreRejected() {
    val line = signedProgress(now).dropLast(1) + ",\"extra\":true}"

    assertEquals(
      BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.Malformed),
      verify(line),
    )
  }

  @Test
  fun outOfRangeFailureExitCodeIsRejected() {
    val line =
      signBootstrapHandoffEnvelope(
        secretHex = secret,
        requestId = requestId,
        sequence = 2,
        timestampEpochSeconds = now,
        stage = FAILED_STAGE,
        payloadJson = """{"stage":"bootstrap_executed","exitCode":0}""",
      )

    assertEquals(
      BootstrapHandoffVerificationResult.Rejected(
        BootstrapHandoffVerificationError.InvalidFailurePayload,
      ),
      verify(line),
    )
  }

  @Test
  fun staleAndFutureEventsAreRejected() {
    val stale = signedProgress(now - BOOTSTRAP_HANDOFF_MAX_AGE_SECONDS - 1)
    val future = signedProgress(now + BOOTSTRAP_HANDOFF_MAX_FUTURE_SKEW_SECONDS + 1)

    assertEquals(
      BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.Expired),
      verify(stale),
    )
    assertEquals(
      BootstrapHandoffVerificationResult.Rejected(BootstrapHandoffVerificationError.Expired),
      verify(future),
    )
  }

  @Test
  fun signedEnvelopeNeverContainsTheSecret() {
    val line = signedProgress(now)

    assertFalse(line.contains(secret))
    assertTrue(line.contains("\"protocolVersion\":4"))
  }

  @Test
  fun hmacMatchesTheOpenSslBootstrapFixture() {
    assertEquals(
      "463f9bda3085da8f75ddb23764944dc7e02d42ef343a07348170bf73c6c8e5e6",
      bootstrapHandoffMac(
        secretHex = secret,
        requestId = requestId,
        protocolVersion = 4,
        sequence = 1,
        timestampEpochSeconds = 1_788_400_000L,
        stage = "bootstrap_executed",
        payload = "e30",
      ),
    )
  }

  private fun signedProgress(timestamp: Long): String =
    signBootstrapHandoffEnvelope(
      secretHex = secret,
      requestId = requestId,
      sequence = 1,
      timestampEpochSeconds = timestamp,
      stage = "bootstrap_executed",
      payloadJson = "{}",
    )

  private fun verify(line: String): BootstrapHandoffVerificationResult =
    verifyBootstrapHandoffLine(
      line = line,
      expectedRequestId = requestId,
      secretHex = secret,
      nowEpochSeconds = now,
    )
}
