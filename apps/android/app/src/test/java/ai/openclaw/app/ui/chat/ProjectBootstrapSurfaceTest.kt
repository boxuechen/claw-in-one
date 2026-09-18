package ai.openclaw.app.ui.chat

import ai.openclaw.app.project.DevelopmentCapabilitiesState
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.ui.design.ProvideClawDesignSystem
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectBootstrapSurfaceTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun enabledStarterCommitsOneClick() {
    val starters = projectStarters(readiness())
    val starter = starters.first()
    val clicks = mutableListOf<ProjectStarter>()
    composeRule.setContent {
      ProvideClawDesignSystem {
        ProjectBootstrapSurface(
          starters = starters,
          enabled = true,
          onStart = clicks::add,
        )
      }
    }

    composeRule.onNodeWithTag("project-bootstrap-logo").assertExists()
    starters.forEach {
      composeRule.onNodeWithText(it.title).assertExists()
    }
    composeRule.onNodeWithTag("project-starter-${starter.id}").assertHasClickAction().performClick()
    composeRule
      .onNodeWithText("Create an offline task app, build it on this phone, and open it in VScreen.")
      .assertDoesNotExist()
    composeRule.onNodeWithText("Name and create this Project").assertDoesNotExist()

    composeRule.runOnIdle { assertEquals(listOf(starter), clicks) }
  }

  @Test
  fun unavailableStarterIsVisiblyDisabled() {
    val starter = projectStarters(readiness()).first()
    composeRule.setContent {
      ProvideClawDesignSystem {
        ProjectBootstrapSurface(
          starters = listOf(starter),
          enabled = false,
          onStart = { error("disabled starter clicked") },
        )
      }
    }

    composeRule.onNodeWithTag("project-starter-${starter.id}").assertIsNotEnabled()
  }

  private fun readiness() =
    DevelopmentCapabilitiesState.Ready(
      "revision",
      listOf(
        DevelopmentCapability.AndroidKotlin,
        DevelopmentCapability.Flutter,
        DevelopmentCapability.GodotAndroid,
        DevelopmentCapability.WebDevelopment,
      ),
    )
}
