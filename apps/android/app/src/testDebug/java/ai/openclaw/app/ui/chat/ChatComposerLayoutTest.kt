package ai.openclaw.app.ui.chat

import ai.openclaw.app.AndroidScreenshotFixture
import ai.openclaw.app.AndroidScreenshotScene
import ai.openclaw.app.MainViewModel
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
import ai.openclaw.app.SecurePrefs
import ai.openclaw.app.androidScreenshotLaunch
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-420dpi")
class ChatComposerLayoutTest {
  @get:Rule
  val composeRule = createComposeRule()

  private lateinit var app: NodeApp
  private lateinit var prefs: SecurePrefs
  private lateinit var runtime: NodeRuntime
  private var originalRuntime: NodeRuntime? = null
  private val viewModelStore = ViewModelStore()
  private var originalAnimatorScale: String? = null

  @Before
  fun setUp() {
    app = RuntimeEnvironment.getApplication() as NodeApp
    prefs = SecurePrefs(app, app.getSharedPreferences("chat-composer-${UUID.randomUUID()}", Context.MODE_PRIVATE))
    AndroidScreenshotFixture.configure(AndroidScreenshotScene.Chat)
    runtime = NodeRuntime.forScreenshotFixture(app, prefs, AndroidScreenshotFixture)
    originalRuntime = app.peekRuntime()
    setApplicationRuntime(runtime)
    originalAnimatorScale = Settings.Global.getString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    setApplicationRuntime(originalRuntime)
    runtime.disconnect()
    AndroidScreenshotFixture.configure(AndroidScreenshotScene.Home)
    Settings.Global.putString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalAnimatorScale)
  }

  @Test
  fun slashSuggestionsKeepEditorAndStopVisibleAndLastSuggestionReachable() {
    showChat()
    val editor = composeRule.onNode(hasSetTextAction())
    editor.performTextReplacement("/")
    editor.assertTextEquals("/")

    assertEditorAndStopVisible()
    val lastSuggestion = composeRule.onNodeWithText("/loop").performScrollTo().assertIsDisplayed()
    assertEditorAndStopVisible()
    lastSuggestion.performClick()
    editor.assertTextEquals("/loop ")
    assertEditorAndStopVisible()
  }

  @Test
  fun normalTextAndShortSuggestionListsKeepComposerVisible() {
    showChat()
    val editor = composeRule.onNode(hasSetTextAction())
    listOf("hello", "/help", "/unknown").forEach { input ->
      editor.performTextReplacement(input)
      editor.assertTextEquals(input)
      assertEditorAndStopVisible()
    }
  }

  @Test
  fun multilineDraftKeepsRunControlsAlignedToTheEditorsBottomEdge() {
    showChat()
    val editor = composeRule.onNode(hasSetTextAction())
    editor.performTextReplacement("first line\nsecond line\nthird line")

    val editorBounds = editor.getUnclippedBoundsInRoot()
    val stopBounds = composeRule.onNodeWithContentDescription("Stop").getUnclippedBoundsInRoot()
    assertTrue(
      "A growing draft must keep the trailing control on the composer's bottom row: $stopBounds vs $editorBounds",
      abs(stopBounds.bottom.value - editorBounds.bottom.value) <= 8.dp.value,
    )
  }

  @Test
  fun composerOptionsMenuKeepsContentAndCapabilityActionsSeparate() {
    showChat()

    composeRule.onNodeWithContentDescription("Composer options").performClick()

    composeRule.onNodeWithText("Photos").assertIsDisplayed()
    composeRule.onNodeWithText("Files").assertIsDisplayed()
    composeRule.onNodeWithText("Skills").assertIsDisplayed()
    composeRule.onNodeWithText("Manage in DevKit").assertDoesNotExist()
    composeRule.onNodeWithText("Model").assertDoesNotExist()
    composeRule.onNodeWithText("Thinking").assertDoesNotExist()
    composeRule.onNodeWithText("Context").assertDoesNotExist()
  }

  @Test
  fun skillMentionIsSelectedAndRemovedAsOneAccessibleToken() {
    RuntimeEnvironment.getApplication()
    var input by mutableStateOf("@and")
    var selected by mutableStateOf(emptyList<String>())
    val option = ChatSkillMentionOption("android-development", "Android", "Build an Android app", null)
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatComposer(
          value = input,
          onValueChange = { input = it },
          skillOptions = listOf(option),
          skillsLoaded = true,
          selectedSkillReferences = selected,
          staleSkillReferences = emptySet(),
          onSelectSkill = {
            selected = normalizeChatSkillReferences(selected + it.reference)
            input = removeChatSkillMentionQuery(input)
          },
          onRemoveSkill = { reference -> selected = selected.filterNot { it == reference } },
          attachments = emptyList(),
          pendingRunCount = 0,
          shareStaging = false,
          sendInFlight = false,
          shareImportNotice = null,
          onDismissShareImportNotice = {},
          commands = emptyList(),
          onPickImages = {},
          onPickDocument = {},
          onRemoveAttachment = {},
          onAbort = {},
          onSend = {},
        )
      }
    }

    composeRule.onNodeWithTag("skill-mention-android-development").assertIsDisplayed().performClick()
    assertEquals("", input)
    composeRule
      .onNodeWithContentDescription("Remove Skill @Android")
      .assertIsDisplayed()
      .assertHasClickAction()
      .performClick()
    composeRule.onAllNodesWithTag("skill-mention-strip").assertCountEquals(0)
  }

  @Test
  fun composerSkillsPageCommitsTheSameStableReferenceWithoutManagingDevKit() {
    var input by mutableStateOf("Keep this draft")
    var selected by mutableStateOf(emptyList<String>())
    val option = ChatSkillMentionOption("android-development", "Kotlin app", "Build an Android app", null)
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatComposer(
          value = input,
          onValueChange = { input = it },
          skillOptions = listOf(option),
          skillsLoaded = true,
          selectedSkillReferences = selected,
          staleSkillReferences = emptySet(),
          onSelectSkill = { selected = normalizeChatSkillReferences(selected + it.reference) },
          onRemoveSkill = { reference -> selected = selected.filterNot { it == reference } },
          attachments = emptyList(),
          pendingRunCount = 0,
          shareStaging = false,
          sendInFlight = false,
          shareImportNotice = null,
          onDismissShareImportNotice = {},
          commands = emptyList(),
          onPickImages = {},
          onPickDocument = {},
          onRemoveAttachment = {},
          onAbort = {},
          onSend = {},
        )
      }
    }

    composeRule.onNodeWithContentDescription("Composer options").performClick()
    composeRule.onNodeWithTag("chat-composer-open-skills").performClick()
    composeRule.onNodeWithTag("chat-composer-skill-android-development").performClick()

    composeRule.runOnIdle {
      assertEquals(listOf("android-development"), selected)
      assertEquals("Keep this draft", input)
    }
    composeRule.onNodeWithTag("skill-mention-chip-android-development").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("Composer options").performClick()
    composeRule.onNodeWithText("Manage in DevKit").assertDoesNotExist()
  }

  private fun showChat() {
    val viewModel = MainViewModel(app, prefs, SavedStateHandle())
    viewModelStore.put("chat", viewModel)
    viewModel.enterScreenshotFixture(androidScreenshotLaunch(AndroidScreenshotScene.Chat))
    composeRule.setContent {
      ProvideClawDesignSystem {
        // A portrait phone's remaining content viewport after its IME opens.
        Box(Modifier.size(width = 360.dp, height = 400.dp).clipToBounds().testTag("chat-viewport")) {
          ChatRoute(
            composer = viewModel.chatComposer,
            history = app.ensureRuntime().chatHistory,
            currentWork = app.ensureRuntime().chatCurrentWork,
            execution = app.ensureRuntime().chatExecution,
            gateway = app.ensureRuntime().chatGateway,
            outbox = app.ensureRuntime().chatOutbox,
            directory = app.ensureRuntime().chatDirectory,
            sessionOptions = app.ensureRuntime().chatSessionOptions,
            approvals = app.ensureRuntime().approvalFeature,
            permissions = app.ensureRuntime().permissionFeature,
            forceBlank = false,
            showSidebarButton = true,
            onOpenSidebar = {},
            focusApprovalId = null,
            onApprovalFocusConsumed = {},
          )
        }
      }
    }
    composeRule.waitUntil {
      app
        .ensureRuntime()
        .chatSessionOptions.commands.value.size == 6
    }
  }

  private fun assertEditorAndStopVisible() {
    val viewport = composeRule.onNodeWithTag("chat-viewport").getUnclippedBoundsInRoot()
    val editorNode = composeRule.onNode(hasSetTextAction())
    val stopNode = composeRule.onNodeWithContentDescription("Stop")
    val editor = editorNode.getUnclippedBoundsInRoot()
    val stop = stopNode.getUnclippedBoundsInRoot()
    assertTrue("Editor must retain a visible line: $editor inside $viewport", editor.bottom > editor.top)
    assertTrue("Stop must retain its touch target: $stop inside $viewport", stop.bottom - stop.top >= 48.dp)
    for (bounds in listOf(editor, stop)) {
      assertTrue("Composer control must stay below the viewport top", bounds.top >= viewport.top)
      assertTrue("Composer control must stay above the viewport bottom", bounds.bottom <= viewport.bottom)
    }
    editorNode.assertIsDisplayed()
    stopNode.assertIsDisplayed().assertHasClickAction()
  }

  private fun setApplicationRuntime(value: NodeRuntime?) {
    NodeApp::class.java
      .getDeclaredField("runtimeInstance")
      .apply { isAccessible = true }
      .set(app, value)
  }
}
