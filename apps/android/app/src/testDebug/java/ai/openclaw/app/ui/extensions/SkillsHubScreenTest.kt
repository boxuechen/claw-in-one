package ai.openclaw.app.ui.extensions

import ai.openclaw.app.GatewayClawHubSkillSearchState
import ai.openclaw.app.skill.SkillActions
import ai.openclaw.app.skill.SkillFeature
import ai.openclaw.app.skill.SkillState
import ai.openclaw.app.skill.catalog.SkillDirectoryActions
import ai.openclaw.app.skill.catalog.SkillDirectoryFeature
import ai.openclaw.app.skill.catalog.SkillDirectoryState
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillsHubScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun auditDetailsRemainReadableForSuccessAndRejection() {
    RuntimeEnvironment.getApplication()
    val state = MutableStateFlow(SkillState(connected = true))
    val feature =
      SkillFeature(
        state = state,
        actions =
          SkillActions(
            refresh = {},
            setEnabled = { _, _ -> },
            searchClawHub = {},
            reviewClawHubInstall = {},
            dismissInstallReview = {},
            installClawHub = { _, _ -> },
            clearClawHubNotice = {
              state.value = state.value.copy(clawHub = GatewayClawHubSkillSearchState())
            },
          ),
      )
    val directory =
      SkillDirectoryFeature(
        state = MutableStateFlow(SkillDirectoryState()),
        actions = SkillDirectoryActions({}, {}, {}),
      )
    composeRule.setContent {
      ProvideClawDesignSystem {
        SkillsHubRoute(
          skill = feature,
          directory = directory,
          destination = ExtensionCenterDestination.Directory(ExtensionCenterDirectory.Skills),
          contentPadding = PaddingValues(),
          onOpenSkill = {},
        )
      }
    }

    for (isError in listOf(false, true)) {
      val message = if (isError) "Blocked by ClawHub." else "Installed @alice/alpha."
      composeRule.runOnIdle {
        state.value =
          state.value.copy(
            clawHub =
              GatewayClawHubSkillSearchState(
                errorText = "$message\n\nClawHub audit details.".takeIf { isError },
                messageText = "$message\n\nClawHub audit details.".takeUnless { isError },
              ),
          )
      }

      composeRule.onNodeWithText(message).performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithText("ClawHub audit details.").assertDoesNotExist()
      composeRule.onNodeWithText("Review").performScrollTo().performClick()
      composeRule.onNodeWithText("ClawHub audit details.").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithText("Acknowledge Gateway warning and install").assertDoesNotExist()
      composeRule.onNodeWithText("Dismiss").performScrollTo().performClick()
      composeRule.onNodeWithText(message).assertDoesNotExist()
    }
  }

  @Test
  fun installedSkillStatusLabelsRemainDistinct() {
    assertEquals("Ready", installedSkillStatusLabel(InstalledSkillStatus.Ready))
    assertEquals("Needs setup", installedSkillStatusLabel(InstalledSkillStatus.NeedsSetup))
    assertEquals("Disabled", installedSkillStatusLabel(InstalledSkillStatus.Disabled))
  }

  @Test
  fun directoryKeepsManagementOutOfInstalledRows() {
    RuntimeEnvironment.getApplication()
    var openedSkill: String? = null
    composeRule.setContent {
      ProvideClawDesignSystem {
        SkillsHubScreen(
          state = skillsState(),
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearchClawHub = {},
          onOpenSkill = { openedSkill = it },
          onReviewClawHubSkill = {},
          onDismissNotice = {},
          onDismissInstallReview = {},
          onInstallReviewedSkill = {},
        )
      }
    }

    composeRule.onNodeWithContentDescription("Search Skills").assertIsDisplayed()
    composeRule.onAllNodesWithText("Calendar helper")[0].performClick()
    assertEquals("calendar-helper", openedSkill)
    composeRule.onNodeWithText("Skill controls").assertDoesNotExist()
  }

  @Test
  fun needsSetupDetailOwnsRecoveryAndEnablement() {
    RuntimeEnvironment.getApplication()
    var refreshes = 0
    var enabled: Boolean? = null
    composeRule.setContent {
      ProvideClawDesignSystem {
        SkillDetailScreen(
          skill = skillsState().installedSkills.single(),
          connected = true,
          canManageSkills = true,
          mutating = false,
          contentPadding = PaddingValues(),
          onRefresh = { refreshes += 1 },
          onSetEnabled = { enabled = it },
        )
      }
    }

    composeRule.onAllNodesWithText("Needs setup").assertCountEquals(2)
    composeRule.onNodeWithText("Check again").performClick()
    assertEquals(1, refreshes)
    composeRule
      .onNodeWithContentDescription("Allow OpenClaw to use this Skill in Chat when it is ready.")
      .performScrollTo()
      .performClick()
    assertEquals(false, enabled)
  }

  @Test
  fun installReviewPreservesUnscannedSecurityState() {
    RuntimeEnvironment.getApplication()
    var installs = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        SkillsHubScreen(
          state =
            skillsState().copy(
              installReview =
                SkillInstallReviewItem(
                  reference = "skills-sh:openai/skills/pdf",
                  displayName = "PDF",
                  summary = "Read and create PDF documents.",
                  version = "1.2.3",
                  publisher = "OpenAI",
                  isUnscannedSource = true,
                ),
            ),
          contentPadding = PaddingValues(),
          onRefresh = {},
          onSearchClawHub = {},
          onOpenSkill = {},
          onReviewClawHubSkill = {},
          onDismissNotice = {},
          onDismissInstallReview = {},
          onInstallReviewedSkill = { installs += 1 },
        )
      }
    }

    composeRule.onNodeWithText("Review PDF").assertIsDisplayed()
    composeRule.onNodeWithText("ClawHub has not scanned this source. Review its publisher before continuing; the Gateway may still block installation.").assertIsDisplayed()
    composeRule.onNodeWithText("Verify and install").performClick()
    assertEquals(1, installs)
  }

  private fun skillsState(): SkillsHubUiState =
    SkillsHubUiState(
      connected = true,
      canManageSkills = true,
      installMethodsAvailable = true,
      inventoryRefreshing = false,
      inventoryErrorText = null,
      catalogRefreshing = false,
      catalogErrorText = null,
      installedSkills =
        listOf(
          InstalledSkillItem(
            skillKey = "calendar-helper",
            displayName = "Calendar helper",
            summary = "Plan and review upcoming events.",
            sourceLabel = "Installed",
            badge = "CH",
            status = InstalledSkillStatus.NeedsSetup,
            missingCount = 2,
            installCount = 1,
            isBuiltIn = false,
          ),
        ),
      mutatingSkillKeys = emptySet(),
      directorySkills =
        listOf(
          SkillDirectoryItem(
            reference = "@openclaw/calendar-helper",
            displayName = "Calendar helper",
            summary = "Plan and review upcoming events.",
            version = "1.0.0",
            categories = listOf("Productivity"),
            installed = true,
            installedSkillKey = "calendar-helper",
            reviewing = false,
            installing = false,
          ),
        ),
      directoryQuery = "",
      directorySearching = false,
      directoryResults = emptyList(),
      directoryErrorText = null,
      lifecycleErrorText = null,
      lifecycleMessageText = null,
      installReview = null,
    )
}
