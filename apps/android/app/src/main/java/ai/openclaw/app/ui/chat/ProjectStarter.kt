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
              "Start gameplay immediately: drag a glowing red player left and right to dodge incoming blocks " +
              "and collect cyan energy. Give the player a three-second obstacle-free start, place the first cyan " +
              "energy directly in the player's path, then introduce slow sparse obstacles and ramp difficulty " +
              "gradually so the player reliably scores within five seconds. Build every visual from Godot " +
              "primitives, materials, lights, particles, and UI—use no external or downloaded assets. Add score, " +
              "hit feedback, game over, and tap to restart. Export it for Android ARM64 on this phone, install it, " +
              "and show it in VScreen.",
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
