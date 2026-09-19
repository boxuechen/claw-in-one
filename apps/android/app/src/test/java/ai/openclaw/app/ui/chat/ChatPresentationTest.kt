package ai.openclaw.app.ui.chat

import ai.openclaw.app.project.DevelopmentCapabilitiesState
import ai.openclaw.app.supervisor.DevelopmentCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationTest {
  @Test
  fun projectStartersComeOnlyFromLiveGatewayReadiness() {
    val readiness = DevelopmentCapabilitiesState.Ready("revision", listOf(DevelopmentCapability.AndroidKotlin))
    val starters = projectStarters(readiness)

    assertEquals(1, starters.size)
    assertEquals(DevelopmentCapability.AndroidKotlin, starters.single().capability)
    assertTrue(starters.single().prompt.contains("Kotlin Android"))
    assertTrue(starters.size <= 5)
    assertEquals(emptyList<ProjectStarter>(), projectStarters(null))
    assertEquals(
      emptyList<ProjectStarter>(),
      projectStarters(DevelopmentCapabilitiesState.Ready("revision", emptyList())),
    )
  }

  @Test
  fun flutterReadyAddsOneIndependentStarterWithoutChangingKotlin() {
    val starters =
      projectStarters(
        DevelopmentCapabilitiesState.Ready(
          "revision",
          listOf(DevelopmentCapability.AndroidKotlin, DevelopmentCapability.Flutter),
        ),
      )

    assertEquals(listOf("android-kotlin", "flutter"), starters.map(ProjectStarter::id))
    assertEquals(listOf("android-development", "flutter-development"), starters.map(ProjectStarter::skillReference))
  }

  @Test
  fun godotReadyAddsTheHeadlessAndroidGameStarter() {
    val starters =
      projectStarters(
        DevelopmentCapabilitiesState.Ready(
          "revision",
          listOf(DevelopmentCapability.AndroidKotlin, DevelopmentCapability.GodotAndroid),
        ),
      )

    assertEquals(listOf("android-kotlin", "godot-android"), starters.map(ProjectStarter::id))
    val godotStarter = starters.last()
    assertEquals("godot-android-development", godotStarter.skillReference)
    assertTrue(godotStarter.prompt.contains("Claw Dash"))
    assertTrue(godotStarter.prompt.contains("portrait 3D arcade game"))
    assertTrue(godotStarter.prompt.contains("1080x1920"))
    assertTrue(godotStarter.prompt.contains("1080x2400 Pixel display"))
    assertTrue(godotStarter.prompt.contains("anchors and safe margins"))
    assertTrue(godotStarter.prompt.contains("Tap to Start"))
    assertTrue(godotStarter.prompt.contains("do not spawn obstacles"))
    assertTrue(godotStarter.prompt.contains("InputEventScreenTouch"))
    assertTrue(godotStarter.prompt.contains("use that first tap only"))
    assertTrue(godotStarter.prompt.contains("three-second obstacle-free start"))
    assertTrue(godotStarter.prompt.contains("scores within five seconds"))
    assertTrue(godotStarter.prompt.contains("lasts at least fifteen seconds"))
    assertTrue(godotStarter.prompt.contains("three-hit energy shield"))
    assertTrue(godotStarter.prompt.contains("only after the third hit"))
    assertTrue(godotStarter.prompt.contains("five fixed visible lanes"))
    assertTrue(godotStarter.prompt.contains("clamp the player to the visible playfield"))
    assertTrue(godotStarter.prompt.contains("move them toward the player"))
    assertTrue(godotStarter.prompt.contains("Tap to Restart"))
    assertTrue(godotStarter.prompt.contains("real InputEventScreenTouch"))
    assertTrue(godotStarter.prompt.contains("three and fifteen seconds"))
    assertTrue(godotStarter.prompt.contains("score is positive"))
    assertTrue(godotStarter.prompt.contains("Do not substitute a headless self-test"))
    assertTrue(godotStarter.prompt.contains("report that limitation"))
    assertTrue(godotStarter.prompt.contains("no external or downloaded assets"))
    assertTrue(godotStarter.prompt.contains("Android ARM64"))
    assertTrue(godotStarter.prompt.contains("VScreen"))
  }

  @Test
  fun projectBootstrapUsesOnlyTheFixedTwoByTwoProductStarters() {
    val starters =
      projectStarters(
        DevelopmentCapabilitiesState.Ready("revision", DevelopmentCapability.entries),
      )

    assertEquals(4, starters.size)
    assertEquals(
      listOf("android-kotlin", "flutter", "godot-android", "web-development"),
      starters.map(ProjectStarter::id),
    )
    assertEquals(
      listOf("Android", "Flutter", "Godot", "Web"),
      starters.map(ProjectStarter::title),
    )
    assertEquals(
      listOf(ProjectStarterIcon.Kotlin, ProjectStarterIcon.Flutter, ProjectStarterIcon.Godot, ProjectStarterIcon.Web),
      starters.map(ProjectStarter::icon),
    )
    assertEquals("web-development", starters.last().skillReference)
    assertTrue(starters.last().prompt.contains("Android Chrome"))
    assertTrue(starters.none { it.capability in setOf(DevelopmentCapability.AndroidNative, DevelopmentCapability.ReactNative) })
  }

  @Test
  fun startersAppearOnlyInAHealthyLoadedNewProjectDraft() {
    val starters = projectStarters(DevelopmentCapabilitiesState.Ready("revision", listOf(DevelopmentCapability.AndroidKotlin)))

    assertEquals(
      ChatBlankContent.ProjectBootstrap(starters, enabled = true),
      resolveChatBlankContent(
        starters = starters,
        destinationKind = ChatDestinationKind.NewProjectDraft,
        presentationState = ChatPresentationState.BlankHealthy,
        historyLoading = false,
        startersEnabled = true,
      ),
    )
    assertEquals(
      ChatBlankContent.Conversation,
      resolveChatBlankContent(
        starters,
        ChatDestinationKind.ProjectChatDraft,
        ChatPresentationState.BlankHealthy,
        historyLoading = false,
      ),
    )
    assertEquals(
      ChatBlankContent.Conversation,
      resolveChatBlankContent(
        starters,
        ChatDestinationKind.MaterializedChat,
        ChatPresentationState.BlankHealthy,
        historyLoading = false,
      ),
    )
  }

  @Test
  fun projectBootstrapDoesNotReplaceLoadingConversationOrAnEditedComposer() {
    val starters = projectStarters(DevelopmentCapabilitiesState.Ready("revision", listOf(DevelopmentCapability.AndroidKotlin)))

    assertEquals(
      ChatBlankContent.Conversation,
      resolveChatBlankContent(
        starters,
        ChatDestinationKind.NewProjectDraft,
        ChatPresentationState.BlankHealthy,
        historyLoading = true,
      ),
    )
    assertEquals(
      ChatBlankContent.Conversation,
      resolveChatBlankContent(
        starters,
        ChatDestinationKind.NewProjectDraft,
        ChatPresentationState.BlankHealthy,
        historyLoading = false,
        composerPristine = false,
      ),
    )
    assertEquals(
      ChatBlankContent.Conversation,
      resolveChatBlankContent(
        starters,
        ChatDestinationKind.NewProjectDraft,
        ChatPresentationState.ConversationIdle,
        historyLoading = false,
      ),
    )
  }

  @Test
  fun healthyEmptyChatStaysVisuallyBlank() {
    assertEquals(
      ChatPresentationState.BlankHealthy,
      resolveChatPresentationState(hasContent = false, runActive = false),
    )
  }

  @Test
  fun activeConversationKeepsItsContentState() {
    assertEquals(
      ChatPresentationState.ConversationRunning,
      resolveChatPresentationState(hasContent = true, runActive = true),
    )
  }
}
