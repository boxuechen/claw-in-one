package ai.openclaw.app.ui.chat

import ai.openclaw.app.chat.ChatCommentarySegment
import ai.openclaw.app.chat.ChatPendingToolCall
import ai.openclaw.app.chat.ChatRunActivity
import ai.openclaw.app.chat.ChatStopPhase
import ai.openclaw.app.chat.ChatThinkingLevelOption
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.permissions.SessionPermissionConnection
import ai.openclaw.app.permissions.SessionPermissionFailure
import ai.openclaw.app.permissions.SessionPermissionMode
import ai.openclaw.app.permissions.SessionPermissionPhase
import ai.openclaw.app.permissions.SessionPermissionRef
import ai.openclaw.app.permissions.SessionPermissionTarget
import ai.openclaw.app.permissions.SessionPermissionsState
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.core.os.LocaleListCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatScreenPresentationTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun healthyProjectWorkspaceIsQuietAndCompletedWorkDisappears() {
    val target = SessionPermissionTarget("gateway", "agent:main:project", "main")
    val permission =
      SessionPermissionsState(
        target = target,
        confirmedMode = SessionPermissionMode.Workspace,
        phase = SessionPermissionPhase.Ready,
      )

    assertNull(resolveChatPermissionAttention(null, permission, connected = true))
    assertNull(resolveChatCurrentWorkUiState(0, progress = null, tools = emptyList()))
  }

  @Test
  fun alternateProjectAccessIsValidAndUnconfirmedStopHasExplicitRecoveryActions() {
    val target = SessionPermissionTarget("gateway", "agent:main:project", "main")
    val permission =
      SessionPermissionsState(
        target = target,
        confirmedMode = SessionPermissionMode.Full,
        phase = SessionPermissionPhase.Ready,
      )

    assertNull(resolveChatPermissionAttention(null, permission, connected = true))

    val stopAttention = resolveChatStopAttention(ChatStopPhase.Unconfirmed, connected = true)
    assertEquals(ChatAttentionAction.CheckStop, stopAttention?.primaryAction?.action)
    assertEquals(ChatAttentionAction.StopChat, stopAttention?.secondaryAction?.action)
  }

  @Test
  fun permissionPickerDerivesCanonicalChoicesFromConnectionAuthority() {
    val target = SessionPermissionTarget("gateway", "agent:main:project", "main")
    val writeConnection =
      SessionPermissionConnection(
        gatewayId = "gateway",
        generation = 1,
        catalogRevision = 1,
        scopes = setOf("operator.write"),
        methods = setOf("sessions.describe", "sessions.patch"),
      )
    val state =
      SessionPermissionsState(
        target = target,
        ref = SessionPermissionRef(target, writeConnection, "session"),
        savedMode = SessionPermissionMode.Workspace,
        confirmedMode = SessionPermissionMode.Workspace,
        phase = SessionPermissionPhase.Ready,
      )

    val picker = requireNotNull(resolveChatPermissionPickerState(null, state, connected = true))
    assertEquals(SessionPermissionMode.Workspace, picker.displayedMode)
    assertEquals(true, picker.canOpen)
    assertEquals(true, picker.fullRequiresAdmin)
    assertEquals(
      setOf(
        SessionPermissionMode.Default,
        SessionPermissionMode.ReadOnly,
        SessionPermissionMode.Standard,
        SessionPermissionMode.Workspace,
      ),
      picker.enabledModes,
    )
    val applying =
      requireNotNull(
        resolveChatPermissionPickerState(
          null,
          state.copy(
            requestedMode = SessionPermissionMode.ReadOnly,
            phase = SessionPermissionPhase.Applying,
          ),
          connected = true,
        ),
      )
    assertEquals(SessionPermissionMode.ReadOnly, applying.displayedMode)
    assertEquals(emptySet<SessionPermissionMode>(), applying.enabledModes)
    assertEquals(
      emptySet<SessionPermissionMode>(),
      requireNotNull(resolveChatPermissionPickerState(SessionPermissionMode.Workspace, null, connected = true)).enabledModes,
    )
    val unavailable =
      requireNotNull(
        resolveChatPermissionPickerState(
          null,
          state.copy(
            phase = SessionPermissionPhase.Unavailable,
            failure = SessionPermissionFailure.Disconnected,
          ),
          connected = false,
        ),
      )
    assertEquals("Permissions unavailable. Check the connection.", unavailable.statusMessage)
    assertNull(unavailable.recoveryLabel)
    assertNull(resolveChatPermissionPickerState(null, state.copy(confirmedMode = null), connected = true))
  }

  @Test
  fun currentWorkKeepsToolDetailsBehindOneExpandableRow() {
    val state =
      ChatCurrentWorkUiState(
        progress = null,
        tools = listOf(ChatPendingToolCall("call", "project_query", startedAtMs = 0)),
      )
    val autoAdvance = composeRule.mainClock.autoAdvance
    composeRule.mainClock.autoAdvance = false
    try {
      composeRule.setContent {
        ProvideClawDesignSystem {
          ChatCurrentWorkRow(
            state = state,
            run = ChatWorkingRun("run", 0, "run", null),
          )
        }
      }

      composeRule.onNodeWithText("project_query").assertDoesNotExist()
      composeRule.onNodeWithTag("chat-current-work").performClick()
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.onNodeWithText("project_query").assertIsDisplayed()
      composeRule.onNodeWithTag("chat-current-work").performClick()
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.onNodeWithText("project_query").assertDoesNotExist()
    } finally {
      composeRule.mainClock.autoAdvance = autoAdvance
    }
  }

  @Test
  fun commentaryBelongsOnlyToItsWorkingRunAndNeverExposesAnswerActions() {
    val activity =
      ChatRunActivity(
        runId = "run-1",
        commentary =
          listOf(
            ChatCommentarySegment("run-1", "one", "First update", 1),
            ChatCommentarySegment("run-1", "two", "Second update", 2),
          ),
      )
    assertNull(resolveChatCurrentWorkUiState(1, null, emptyList(), "run-2", activity)?.commentary?.singleOrNull())
    val state = requireNotNull(resolveChatCurrentWorkUiState(1, null, emptyList(), "run-1", activity))
    assertEquals(listOf("First update", "Second update"), state.commentary)

    val autoAdvance = composeRule.mainClock.autoAdvance
    composeRule.mainClock.autoAdvance = false
    try {
      composeRule.setContent {
        ProvideClawDesignSystem {
          ChatCurrentWorkRow(
            state = state,
            run = ChatWorkingRun("run-1", 0, "run-1", null),
          )
        }
      }

      composeRule.onNodeWithText("Working · Second update").assertIsDisplayed()
      composeRule.onNodeWithText("First update").assertDoesNotExist()
      composeRule.onNodeWithContentDescription("Copy response").assertDoesNotExist()
      composeRule.onNodeWithTag("chat-current-work").performClick()
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.onNodeWithText("First update").assertIsDisplayed()
      composeRule.onNodeWithContentDescription("Copy response").assertDoesNotExist()
    } finally {
      composeRule.mainClock.autoAdvance = autoAdvance
    }
  }

  @Test
  fun headerHasNoNewChatAndMovesConfigurationIntoOptions() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatHeader(
          state =
            ChatHeaderState(
              showSidebarButton = true,
              sessionIdentity = "agent:main:claw-in-one-project:build-app",
              chatTitle = "Build app",
              projectName = "Demo",
              renameEnabled = true,
              permission =
                ChatPermissionPickerState(
                  confirmedMode = SessionPermissionMode.Workspace,
                  enabledModes = SessionPermissionMode.entries.toSet(),
                  canOpen = true,
                ),
              modelLabel = "GPT",
              modelPickerEnabled = true,
              thinkingLevel = "high",
              thinkingOptions = listOf(ChatThinkingLevelOption("high", "High")),
              thinkingSupported = true,
              contextPercent = 42,
            ),
          onOpenSidebar = {},
          onOpenModelPicker = {},
          onOpenPermissionPicker = {},
          onOpenRenameDialog = {},
          onThinkingLevelChange = {},
          onRefresh = {},
        )
      }
    }

    composeRule.onNodeWithContentDescription("New Chat").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Chat options").performClick()
    listOf("Model", "Thinking", "Context", "Chat details", "Chat name", "Project", "Workspace access").forEach {
      composeRule.onNodeWithText(it).assertExists()
    }
  }

  @Test
  fun accessRowUsesDedicatedPickerActionWithoutExpandingChatOptions() {
    var opened = false
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatHeader(
          state =
            ChatHeaderState(
              showSidebarButton = false,
              sessionIdentity = "agent:main:claw-in-one-project:build-app",
              chatTitle = "Build app",
              projectName = "Demo",
              permission =
                ChatPermissionPickerState(
                  confirmedMode = SessionPermissionMode.Workspace,
                  enabledModes = SessionPermissionMode.entries.toSet(),
                  canOpen = true,
                ),
              modelLabel = "GPT",
              modelPickerEnabled = false,
              thinkingLevel = "off",
              thinkingOptions = emptyList(),
              thinkingSupported = false,
            ),
          onOpenSidebar = {},
          onOpenModelPicker = {},
          onOpenPermissionPicker = { opened = true },
          onOpenRenameDialog = {},
          onThinkingLevelChange = {},
          onRefresh = {},
        )
      }
    }

    composeRule.onNodeWithContentDescription("Chat options").performClick()
    composeRule.onNodeWithTag("chat-access-option").performClick()
    assertEquals(true, opened)
    composeRule.onNodeWithTag("chat-permission-full").assertDoesNotExist()
  }

  @Test
  fun chatNameRowOpensBoundedRenameAction() {
    var opened = false
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatHeader(
          state =
            ChatHeaderState(
              showSidebarButton = false,
              sessionIdentity = "agent:main:claw-in-one-project:build-app",
              chatTitle = "Build app",
              projectName = "Demo",
              renameEnabled = true,
              modelLabel = "GPT",
              modelPickerEnabled = false,
              thinkingLevel = "off",
              thinkingOptions = emptyList(),
              thinkingSupported = false,
            ),
          onOpenSidebar = {},
          onOpenModelPicker = {},
          onOpenPermissionPicker = {},
          onOpenRenameDialog = { opened = true },
          onThinkingLevelChange = {},
          onRefresh = {},
        )
      }
    }

    composeRule.onNodeWithText("Build app").assertIsDisplayed()
    composeRule.onNodeWithText("Demo").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Chat options").performClick()
    composeRule.onNodeWithTag("chat-name-option").performClick()
    assertEquals(true, opened)
  }

  @Test
  fun renameDialogEditsManualLabelAndCanReturnToAutomaticName() {
    val value = mutableStateOf("Manual name")
    var saved = false
    var automatic = false
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatRenameDialog(
          state =
            ChatRenameDialogState(
              sessionKey = "agent:main:claw-in-one-project:build-app",
              sessionId = "session-1",
              savedLabel = "Manual name",
              initialValue = "Manual name",
              value = value.value,
            ),
          available = true,
          onValueChange = { value.value = it },
          onSave = { saved = true },
          onUseAutomaticName = { automatic = true },
          onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithTag("chat-rename-save").assertIsNotEnabled()
    composeRule.onNodeWithTag("chat-rename-field").performTextReplacement("Release plan")
    composeRule.onNodeWithTag("chat-rename-save").performClick()
    assertEquals("Release plan", value.value)
    assertEquals(true, saved)
    composeRule.onNodeWithTag("chat-use-automatic-name").performClick()
    assertEquals(true, automatic)
  }

  @Test
  fun unchangedRenameDialogCanAlwaysBeCancelled() {
    val dialog =
      mutableStateOf<ChatRenameDialogState?>(
        ChatRenameDialogState(
          sessionKey = "agent:main:claw-in-one-project:rnfixed:chat-1",
          sessionId = "session-1",
          savedLabel = null,
          initialValue = "New chat",
          value = "New chat",
        ),
      )
    composeRule.setContent {
      ProvideClawDesignSystem {
        dialog.value?.let { current ->
          ChatRenameDialog(
            state = current,
            available = true,
            onValueChange = { next ->
              dialog.value
                ?.takeIf { it.sessionKey == current.sessionKey && it.sessionId == current.sessionId }
                ?.let { dialog.value = it.copy(value = next) }
            },
            onSave = {},
            onUseAutomaticName = {},
            onDismiss = { dialog.value = null },
          )
        }
      }
    }

    composeRule.onNodeWithTag("chat-rename-cancel").performClick()
    composeRule.onNodeWithTag("chat-rename-dialog").assertDoesNotExist()
    assertNull(dialog.value)
  }

  @Test
  fun permissionPickerSheetListsGatewayModesAndDispatchesTheSelection() {
    var selected: SessionPermissionMode? = null
    composeRule.setContent {
      ProvideClawDesignSystem {
        ChatPermissionPickerSheet(
          state =
            ChatPermissionPickerState(
              confirmedMode = SessionPermissionMode.Workspace,
              enabledModes = SessionPermissionMode.entries.toSet(),
              canOpen = true,
            ),
          onDismiss = {},
          onSelect = { selected = it },
          onRecover = {},
        )
      }
    }

    listOf("default", "read-only", "guarded", "workspace", "full").forEachIndexed { index, wire ->
      composeRule.onNodeWithTag("chat-permission-picker-list").performScrollToIndex(index + 1)
      composeRule.onNodeWithTag("chat-permission-$wire").assertExists()
    }
    composeRule.onNodeWithTag("chat-permission-full").performClick()
    assertEquals(SessionPermissionMode.Full, selected)
  }
}
