package ai.openclaw.app.ui.extensions

import ai.openclaw.app.plugin.GatewayPluginDeclaredSurface
import ai.openclaw.app.plugin.GatewayPluginHookGrant
import ai.openclaw.app.plugin.GatewayPluginIdentity
import ai.openclaw.app.plugin.GatewayPluginInspectionDetails
import ai.openclaw.app.plugin.GatewayPluginInspectionState
import ai.openclaw.app.plugin.GatewayPluginInstallAction
import ai.openclaw.app.plugin.GatewayPluginMutationIntent
import ai.openclaw.app.plugin.GatewayPluginMutationState
import ai.openclaw.app.plugin.GatewayPluginOperatorGrants
import ai.openclaw.app.plugin.GatewayPluginTrust
import ai.openclaw.app.plugin.GatewayPluginTrustDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDetailPresentationTest {
  @Test
  fun inspectionProjectionIncludesCapabilitiesAndTrustWithoutReviewToken() {
    val details = fixtureDetails()
    val presentation = details.toPresentation()

    assertEquals(listOf("Tools", "Skills"), presentation.declaredSections.map { it.title })
    assertEquals(
      listOf(PluginIncludedKind.Skills, PluginIncludedKind.Tools),
      presentation.includedSections.map(PluginIncludedSection::kind),
    )
    assertEquals("Review required", presentation.trust?.label)
    assertTrue(presentation.trust?.warning == true)
    assertTrue(presentation.grantSections.any { it.title == "Hooks" })
  }

  @Test
  fun detailStateRejectsInspectionForAnotherPlugin() {
    val state =
      pluginDetailUiState(
        pluginId = "calendar",
        plugin = null,
        connected = true,
        inspectAvailable = true,
        inspectionState = GatewayPluginInspectionState.Ready("workboard", fixtureDetails()),
      )

    assertFalse(state.loading)
    assertNull(state.inspection)
    assertNull(state.inspectionErrorText)
  }

  @Test
  fun lifecycleProjectionKeepsSecurityStateOutOfScreens() {
    val intent =
      GatewayPluginMutationIntent.Install(
        action = GatewayPluginInstallAction.Official("workboard"),
        displayName = "Workboard",
      )
    val confirmation =
      pluginLifecyclePresentation(
        state = GatewayPluginMutationState.Confirmation(intent),
        canInstall = true,
        canSetEnabled = true,
        canUninstall = true,
      )
    val success =
      pluginLifecyclePresentation(
        state =
          GatewayPluginMutationState.Succeeded(
            intent = intent,
            restartRequired = true,
            warnings = listOf("Review configuration."),
          ),
        canInstall = true,
        canSetEnabled = true,
        canUninstall = true,
      )

    val installDialog = confirmation.dialog as PluginMutationDialogPresentation.Confirmation
    assertEquals("Workboard", installDialog.displayName)
    assertEquals(PluginMutationDialogPresentation.ConfirmationAction.Install, installDialog.action)
    assertEquals(PluginLifecycleNoticeTone.Success, success.notice?.tone)
    val notice =
      success.notice
        ?.text
        .orEmpty()
    assertFalse(notice, notice.contains("Restart OpenClaw"))
  }

  @Test
  fun managementProjectionDistinguishesEnablementAndDestructiveRemoval() {
    val enable =
      pluginLifecyclePresentation(
        state =
          GatewayPluginMutationState.Succeeded(
            intent = GatewayPluginMutationIntent.SetEnabled("workboard", "Workboard", enabled = true),
            restartRequired = false,
            warnings = emptyList(),
          ),
        canInstall = true,
        canSetEnabled = true,
        canUninstall = true,
      )
    val removal =
      pluginLifecyclePresentation(
        state =
          GatewayPluginMutationState.Confirmation(
            GatewayPluginMutationIntent.Uninstall("workboard", "Workboard"),
          ),
        canInstall = true,
        canSetEnabled = true,
        canUninstall = true,
      )

    assertTrue(
      enable.notice
        ?.text
        .orEmpty()
        .contains("enabled"),
    )
    val removalDialog = removal.dialog as PluginMutationDialogPresentation.Confirmation
    assertEquals("Workboard", removalDialog.displayName)
    assertEquals(PluginMutationDialogPresentation.ConfirmationAction.Remove, removalDialog.action)
  }

  private fun fixtureDetails(): GatewayPluginInspectionDetails =
    GatewayPluginInspectionDetails(
      plugin =
        GatewayPluginIdentity(
          id = "workboard",
          name = "Workboard",
          version = "1.0.0",
          description = null,
          origin = "clawhub",
          installed = true,
          enabled = false,
        ),
      source = null,
      declared =
        GatewayPluginDeclaredSurface(
          tools = listOf("workboard.plan"),
          skills = listOf("planning"),
        ),
      grants =
        GatewayPluginOperatorGrants(
          allowPromptInjection = GatewayPluginHookGrant(effective = false, configured = null),
          allowConversationAccess = GatewayPluginHookGrant(effective = true, configured = true),
          llm = null,
          subagent = null,
        ),
      trust =
        GatewayPluginTrust(
          disposition = GatewayPluginTrustDisposition.ReviewRequired,
          reasons = listOf("Community package"),
          checkedAt = null,
          acknowledgedAt = null,
          pending = false,
          stale = false,
        ),
    )
}
