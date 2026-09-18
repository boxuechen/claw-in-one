package ai.openclaw.app.devkit

import ai.openclaw.app.supervisor.DevelopmentCapability

internal const val ANDROID_USE_SKILL_REFERENCE = "android-use"

/** Stable product identity shared by DevKit, Chat Skills and Project starters. */
internal data class DeveloperCapabilityDefinition(
  val id: DeveloperCapabilityId,
  val developmentCapability: DevelopmentCapability,
  val skillReference: String,
  val chatLabel: String,
  val skillLabel: String,
)

/** Product-owned Chat surface for capabilities prepared and authorized in DevKit. */
internal data class DevKitChatSkillDefinition(
  val capabilityId: DeveloperCapabilityId,
  val skillReference: String,
  val chatLabel: String,
)

internal val developerCapabilityDefinitions =
  listOf(
    DeveloperCapabilityDefinition(
      id = DeveloperCapabilityId.AndroidKotlin,
      developmentCapability = DevelopmentCapability.AndroidKotlin,
      skillReference = "android-development",
      chatLabel = "Android",
      skillLabel = "Kotlin app",
    ),
    DeveloperCapabilityDefinition(
      id = DeveloperCapabilityId.AndroidNative,
      developmentCapability = DevelopmentCapability.AndroidNative,
      skillReference = "android-native-development",
      chatLabel = "Native",
      skillLabel = "NDK app",
    ),
    DeveloperCapabilityDefinition(
      id = DeveloperCapabilityId.Flutter,
      developmentCapability = DevelopmentCapability.Flutter,
      skillReference = "flutter-development",
      chatLabel = "Flutter",
      skillLabel = "Flutter app",
    ),
    DeveloperCapabilityDefinition(
      id = DeveloperCapabilityId.GodotAndroid,
      developmentCapability = DevelopmentCapability.GodotAndroid,
      skillReference = "godot-android-development",
      chatLabel = "Godot",
      skillLabel = "Godot game",
    ),
    DeveloperCapabilityDefinition(
      id = DeveloperCapabilityId.ReactNative,
      developmentCapability = DevelopmentCapability.ReactNative,
      skillReference = "react-native-development",
      chatLabel = "React Native",
      skillLabel = "React Native app",
    ),
    DeveloperCapabilityDefinition(
      id = DeveloperCapabilityId.WebDevelopment,
      developmentCapability = DevelopmentCapability.WebDevelopment,
      skillReference = "web-development",
      chatLabel = "Web",
      skillLabel = "Web app",
    ),
  ).also { definitions ->
    require(definitions.map(DeveloperCapabilityDefinition::id).distinct().size == definitions.size)
    require(definitions.map(DeveloperCapabilityDefinition::developmentCapability).distinct().size == definitions.size)
    require(definitions.map(DeveloperCapabilityDefinition::skillReference).distinct().size == definitions.size)
  }

internal val devKitChatSkillDefinitions =
  buildList {
    add(
      DevKitChatSkillDefinition(
        capabilityId = DeveloperCapabilityId.AndroidUse,
        skillReference = ANDROID_USE_SKILL_REFERENCE,
        chatLabel = "Android Use",
      ),
    )
    addAll(
      developerCapabilityDefinitions.map { definition ->
        DevKitChatSkillDefinition(
          capabilityId = definition.id,
          skillReference = definition.skillReference,
          chatLabel = definition.skillLabel,
        )
      },
    )
  }.also { definitions ->
    require(definitions.map(DevKitChatSkillDefinition::capabilityId).distinct().size == definitions.size)
    require(definitions.map(DevKitChatSkillDefinition::skillReference).distinct().size == definitions.size)
  }

internal fun developerCapabilityDefinition(id: DeveloperCapabilityId): DeveloperCapabilityDefinition? = developerCapabilityDefinitions.firstOrNull { it.id == id }

internal fun developerCapabilityDefinition(capability: DevelopmentCapability): DeveloperCapabilityDefinition = developerCapabilityDefinitions.single { it.developmentCapability == capability }
