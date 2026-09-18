package ai.openclaw.app.ui.chat

import ai.openclaw.app.GatewaySkillSummary
import ai.openclaw.app.devkit.DeveloperCapability
import ai.openclaw.app.devkit.DeveloperCapabilityActivationPolicy
import ai.openclaw.app.devkit.DeveloperCapabilityCatalogState
import ai.openclaw.app.devkit.DeveloperCapabilityGroup
import ai.openclaw.app.devkit.DeveloperCapabilityId
import ai.openclaw.app.devkit.DeveloperCapabilityProvisioning
import ai.openclaw.app.devkit.DeveloperCapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSkillMentionsTest {
  @Test
  fun productCatalogIntersectsGatewayEligibilityAndOwnerReadiness() {
    val options =
      eligibleChatSkillMentions(
        skills =
          listOf(
            skill(name = "android-development", skillKey = "plugin-key"),
            skill(name = "release-notes"),
            skill(name = "disabled", disabled = true),
            skill(name = "missing", missingCount = 1),
            skill(name = "blocked", blockedByAgentFilter = true),
            skill(name = "ineligible", eligible = false),
          ),
        capabilities = capabilities(DeveloperCapabilityId.AndroidKotlin),
      )

    assertEquals(listOf("android-development"), options.map(ChatSkillMentionOption::reference))
    assertEquals("Kotlin app", options.single().label)
  }

  @Test
  fun readyDevKitSkillsUseTheFixedProductOrderAndLabels() {
    val options =
      eligibleChatSkillMentions(
        skills =
          listOf(
            skill("web-development"),
            skill("android-use"),
            skill("android-development"),
            skill("android-native-development"),
            skill("flutter-development"),
            skill("godot-android-development"),
            skill("react-native-development"),
          ),
        capabilities = capabilities(*DEVKIT_CHAT_CAPABILITY_IDS.toTypedArray()),
      )

    assertEquals(
      listOf(
        "Android Use",
        "Kotlin app",
        "NDK app",
        "Flutter app",
        "Godot game",
        "React Native app",
        "Web app",
      ),
      options.map(ChatSkillMentionOption::label),
    )
  }

  @Test
  fun androidUseIsSelectableOnlyWhenGatewayAndDevKitAreReady() {
    val gateway = listOf(skill("android-use"))

    assertTrue(
      eligibleChatSkillMentions(
        skills = gateway,
        capabilities = capabilities(DeveloperCapabilityId.AndroidUse),
      ).single().reference == "android-use",
    )
    assertTrue(
      eligibleChatSkillMentions(
        skills = gateway,
        capabilities = capabilities(),
      ).isEmpty(),
    )
    assertTrue(
      eligibleChatSkillMentions(
        skills = listOf(skill("android-use", eligible = false)),
        capabilities = capabilities(DeveloperCapabilityId.AndroidUse),
      ).isEmpty(),
    )
  }

  @Test
  fun trailingAtQueryFiltersAndIsRemovedWhenSelectionCommits() {
    val options =
      listOf(
        ChatSkillMentionOption("android-development", "Android Development", "Build an app", null),
        ChatSkillMentionOption("release-notes", "Release Notes", "Write notes", null),
      )

    assertEquals("and", chatSkillMentionQuery("Create an app @and")?.query)
    assertEquals(listOf("android-development"), matchingChatSkillMentions("Create an app @and", options).map { it.reference })
    assertEquals("Create an app", removeChatSkillMentionQuery("Create an app @and"))
    assertEquals(null, chatSkillMentionQuery("email@example.com"))
  }

  @Test
  fun explicitReferencesAreOrderedUniqueBoundedAndUseOpenClawSyntax() {
    val references = (0..CHAT_COMPOSER_MAX_SKILL_REFERENCES).map { "skill-$it" } + "skill-1"
    val normalized = normalizeChatSkillReferences(references)

    assertEquals(CHAT_COMPOSER_MAX_SKILL_REFERENCES, normalized.size)
    assertEquals("skill-0", normalized.first())
    assertEquals(
      "\$android-development \$review\n\nBuild the app",
      serializeExplicitSkillPrompt(listOf("android-development", "review", "review"), "  Build the app  "),
    )
    assertEquals(
      "\$android-use\n\nOpen Settings",
      serializeExplicitSkillPrompt(listOf("android-use"), "Open Settings"),
    )
  }

  @Test
  fun staleSelectionWaitsForLoadedInventoryAndNeverSubstitutes() {
    val selected = listOf("android-development", "removed-skill")
    val options = listOf(ChatSkillMentionOption("android-development", "Android Development", null, null))

    assertTrue(staleChatSkillReferences(false, selected, options).isEmpty())
    assertEquals(setOf("removed-skill"), staleChatSkillReferences(true, selected, options))
    assertFalse("replacement" in serializeExplicitSkillPrompt(selected, "Keep my prompt"))
  }

  private fun skill(
    name: String,
    skillKey: String = name,
    disabled: Boolean = false,
    eligible: Boolean = true,
    blockedByAgentFilter: Boolean = false,
    missingCount: Int = 0,
  ) = GatewaySkillSummary(
    skillKey = skillKey,
    name = name,
    description = null,
    source = "plugin",
    emoji = null,
    disabled = disabled,
    eligible = eligible,
    blockedByAllowlist = false,
    blockedByAgentFilter = blockedByAgentFilter,
    bundled = false,
    missingCount = missingCount,
    installCount = 0,
  )

  private fun capabilities(vararg ready: DeveloperCapabilityId): DeveloperCapabilityCatalogState {
    val readyIds = ready.toSet()
    return DeveloperCapabilityCatalogState(
      capabilities =
        DeveloperCapabilityId.entries.map { id ->
          DeveloperCapability(
            id = id,
            group = DeveloperCapabilityGroup.Device,
            provisioning = DeveloperCapabilityProvisioning.BuiltIn,
            activationPolicy = DeveloperCapabilityActivationPolicy.OnDemand,
            status = if (id in readyIds) DeveloperCapabilityStatus.Ready else DeveloperCapabilityStatus.Checking,
            allowedActions = emptySet(),
          )
        },
    )
  }

  private companion object {
    val DEVKIT_CHAT_CAPABILITY_IDS =
      listOf(
        DeveloperCapabilityId.AndroidUse,
        DeveloperCapabilityId.AndroidKotlin,
        DeveloperCapabilityId.AndroidNative,
        DeveloperCapabilityId.Flutter,
        DeveloperCapabilityId.GodotAndroid,
        DeveloperCapabilityId.ReactNative,
        DeveloperCapabilityId.WebDevelopment,
      )
  }
}
