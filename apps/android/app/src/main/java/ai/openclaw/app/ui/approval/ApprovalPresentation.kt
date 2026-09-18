package ai.openclaw.app.ui.approval

import ai.openclaw.app.approval.ApprovalDetails
import ai.openclaw.app.approval.ApprovalFailure
import ai.openclaw.app.approval.ApprovalInboxState
import ai.openclaw.app.approval.ApprovalNotice
import ai.openclaw.app.approval.ApprovalOutcome
import ai.openclaw.app.approval.ApprovalRow
import ai.openclaw.app.approval.ApprovalSession
import ai.openclaw.app.approval.ApprovalStatus
import ai.openclaw.app.i18n.nativeString

/** Presentation only: every surface reads the same canonical inbox and dispatches its actions. */
internal sealed interface ApprovalReview {
  val id: String
  val details: ApprovalDetails?

  data class Pending(
    val row: ApprovalRow,
  ) : ApprovalReview {
    override val id get() = row.id
    override val details get() = row.details
  }

  data class Settled(
    val outcome: ApprovalOutcome,
  ) : ApprovalReview {
    override val id get() = outcome.approval.id
    override val details get() = outcome.row?.details ?: outcome.approval.details
  }
}

internal fun ApprovalInboxState.review(id: String): ApprovalReview? =
  rows.firstOrNull { it.id == id }?.let(ApprovalReview::Pending)
    ?: outcomes.firstOrNull { it.approval.id == id }?.let(ApprovalReview::Settled)

internal fun ApprovalInboxState.reviewsFor(session: ApprovalSession): List<ApprovalReview> {
  val key = canonicalKey(session)
  return (
    pendingFor(session).map(ApprovalReview::Pending) +
      outcomes.filter { !it.attribution.conflicted && it.attribution.sourceSessionKey == key }.map(ApprovalReview::Settled)
  ).sortedBy { review ->
    when (review) {
      is ApprovalReview.Pending -> review.row.createdAtMs
      is ApprovalReview.Settled -> review.outcome.row?.createdAtMs ?: review.outcome.approval.createdAtMs
    }
  }
}

internal data class ApprovalAction(
  val decision: String,
  val label: String,
)

internal fun approvalActions(decisions: List<String>): List<ApprovalAction> =
  decisions.mapNotNull {
    when (it) {
      "allow-once" -> ApprovalAction(it, nativeString("Allow Once"))
      "allow-always" -> ApprovalAction(it, nativeString("Allow Always"))
      "deny" -> ApprovalAction(it, nativeString("Deny"))
      else -> null
    }
  }

internal fun approvalTitle(details: ApprovalDetails?): String =
  when (details) {
    is ApprovalDetails.Exec -> nativeString("Run this command?")
    is ApprovalDetails.Plugin -> details.title
    null -> nativeString("Review action")
  }

internal fun approvalCanDecide(
  row: ApprovalRow,
  connected: Boolean,
  nowMs: Long,
): Boolean =
  connected &&
    row.details != null &&
    row.resolvingDecision == null &&
    row.failure != ApprovalFailure.OutcomeUnknown &&
    row.expiresAtMs > nowMs &&
    row.allowedDecisions.isNotEmpty()

internal fun approvalReviewStatus(
  review: ApprovalReview,
  nowMs: Long,
): String =
  when (review) {
    is ApprovalReview.Pending ->
      when {
        review.row.failure == ApprovalFailure.OutcomeUnknown -> nativeString("Checking decision…")
        review.row.resolvingDecision != null -> nativeString("Sending decision…")
        review.row.expiresAtMs <= nowMs -> nativeString("Checking expiry…")
        review.row.failure != null -> approvalFailureText(review.row.failure)
        else -> nativeString("Waiting for review")
      }
    is ApprovalReview.Settled ->
      when (review.outcome.approval.status) {
        ApprovalStatus.Allowed -> nativeString("Allowed. Check the task for the operation result.")
        ApprovalStatus.Denied -> nativeString("Denied")
        ApprovalStatus.Expired -> nativeString("Expired")
        ApprovalStatus.Cancelled -> nativeString("Cancelled")
      }
  }

internal fun approvalFailureText(failure: ApprovalFailure): String =
  when (failure) {
    ApprovalFailure.LoadInbox -> nativeString("Could not load approvals.")
    ApprovalFailure.LoadDetails -> nativeString("Could not load approval details. Refresh and try again.")
    ApprovalFailure.Resolve -> nativeString("Could not resolve approval. Refresh and try again.")
    ApprovalFailure.OutcomeUnknown -> nativeString("Resolution outcome unknown. Actions stay disabled until the Gateway record is verified.")
  }

internal fun approvalNoticeText(notice: ApprovalNotice): String =
  when (notice.status) {
    ApprovalStatus.Allowed ->
      when {
        !notice.appliedHere -> nativeString("A prior response already resolved this approval.")
        notice.decision == "allow-always" -> nativeString("Approval allowed and saved.")
        else -> nativeString("Approval allowed once.")
      }
    ApprovalStatus.Denied -> if (notice.appliedHere) nativeString("Approval denied.") else nativeString("A prior response already denied this approval.")
    ApprovalStatus.Expired -> nativeString("This approval expired before it could be resolved.")
    ApprovalStatus.Cancelled -> nativeString("This approval was cancelled before it could be resolved.")
  }
