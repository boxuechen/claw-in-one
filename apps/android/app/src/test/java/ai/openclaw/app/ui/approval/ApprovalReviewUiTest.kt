package ai.openclaw.app.ui.approval

import ai.openclaw.app.approval.ApprovalActions
import ai.openclaw.app.approval.ApprovalDetails
import ai.openclaw.app.approval.ApprovalFailure
import ai.openclaw.app.approval.ApprovalOutcome
import ai.openclaw.app.approval.ApprovalSnapshot
import ai.openclaw.app.approval.ApprovalStatus
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.ui.chat.ChatApprovalCard
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalReviewUiTest {
  @get:Rule val composeRule = createComposeRule()

  @Before fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test fun inlineReviewHidesDigestsAndFreezesAllDecisionsDuringUnknownReadback() {
    val row = approvalRow()
    var review: ApprovalReview by mutableStateOf(ApprovalReview.Pending(row))
    var decisions = 0
    var detailsId: String? = null
    val actions =
      ApprovalActions({}, { _, _, decision ->
        decisions++
        review = ApprovalReview.Pending(row.copy(resolvingDecision = decision, failure = ApprovalFailure.OutcomeUnknown))
      }, {})
    composeRule.setContent {
      ProvideClawDesignSystem {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          ChatApprovalCard(review, true, actions) { detailsId = it }
        }
      }
    }
    composeRule.onNodeWithText("Install Notes").assertIsDisplayed()
    composeRule.onNodeWithText("Artifact SHA-256", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("Details").performClick()
    assertEquals("one", detailsId)
    composeRule.onNodeWithText("Allow Once").performScrollTo().performClick()
    composeRule.onNodeWithText("Checking decision…").assertIsDisplayed()
    composeRule.onNodeWithText("Allow Once").assertIsNotEnabled()
    composeRule.onNodeWithText("Deny").assertIsNotEnabled()
    assertEquals(1, decisions)
    composeRule.runOnIdle {
      review = ApprovalReview.Settled(ApprovalOutcome(ApprovalSnapshot.Terminal(row.id, row.kind, ApprovalStatus.Denied, "deny"), row, row.attribution))
    }
    composeRule.onNodeWithText("Denied").assertIsDisplayed()
    composeRule.onNodeWithText("Allow Once").assertDoesNotExist()
    composeRule.onNodeWithText("Deny").assertDoesNotExist()
  }

  @Test fun longDetailsKeepCloseAndDecisionsOutsideScrollingContent() {
    val row = approvalRow().let { it.copy(details = (it.details as ApprovalDetails.Plugin).copy(detail = "Inspection details\n".repeat(200) + "Signer: sample-signer")) }
    var closed = false
    var decisions = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        ApprovalDetailSheet(ApprovalReview.Pending(row), true, ApprovalActions({}, { _, _, _ -> decisions++ }, {}), { closed = true })
      }
    }
    composeRule.onNodeWithTag("approval-detail-close").assertIsDisplayed()
    composeRule.onNodeWithText("Allow Once").assertIsDisplayed()
    composeRule.onNodeWithText("Deny").assertIsDisplayed()
    composeRule.onNodeWithText("one").performScrollTo()
    composeRule.onNodeWithTag("approval-detail-close").assertIsDisplayed()
    composeRule.onNodeWithText("Allow Once").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Close approval details").performClick()
    assertTrue(closed)
    assertEquals(0, decisions)
  }

  @Test fun unavailableDetailNeverOffersAnotherDecision() {
    composeRule.setContent {
      ProvideClawDesignSystem { ApprovalDetailSheet(null, false, ApprovalActions({}, { _, _, _ -> error("unavailable") }, {}), {}) }
    }
    composeRule.onNodeWithTag("approval-detail-close").assertIsDisplayed()
    composeRule.onNodeWithText("Allow Once").assertDoesNotExist()
    composeRule.onNodeWithText("Gateway disconnected.").assertIsDisplayed()
  }

  @Test fun largeTextLightThemeRetainsFixedActionsAndClose() {
    composeRule.setContent {
      val density = LocalDensity.current.density
      CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
        ProvideClawDesignSystem(dark = false) {
          ApprovalDetailSheet(ApprovalReview.Pending(approvalRow()), true, ApprovalActions({}, { _, _, _ -> }, {}), {})
        }
      }
    }
    composeRule.onNodeWithTag("approval-detail-close").assertIsDisplayed()
    composeRule.onNodeWithText("Allow Once").assertIsDisplayed()
    composeRule.onNodeWithText("Deny").assertIsDisplayed()
  }
}
