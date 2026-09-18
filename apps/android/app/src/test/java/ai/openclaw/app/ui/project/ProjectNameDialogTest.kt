package ai.openclaw.app.ui.project

import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.project.ProjectDraft
import ai.openclaw.app.project.ProjectNameDialogState
import ai.openclaw.app.project.ProjectNameOrigin
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectNameDialogTest {
  @get:Rule val composeRule = createComposeRule()

  @Before fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test fun defaultNameCanBeReplacedAndConfirmedInOneModal() {
    var changed = ""
    var confirms = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        ProjectNameDialog(
          state = editing(),
          onNameChange = { changed = it },
          onConfirm = { confirms++ },
          onDismiss = {},
          onFinishSetup = {},
        )
      }
    }

    composeRule.onNodeWithTag("project-name-field").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("project-name-field").performTextInput("Tiny Memo")
    composeRule.onNodeWithText("Create").performClick()
    assertEquals("Tiny Memo", changed)
    assertEquals(1, confirms)
  }

  @Test fun duplicateNameKeepsInputFocusAndInlineAccessibleText() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        ProjectNameDialog(
          state = editing(error = "Name already exists. Choose another.", errorRevision = 1),
          onNameChange = {},
          onConfirm = {},
          onDismiss = {},
          onFinishSetup = {},
        )
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("project-name-dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("project-name-field").assertIsFocused()
    composeRule.onNodeWithText("Name already exists. Choose another.").assertIsDisplayed()
  }

  private fun editing(
    error: String? = null,
    errorRevision: Long = 0,
  ) = ProjectNameDialogState.Editing(
    draft =
      ProjectDraft(
        draftId = "draft",
        sessionKey = "agent:main:project-draft",
      ),
    value = "New Project",
    origin = ProjectNameOrigin.Default,
    nameRevision = "revision",
    inlineError = error,
    errorRevision = errorRevision,
  )
}
