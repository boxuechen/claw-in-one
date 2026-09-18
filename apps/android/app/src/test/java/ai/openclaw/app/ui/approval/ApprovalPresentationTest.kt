package ai.openclaw.app.ui.approval

import ai.openclaw.app.approval.ApprovalAttribution
import ai.openclaw.app.approval.ApprovalDetails
import ai.openclaw.app.approval.ApprovalFailure
import ai.openclaw.app.approval.ApprovalInboxState
import ai.openclaw.app.approval.ApprovalKind
import ai.openclaw.app.approval.ApprovalNotice
import ai.openclaw.app.approval.ApprovalOutcome
import ai.openclaw.app.approval.ApprovalRow
import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.approval.ApprovalSnapshot
import ai.openclaw.app.approval.ApprovalStatus
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.ui.chat.ChatCurrentWorkUiState
import ai.openclaw.app.ui.chat.ChatTimelineItem
import ai.openclaw.app.ui.chat.ChatTimelineSupplement
import ai.openclaw.app.ui.chat.buildChatTimeline
import ai.openclaw.app.ui.chat.chatTimelineItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalPresentationTest {
  @Test
  fun onlyCanonicalSourceReceivesCardsAndSettledRecordsReplacePending() {
    val first = approvalRow("one", "agent:main:first")
    val unknown = approvalRow("unknown", null)
    val conflict = approvalRow("conflict", "agent:main:first").copy(attribution = ApprovalAttribution(conflicted = true))
    val settled = ApprovalOutcome(ApprovalSnapshot.Terminal("done", ApprovalKind.Plugin, ApprovalStatus.Denied, "deny"), first.copy(id = "done"), first.attribution)
    val state =
      ApprovalInboxState(
        rows = listOf(first, unknown, conflict),
        outcomes = listOf(settled),
        sessionKeys = mapOf(ApprovalSession("first", "main") to "agent:main:first"),
      )
    assertEquals(listOf("one", "done"), state.reviewsFor(ApprovalSession("first", "main")).map { it.id })
    assertTrue(state.reviewsFor(ApprovalSession("agent:main:parent")).isEmpty())
    assertEquals(listOf("unknown", "conflict"), state.unattributed.map { it.id })
    assertTrue(state.review("one") is ApprovalReview.Pending)
    assertTrue(state.review("done") is ApprovalReview.Settled)
    assertNull(state.review("missing"))
    assertTrue(ApprovalInboxState().reviewsFor(ApprovalSession("first", "main")).isEmpty())
  }

  @Test
  fun actionsRequireLiveCanonicalRecordAndNeverInventADecision() {
    val row = approvalRow()
    assertTrue(approvalCanDecide(row, true, 11))
    assertFalse(approvalCanDecide(row, false, 11))
    assertFalse(approvalCanDecide(row, true, row.expiresAtMs))
    assertFalse(approvalCanDecide(row.copy(details = null), true, 11))
    assertFalse(approvalCanDecide(row.copy(resolvingDecision = "deny"), true, 11))
    assertFalse(approvalCanDecide(row.copy(failure = ApprovalFailure.OutcomeUnknown), true, 11))
    assertEquals(listOf("allow-once", "deny"), approvalActions(listOf("allow-once", "deny", "future")).map { it.decision })
    assertEquals(listOf("Allow Once", "Allow Always", "Deny"), approvalActions(listOf("allow-once", "allow-always", "deny")).map { it.label })
    assertEquals("Checking decision…", approvalReviewStatus(ApprovalReview.Pending(row.copy(failure = ApprovalFailure.OutcomeUnknown)), 11))
    assertEquals("Checking expiry…", approvalReviewStatus(ApprovalReview.Pending(row), row.expiresAtMs))
  }

  @Test
  fun terminalPresentationRetainsCanonicalDetailsWithoutAnEarlierPendingCard() {
    val terminal = ApprovalSnapshot.Terminal("one", ApprovalKind.Plugin, ApprovalStatus.Allowed, "allow-once", details = approvalRow().details)
    val review = ApprovalReview.Settled(ApprovalOutcome(terminal, null, ApprovalAttribution()))
    assertEquals("Install Notes", approvalTitle(review.details))
    assertEquals("Allowed. Check the task for the operation result.", approvalReviewStatus(review, 20))
    val local = ApprovalNotice("one", ApprovalStatus.Denied, "deny", true, 1)
    assertEquals("Approval denied.", approvalNoticeText(local))
    assertEquals("A prior response already denied this approval.", approvalNoticeText(local.copy(appliedHere = false)))
  }

  @Test
  fun approvalCardsPrecedeTheSingleCurrentWorkRowAndKeepStableKeysAcrossSettlement() {
    val row = approvalRow()
    val pending = ApprovalReview.Pending(row)
    val settled = ApprovalReview.Settled(ApprovalOutcome(ApprovalSnapshot.Terminal(row.id, row.kind, ApprovalStatus.Denied, "deny"), row, row.attribution))

    val tool = ChatPendingToolCall("call", "android_use", startedAtMs = 10)

    fun timeline(review: ApprovalReview) =
      buildChatTimeline(
        emptyList(),
        1,
        listOf(tool),
        null,
        approvals = listOf(review),
        supplement =
          ChatTimelineSupplement(
            currentWork = ChatCurrentWorkUiState(progress = null, tools = listOf(tool)),
          ),
      )
    val before = timeline(pending)
    val after = timeline(settled)
    assertTrue(before.items.indexOfFirst { it is ChatTimelineItem.Approval } < before.items.indexOfFirst { it is ChatTimelineItem.CurrentWork })
    assertEquals(chatTimelineItemKey(ChatTimelineItem.Approval(pending)), chatTimelineItemKey(ChatTimelineItem.Approval(settled)))
    assertNotEquals(before.latestContentVersion, after.latestContentVersion)
    assertTrue(before.items.none { it is ChatTimelineItem.TranscriptMessage })
  }
}

internal fun approvalRow(
  id: String = "one",
  source: String? = "agent:main:first",
) = ApprovalRow(
  id,
  ApprovalKind.Plugin,
  10,
  Long.MAX_VALUE,
  details = ApprovalDetails.Plugin("Install Notes", "Install Notes on this phone?\n\nArtifact SHA-256: sample-digest", "Signer: sample-signer", "warning", "claw-in-one", "android_install", null, emptySet(), "main", emptyList()),
  allowedDecisions = listOf("allow-once", "deny"),
  attribution = ApprovalAttribution(source, setOf("agent:main:parent")),
)
