package ai.openclaw.app.ui.settings

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.GatewayConnectionProblem
import ai.openclaw.app.i18n.nativeText
import ai.openclaw.app.ui.gatewayStatusLabel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SettingsScreensTest {
  @Test
  fun settingsHasOnlyTheFourTargetTopLevelDestinations() {
    assertEquals(
      setOf(
        SettingsDestination.AiModels,
        SettingsDestination.Appearance,
        SettingsDestination.LocalEnvironment,
        SettingsDestination.About,
      ),
      topLevelSettingsDestinations,
    )
  }

  @Test
  fun settingsDirectoryDoesNotPromoteNestedDestinations() {
    val allRows =
      SettingsDestination.entries.map { route ->
        SettingsRow(
          title = nativeText(route.name),
          value = nativeText("Value"),
          icon = Icons.Default.Settings,
          route = route,
        )
      }

    val homeRoutes = settingsHomeSections(allRows).flatMap(SettingsSection::rows).mapNotNull(SettingsRow::route)

    assertEquals(topLevelSettingsDestinations, homeRoutes.toSet())
  }

  @Test
  fun settingsRowsDecorateOnlyActionableProblems() {
    val healthy = SettingsRow(nativeText("Health"), nativeText("Ready"), Icons.Default.Settings)
    val problem = healthy.copy(needsAttention = true)

    assertFalse(healthy.showsAttentionIndicator())
    assertTrue(problem.showsAttentionIndicator())
  }

  @Test
  fun androidDistributionChannelUsesBuildFlavorLabels() {
    assertEquals("Play", androidDistributionChannel("play"))
    assertEquals("Third-party", androidDistributionChannel("thirdParty"))
    assertEquals("Unknown", androidDistributionChannel(""))
    assertEquals("enterpriseInternal", androidDistributionChannel("enterpriseInternal"))
  }

  @Test
  fun aboutFallbacksLocalizeOnlyControlledLabels() {
    assertEquals("Website", aboutLinkTitle("Website"))
    assertEquals("Docs", aboutLinkTitle("Docs"))
    assertEquals("GitHub", aboutLinkTitle("GitHub"))
    assertEquals("Custom", aboutLinkTitle("Custom"))
  }

  @Test
  fun aboutBuildIdentityFormatsVersionShortCommitAndUtcDate() {
    val identity =
      aboutBuildIdentity(
        versionName = "0.1.0",
        versionCode = 1,
        gitCommit = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
        buildTimestamp = "2026-07-10T00:30:00.000Z",
        locale = Locale.US,
        unknownLabel = "Unknown",
      )

    assertEquals("0.1.0 (1)", identity.version)
    assertEquals("abcdef012345", identity.commit)
    assertEquals("abcdef0123456789abcdef0123456789abcdef01", identity.fullCommit)
    assertEquals("Jul 10, 2026", identity.built)
    assertEquals("2026-07-10T00:30:00.000Z", identity.buildTimestamp)
  }

  @Test
  fun aboutBuildIdentityKeepsUnknownFallbacksVisible() {
    val identity =
      aboutBuildIdentity(
        versionName = "dev",
        versionCode = 1,
        gitCommit = "unknown",
        buildTimestamp = "unknown",
        locale = Locale.US,
        unknownLabel = "Unbekannt",
      )

    assertEquals("dev (1)", identity.version)
    assertEquals("Unbekannt", identity.commit)
    assertEquals(null, identity.fullCommit)
    assertEquals("Unbekannt", identity.built)
    assertEquals(null, identity.buildTimestamp)
    assertEquals("Unbekannt", aboutCommitAccessibilityValue(identity.fullCommit, "Unbekannt"))
  }

  @Test
  fun aboutCommitAccessibilityValueSpellsTheFullHash() {
    val commit = "abcdef0123456789abcdef0123456789abcdef01"

    assertEquals(
      commit.toCharArray().joinToString(" "),
      aboutCommitAccessibilityValue(commit, "Unknown"),
    )
  }

  @Test
  fun gatewayStatusLabelReportsWhichAuthRecoveryAppliesInsteadOfGenericLabel() {
    assertEquals(
      "Setup code expired",
      gatewayStatusLabel(
        "Gateway error: unauthorized: bootstrap token invalid or expired",
        isConnected = false,
        gatewayConnectionProblem = authProblem("AUTH_BOOTSTRAP_TOKEN_INVALID"),
      ),
    )
    assertEquals(
      "Device identity required",
      gatewayStatusLabel(
        "Gateway error: device identity required",
        isConnected = false,
        gatewayConnectionProblem = authProblem("DEVICE_IDENTITY_REQUIRED"),
      ),
    )
  }

  @Test
  fun gatewayStatusLabelFallsBackToGenericAuthLabelWithoutAKnownReason() {
    assertEquals("Authentication needed", gatewayStatusLabel("auth failed", isConnected = false, gatewayConnectionProblem = null))
    assertEquals(
      "Authentication needed",
      gatewayStatusLabel("auth failed", isConnected = false, gatewayConnectionProblem = authProblem("SOME_UNMAPPED_CODE")),
    )
  }

  @Test
  fun gatewayStatusLabelLeavesUnrelatedStatesUnaffectedByConnectionProblem() {
    val problem = authProblem("AUTH_TOKEN_MISSING")
    assertEquals("Ready", gatewayStatusLabel("auth failed", isConnected = true, gatewayConnectionProblem = authProblem("AUTH_TOKEN_MISSING")))
    assertEquals("Pairing needed", gatewayStatusLabel("Pairing in progress", isConnected = false, gatewayConnectionProblem = problem))
    assertEquals("Cannot reach gateway", gatewayStatusLabel("Connection failed", isConnected = false, gatewayConnectionProblem = problem))
  }

  @Test
  fun gatewayStatusLabelPreservesPartialConnectivity() {
    assertEquals(
      "Connected (node offline)",
      gatewayStatusLabel(
        GatewayConnectionDisplay(
          isConnected = true,
          statusText = "Connected (node offline)",
          problem = null,
        ),
      ),
    )
    assertEquals(
      "Connected (operator offline)",
      gatewayStatusLabel(
        GatewayConnectionDisplay(
          isConnected = false,
          statusText = "Connected (operator offline)",
          problem = null,
        ),
      ),
    )
  }

  private fun authProblem(code: String): GatewayConnectionProblem =
    GatewayConnectionProblem(
      code = code,
      message = "Authentication failed.",
      reason = null,
      requestId = null,
      recommendedNextStep = null,
      pauseReconnect = false,
      retryable = false,
    )
}
