package ai.openclaw.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidScreenshotFixtureTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val request = AndroidScreenshotFixture.createRequester()

  @Test
  fun providesDeterministicProductionScreenData() {
    val sessions =
      json
        .parseToJsonElement(request("sessions.list", null))
        .jsonObject["sessions"]
        ?.jsonArray
        .orEmpty()
    val metadata =
      json
        .parseToJsonElement(request("chat.metadata", null))
        .jsonObject
    assertEquals(3, sessions.size)
    assertEquals(
      AndroidScreenshotFixture.primarySessionTitle,
      sessions
        .first()
        .jsonObject["displayName"]
        ?.jsonPrimitive
        ?.content,
    )
    assertEquals(1, metadata["models"]?.jsonArray?.size)
    assertEquals(6, metadata["commands"]?.jsonArray?.size)
  }

  @Test
  fun providesDeterministicRecentChatHistory() {
    val history =
      json
        .parseToJsonElement(request("chat.history", null))
        .jsonObject
    val messages = history["messages"]?.jsonArray.orEmpty().takeLast(10)

    assertEquals(
      listOf(
        listOf("user", "What is blocking the Android release?", "1783555020000"),
        listOf(
          "assistant",
          "Two review threads are still open on the release branch, and the localization sync needs one more pass. " +
            "Once those land, the changelog draft is ready for review and the tag can go out.",
          "1783555080000",
        ),
        listOf("user", "[System] Continue the interrupted turn.", "1783555100000"),
        listOf("user", "[System] Gateway restarted during the Android release update.", "1783555120000"),
        listOf("user", "Summarize the open review feedback for me.", "1783555140000"),
        listOf(
          "assistant",
          "The release check is ready:\n\n```kotlin\nval ready = lint && tests\n```\n\n" +
            "Review https://openclaw.ai before tagging.",
          "1783555200000",
        ),
        listOf("system", "Compaction", "1783555220000"),
        listOf("system", "Reset", "1783555240000"),
        listOf("user", "Draft a short status update for the team.", "1783555260000"),
        listOf(
          "assistant",
          "The Android release is close. Two review follow-ups and one localization pass remain; once those land, " +
            "the changelog can be reviewed and the tag can go out.",
          "1783555320000",
        ),
      ),
      messages.map { message ->
        val fields = message.jsonObject
        listOf(
          fields["role"]?.jsonPrimitive?.content,
          fields["content"]?.jsonPrimitive?.content,
          fields["timestamp"]?.jsonPrimitive?.content,
        )
      },
    )

    val restartRecovery = messages[2].jsonObject["provenance"]?.jsonObject
    assertEquals("internal_system", restartRecovery?.get("kind")?.jsonPrimitive?.content)
    assertEquals("main_session_restart_recovery", restartRecovery?.get("sourceTool")?.jsonPrimitive?.content)
    val gatewayRestarted = messages[3].jsonObject["provenance"]?.jsonObject
    assertEquals("restart-sentinel", gatewayRestarted?.get("sourceTool")?.jsonPrimitive?.content)
    val compaction = messages[6].jsonObject["__openclaw"]?.jsonObject
    assertEquals("compaction", compaction?.get("kind")?.jsonPrimitive?.content)
    assertEquals("android-screenshot-compaction", compaction?.get("id")?.jsonPrimitive?.content)
    assertEquals("900000", compaction?.get("tokensBefore")?.jsonPrimitive?.content)
    assertEquals("24700", compaction?.get("tokensAfter")?.jsonPrimitive?.content)
    val reset = messages[7].jsonObject["__openclaw"]?.jsonObject
    assertEquals("reset", reset?.get("kind")?.jsonPrimitive?.content)
    assertEquals("android-screenshot-reset", reset?.get("id")?.jsonPrimitive?.content)
    val inFlightRun = history["inFlightRun"]?.jsonObject
    assertEquals("android-screenshot-active-run", inFlightRun?.get("runId")?.jsonPrimitive?.content)
    assertEquals("", inFlightRun?.get("text")?.jsonPrimitive?.content)
  }

  @Test
  fun rejectsUnexpectedGatewayCalls() {
    val error =
      assertThrows(IllegalStateException::class.java) {
        request("gateway.unexpected", null)
      }

    assertEquals(
      "Screenshot fixture does not implement gateway method gateway.unexpected with params null",
      error.message,
    )
  }
}
