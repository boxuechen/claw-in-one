package ai.openclaw.app.ui.chat

import ai.openclaw.app.devkit.developerCapabilityDefinition
import ai.openclaw.app.project.DevelopmentCapabilitiesState
import ai.openclaw.app.supervisor.DevelopmentCapability

internal enum class ProjectStarterIcon {
  Kotlin,
  Flutter,
  Godot,
  Web,
}

internal data class ProjectStarter(
  val id: String,
  val capability: DevelopmentCapability,
  val icon: ProjectStarterIcon,
  val title: String,
  val skillReference: String,
  val prompt: String,
)

internal fun projectStarters(snapshot: DevelopmentCapabilitiesState?): List<ProjectStarter> {
  val ready =
    (snapshot as? DevelopmentCapabilitiesState.Ready)
      ?.capabilities
      .orEmpty()
      .toSet()
  return listOf(
    DevelopmentCapability.AndroidKotlin,
    DevelopmentCapability.Flutter,
    DevelopmentCapability.GodotAndroid,
    DevelopmentCapability.WebDevelopment,
  ).mapNotNull { capability ->
    val definition = developerCapabilityDefinition(capability)
    when (capability) {
      DevelopmentCapability.AndroidKotlin ->
        ProjectStarter(
          id = "android-kotlin",
          capability = capability,
          icon = ProjectStarterIcon.Kotlin,
          title = definition.chatLabel,
          skillReference = definition.skillReference,
          prompt =
            "Create a small offline Kotlin Android task app that can add and complete items. " +
              "Build it on this phone, install it, and show the result in VScreen.",
        ).takeIf { capability in ready }
      DevelopmentCapability.Flutter ->
        ProjectStarter(
          id = "flutter",
          capability = capability,
          icon = ProjectStarterIcon.Flutter,
          title = definition.chatLabel,
          skillReference = definition.skillReference,
          prompt =
            "Create a small polished Flutter Android app with one useful interactive flow. " +
              "Build it on this phone, install it, and show the result in VScreen.",
        ).takeIf { capability in ready }
      DevelopmentCapability.GodotAndroid ->
        ProjectStarter(
          id = "godot-android",
          capability = capability,
          icon = ProjectStarterIcon.Godot,
          title = definition.chatLabel,
          skillReference = definition.skillReference,
          prompt =
            "Create Claw Dash, a polished portrait 3D arcade game in Godot using GDScript. " +
              "Configure the Godot project for phone portrait from the start: use a portrait viewport such as " +
              "1080x1920, request portrait orientation on Android, and make the camera and every Control node " +
              "responsive with anchors and safe margins so nothing is cropped on a tall 1080x2400 Pixel display. " +
              "Open on a stable title screen that says Tap to Start; do not spawn obstacles, move the world, " +
              "move the player, or count score until the player taps. Handle a real Android touch explicitly with " +
              "InputEventScreenTouch, ensure title UI does not consume it, and use that first tap only to enter the " +
              "playing state. After that tap, let the player drag a glowing red character " +
              "left and right to dodge incoming blocks and collect cyan energy. Give each run a three-second " +
              "obstacle-free start, place the first cyan energy directly in the player's path, then introduce " +
              "slow sparse obstacles and ramp difficulty gradually so the player reliably scores within five " +
              "seconds and a normal run lasts at least fifteen seconds. Give the player a three-hit energy shield; " +
              "each hit removes one segment, shows clear feedback, and grants brief invulnerability, with game over " +
              "only after the third hit. Use five fixed visible lanes, clamp the player to the visible playfield, " +
              "spawn objects far down the track, and move them toward the player so dragging cannot leave the screen " +
              "and every obstacle or energy pickup crosses a playable lane. Build every visual from Godot " +
              "primitives, materials, lights, particles, and UI—use no external or downloaded assets. Add score, " +
              "game over, and Tap to Restart. Before " +
              "finishing, verify the installed app with a real InputEventScreenTouch, then capture it after roughly " +
              "three and fifteen seconds of play and confirm that the player stays visible, the score is positive, " +
              "and obstacles, energy, and shield feedback are on-screen. Do not substitute a headless self-test for " +
              "this installed-app visual check; if touch injection is unavailable, leave Tap to Start visible and " +
              "report that limitation. Export it for Android ARM64 on this phone, install it, and show it in VScreen.",
        ).takeIf { capability in ready }
      DevelopmentCapability.WebDevelopment ->
        ProjectStarter(
          id = "web-development",
          capability = capability,
          icon = ProjectStarterIcon.Web,
          title = definition.chatLabel,
          skillReference = definition.skillReference,
          prompt =
            "Create a small polished Web app with a React and TypeScript frontend and one useful " +
              "Node API flow. Build and serve it, then make it ready to open in Android Chrome.",
        ).takeIf { capability in ready }
      DevelopmentCapability.AndroidNative,
      DevelopmentCapability.ReactNative,
      -> null
    }
  }
}
