package ai.openclaw.app

import ai.openclaw.app.gateway.GatewayErrorDetails
import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.node.nodeConnectFailureNeedsApprovalRefresh
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayNodeApprovalStateTest {
  @Test
  fun parsesGatewayNodeApprovalState() {
    assertEquals(GatewayNodeApprovalState.Approved, parseGatewayNodeApprovalState("approved"))
    assertEquals(GatewayNodeApprovalState.PendingApproval, parseGatewayNodeApprovalState("pending-approval"))
    assertEquals(GatewayNodeApprovalState.PendingReapproval, parseGatewayNodeApprovalState("pending-reapproval"))
    assertEquals(GatewayNodeApprovalState.Unapproved, parseGatewayNodeApprovalState("unapproved"))
    assertEquals(GatewayNodeApprovalState.Loading, parseGatewayNodeApprovalState(null))
    assertEquals(GatewayNodeApprovalState.Loading, parseGatewayNodeApprovalState("future-state"))
  }

  @Test
  fun nodePairingFailuresRefreshCurrentNodeApproval() {
    assertTrue(
      nodeConnectFailureNeedsApprovalRefresh(
        GatewaySession.ErrorShape(
          code = "NOT_PAIRED",
          message = "pairing required",
          details =
            GatewayErrorDetails(
              code = "PAIRING_REQUIRED",
              canRetryWithDeviceToken = false,
              recommendedNextStep = "wait_then_retry",
              pauseReconnect = false,
              reason = "not-paired",
            ),
        ),
      ),
    )
    assertFalse(
      nodeConnectFailureNeedsApprovalRefresh(
        GatewaySession.ErrorShape(
          code = "UNAUTHORIZED",
          message = "token mismatch",
          details =
            GatewayErrorDetails(
              code = "AUTH_TOKEN_MISMATCH",
              canRetryWithDeviceToken = false,
              recommendedNextStep = null,
            ),
        ),
      ),
    )
  }

  @Test
  fun parsesNodeListApprovalFields() {
    val node =
      parseGatewayNodeApprovalRecord(
        Json.parseToJsonElement(
          """
          {
            "nodeId": "android-node",
            "paired": true,
            "connected": true,
            "approvalState": "pending-approval",
            "pendingRequestId": "request-1",
            "caps": ["device"],
            "commands": ["device.status"]
          }
          """.trimIndent(),
        ),
      )

    requireNotNull(node)
    assertEquals(GatewayNodeApprovalState.PendingApproval, node.approvalState)
    assertEquals("request-1", node.pendingRequestId)
  }

  @Test
  fun parsesSplitNodeListShapeFromGateway() {
    val root =
      Json
        .parseToJsonElement(
          """
          {
            "pending": [
              {
                "nodeId": "pending-node",
                "paired": false,
                "connected": false,
                "approvalState": "pending-approval",
                "pendingRequestId": "request-pending"
              }
            ],
            "paired": [
              {
                "nodeId": "self",
                "paired": true,
                "connected": true,
                "approvalState": "approved",
                "caps": ["device"],
                "commands": ["device.status"]
              }
            ]
          }
          """.trimIndent(),
        ).jsonObject

    val nodes = parseGatewayNodeApprovalRecords(root)

    assertEquals(2, nodes.size)
    assertEquals(
      GatewayNodeCapabilityApproval.Approved,
      currentNodeCapabilityApproval(nodes = nodes, selfNodeId = "self"),
    )
  }

  @Test
  fun treatsMissingNodeApprovalStateAsUnsupported() {
    val node =
      parseGatewayNodeApprovalRecord(
        Json.parseToJsonElement("""{"nodeId":"android-node","paired":true,"connected":true}"""),
      )

    requireNotNull(node)
    assertEquals(GatewayNodeApprovalState.Unsupported, node.approvalState)
    assertEquals(
      GatewayNodeCapabilityApproval.Unsupported,
      currentNodeCapabilityApproval(nodes = listOf(node), selfNodeId = "android-node"),
    )
    assertNull(node.pendingRequestId)
  }

  @Test
  fun resolvesCurrentPhoneNodeApprovalState() {
    val nodes =
      listOf(
        GatewayNodeApprovalRecord(
          id = "other",
          approvalState = GatewayNodeApprovalState.Approved,
          pendingRequestId = null,
        ),
        GatewayNodeApprovalRecord(
          id = "self",
          approvalState = GatewayNodeApprovalState.PendingApproval,
          pendingRequestId = "request-self",
        ),
      )

    assertEquals(
      GatewayNodeCapabilityApproval.PendingApproval("request-self"),
      currentNodeCapabilityApproval(nodes = nodes, selfNodeId = "self"),
    )
    assertEquals(
      GatewayNodeCapabilityApproval.Loading,
      currentNodeCapabilityApproval(nodes = nodes, selfNodeId = "missing"),
    )
  }

  @Test
  fun resolvesOnlyCurrentPhonePendingPairingRequest() {
    val root =
      Json
        .parseToJsonElement(
          """
          {
            "pending": [
              {"requestId":"request-other","nodeId":"other"},
              {"requestId":"request-self","nodeId":"self"}
            ],
            "paired": [
              {"nodeId":"self"}
            ]
          }
          """.trimIndent(),
        ).jsonObject

    assertEquals("request-self", currentPhoneNodePairingRequestId(root, selfNodeId = " self "))
    assertNull(currentPhoneNodePairingRequestId(root, selfNodeId = "missing"))
  }

  @Test
  fun rejectsMalformedCurrentPhonePairingRequestId() {
    val root =
      Json
        .parseToJsonElement(
          """
          {
            "pending": [
              {"requestId":"contains spaces","nodeId":"self"},
              {"requestId":42,"nodeId":"self"}
            ]
          }
          """.trimIndent(),
        ).jsonObject

    assertNull(currentPhoneNodePairingRequestId(root, selfNodeId = "self"))
  }

  @Test
  fun ignoresStaleNodeApprovalRefreshResults() {
    val guard = LatestGatewayRefreshGuard()
    var approvalState = GatewayNodeApprovalState.Loading
    val staleRefresh = guard.begin()
    val currentRefresh = guard.begin()

    assertFalse(guard.publishIfCurrent(staleRefresh) { approvalState = GatewayNodeApprovalState.Approved })
    assertTrue(
      guard.publishIfCurrent(currentRefresh) { approvalState = GatewayNodeApprovalState.PendingReapproval },
    )
    assertEquals(GatewayNodeApprovalState.PendingReapproval, approvalState)
  }
}
