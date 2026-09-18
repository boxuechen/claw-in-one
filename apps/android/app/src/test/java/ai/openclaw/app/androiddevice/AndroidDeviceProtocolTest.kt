package ai.openclaw.app.androiddevice

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceProtocolTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun parsesOnlyInternallyConsistentSnapshots() {
    val snapshot =
      parseAndroidDeviceSnapshot(
        """{"protocolVersion":1,"status":"ready","paired":true,"connected":true,"target":{"id":"${"a".repeat(64)}","product":"shiba","model":"Pixel 8","androidApi":"37"}}""",
        json,
      )

    assertEquals(AndroidDeviceStatus.Ready, snapshot?.status)
    assertNull(snapshot?.verificationId)
    assertEquals("Pixel 8", snapshot?.target?.model)
    assertEquals(37, snapshot?.target?.androidApi)
    assertNull(
      parseAndroidDeviceSnapshot(
        """{"protocolVersion":1,"status":"ready","paired":true,"connected":false,"target":{"id":"${"a".repeat(64)}","product":"shiba","model":"Pixel 8","androidApi":"37"}}""",
        json,
      ),
    )
    assertNull(parseAndroidDeviceSnapshot("""{"protocolVersion":2,"status":"setup_required","paired":false,"connected":false,"target":null}""", json))
  }

  @Test
  fun mutationPayloadsCarryOnlyTheVersionedContract() {
    val pair = androidDevicePairParams("10.0.0.2:41001", "654321", "b".repeat(32))
    val connect = androidDeviceConnectParams(null)
    val verify = androidDeviceVerifyReconnectParams("c".repeat(32), "10.0.0.2:42001")

    assertTrue(pair.contains("\"protocolVersion\":1"))
    assertTrue(pair.contains("\"challengeId\":\"${"b".repeat(32)}\""))
    assertEquals("""{"protocolVersion":1}""", connect)
    assertTrue(verify.contains("\"verificationId\":\"${"c".repeat(32)}\""))
    assertEquals(androidDeviceMethods.size, 5)
  }

  @Test
  fun parsesReconnectEvidenceOnlyOnAReadySnapshot() {
    val verificationId = "c".repeat(32)
    assertEquals(
      verificationId,
      parseAndroidDeviceSnapshot(
        """{"protocolVersion":1,"status":"ready","paired":true,"connected":true,"verificationId":"$verificationId","target":{"id":"${"a".repeat(64)}","product":"shiba","model":"Pixel 8","androidApi":"37"}}""",
        json,
      )?.verificationId,
    )
    assertNull(
      parseAndroidDeviceSnapshot(
        """{"protocolVersion":1,"status":"offline","paired":true,"connected":false,"verificationId":"$verificationId","target":{"id":"${"a".repeat(64)}","product":"shiba","model":"Pixel 8","androidApi":"37"}}""",
        json,
      ),
    )
  }
}
