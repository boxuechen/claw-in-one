package ai.openclaw.app.ui.devkit

import ai.openclaw.app.devkit.AndroidUseAuthorizationStatus
import ai.openclaw.app.devkit.AndroidUseCapabilityState
import ai.openclaw.app.devkit.DeveloperCapability
import ai.openclaw.app.devkit.DeveloperCapabilityAction
import ai.openclaw.app.devkit.DeveloperCapabilityActivationPolicy
import ai.openclaw.app.devkit.DeveloperCapabilityCatalogState
import ai.openclaw.app.devkit.DeveloperCapabilityGroup
import ai.openclaw.app.devkit.DeveloperCapabilityId
import ai.openclaw.app.devkit.DeveloperCapabilityProvisioning
import ai.openclaw.app.devkit.DeveloperCapabilityStatus
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
@Config(sdk = [34], qualifiers = "w412dp-h700dp-420dpi")
class DevKitScreensTest {
  @get:Rule val composeRule = createComposeRule()

  @Before
  fun english() {
    NativeStringResources.install(RuntimeEnvironment.getApplication())
    NativeStringResources.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
  }

  @Test
  fun homeRendersOnlyImplementedCapabilitiesInTheirGroups() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        DevKitHomeScreen(state = catalog(), onOpen = {}, onBack = {})
      }
    }

    composeRule.onNodeWithText("DevKit").assertIsDisplayed()
    composeRule.onNodeWithTag("devkit-summary").assertDoesNotExist()
    composeRule.onNodeWithText("Developer capabilities and toolchains").assertDoesNotExist()

    fun assertCapability(id: DeveloperCapabilityId) {
      composeRule.onNodeWithTag("devkit-${id.name}").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("devkit-icon-${id.name}", useUnmergedTree = true).assertIsDisplayed()
    }

    composeRule.onNodeWithText("Environment").assertIsDisplayed()
    assertCapability(DeveloperCapabilityId.OpenClawEnvironment)
    composeRule.onNodeWithText("Device capabilities").assertIsDisplayed()
    listOf(
      DeveloperCapabilityId.AndroidDeviceConnection,
      DeveloperCapabilityId.VScreen,
      DeveloperCapabilityId.AndroidUse,
    ).forEach(::assertCapability)
    composeRule.onNodeWithText("Development stacks").assertIsDisplayed()
    listOf(
      DeveloperCapabilityId.AndroidKotlin,
      DeveloperCapabilityId.AndroidNative,
      DeveloperCapabilityId.Flutter,
      DeveloperCapabilityId.GodotAndroid,
      DeveloperCapabilityId.ReactNative,
      DeveloperCapabilityId.WebDevelopment,
    ).forEach(::assertCapability)
  }

  @Test
  fun homeShowsCompactSummaryOnlyWhenAttentionIsRequired() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        DevKitHomeScreen(
          state = catalog(androidEnvironmentStatus = DeveloperCapabilityStatus.NeedsRepair),
          onOpen = {},
          onBack = {},
        )
      }
    }

    composeRule.onNodeWithTag("devkit-summary").assertIsDisplayed()
    composeRule.onNodeWithText("Needs attention").assertIsDisplayed()
  }

  @Test
  fun androidUseStatusFollowsConsentAndAuthorization() {
    assertEquals(nativeText("Disabled"), androidUseCapabilityStatusText(AndroidUseCapabilityState()))
    assertEquals(
      nativeText("Ready"),
      androidUseCapabilityStatusText(
        AndroidUseCapabilityState(
          enabled = true,
          serviceAvailable = true,
          available = true,
          authorization = AndroidUseAuthorizationStatus.Approved,
        ),
      ),
    )
    val reapproval =
      AndroidUseCapabilityState(
        enabled = true,
        authorization = AndroidUseAuthorizationStatus.ReapprovalRequired,
      )
    assertEquals(nativeText("Needs authorization"), androidUseCapabilityStatusText(reapproval))
    assertTrue(reapproval.needsAuthorizationAttention())
  }

  @Test
  fun readyDevelopmentStackOffersUseInChat() {
    var useCount = 0
    composeRule.setContent {
      ProvideClawDesignSystem {
        DevKitCapabilityDetailScreen(
          capability =
            DeveloperCapability(
              id = DeveloperCapabilityId.AndroidKotlin,
              group = DeveloperCapabilityGroup.Development,
              provisioning = DeveloperCapabilityProvisioning.OptionalExtension,
              activationPolicy = DeveloperCapabilityActivationPolicy.OnDemand,
              status = DeveloperCapabilityStatus.Ready,
              allowedActions = setOf(DeveloperCapabilityAction.UseInChat),
              skillReference = "android-development",
            ),
          onPrimaryAction = { useCount += 1 },
          onBack = {},
        )
      }
    }

    composeRule.onNodeWithTag("devkit-use-in-chat").assertIsDisplayed().performClick()
    composeRule.runOnIdle { assertEquals(1, useCount) }
  }

  @Test
  fun vscreenFailureKeepsItsFocusedRecoveryAction() {
    composeRule.setContent {
      ProvideClawDesignSystem {
        DevKitCapabilityDetailScreen(
          capability =
            DeveloperCapability(
              id = DeveloperCapabilityId.VScreen,
              group = DeveloperCapabilityGroup.Device,
              provisioning = DeveloperCapabilityProvisioning.BuiltIn,
              activationPolicy = DeveloperCapabilityActivationPolicy.OnDemand,
              status = DeveloperCapabilityStatus.NeedsRepair,
              allowedActions = setOf(DeveloperCapabilityAction.Open, DeveloperCapabilityAction.Repair),
            ),
          onPrimaryAction = {},
          onBack = {},
        )
      }
    }

    composeRule.onNodeWithText("Try VScreen again").assertIsDisplayed()
    composeRule.onNodeWithText("Open environment").assertDoesNotExist()
  }

  private fun catalog(androidEnvironmentStatus: DeveloperCapabilityStatus = DeveloperCapabilityStatus.Ready) =
    DeveloperCapabilityCatalogState(
      DeveloperCapabilityId.entries.map { id ->
        DeveloperCapability(
          id = id,
          group =
            when (id) {
              DeveloperCapabilityId.OpenClawEnvironment -> DeveloperCapabilityGroup.Environment
              DeveloperCapabilityId.AndroidDeviceConnection,
              DeveloperCapabilityId.VScreen,
              DeveloperCapabilityId.AndroidUse,
              -> DeveloperCapabilityGroup.Device
              DeveloperCapabilityId.AndroidKotlin,
              DeveloperCapabilityId.AndroidNative,
              DeveloperCapabilityId.Flutter,
              DeveloperCapabilityId.GodotAndroid,
              DeveloperCapabilityId.ReactNative,
              DeveloperCapabilityId.WebDevelopment,
              -> DeveloperCapabilityGroup.Development
            },
          provisioning =
            when (id) {
              DeveloperCapabilityId.OpenClawEnvironment ->
                DeveloperCapabilityProvisioning.RequiredEnvironment
              DeveloperCapabilityId.AndroidDeviceConnection,
              DeveloperCapabilityId.VScreen,
              DeveloperCapabilityId.AndroidUse,
              -> DeveloperCapabilityProvisioning.BuiltIn
              else -> DeveloperCapabilityProvisioning.OptionalExtension
            },
          activationPolicy =
            when (id) {
              DeveloperCapabilityId.OpenClawEnvironment -> DeveloperCapabilityActivationPolicy.AlwaysOn
              DeveloperCapabilityId.AndroidUse -> DeveloperCapabilityActivationPolicy.UserControlled
              else -> DeveloperCapabilityActivationPolicy.OnDemand
            },
          status =
            when (id) {
              DeveloperCapabilityId.OpenClawEnvironment -> androidEnvironmentStatus
              DeveloperCapabilityId.AndroidUse -> DeveloperCapabilityStatus.Disabled
              else -> DeveloperCapabilityStatus.Ready
            },
          allowedActions = emptySet(),
        )
      },
    )
}
