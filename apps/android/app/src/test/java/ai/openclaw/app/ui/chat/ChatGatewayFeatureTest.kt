package ai.openclaw.app.ui.chat

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.chat.ChatGatewayFeature
import ai.openclaw.app.chat.ChatGatewayRoutingFeature
import ai.openclaw.app.chat.ChatGatewayScopeFeature
import ai.openclaw.app.chat.ChatGatewayStatusFeature
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatGatewayFeatureTest {
  @get:Rule val composeRule = createComposeRule()

  @Test fun absentFeatureUsesOfflineDefaultsWithoutConstructingARuntime() {
    composeRule.setContent { GatewayProbe(null) }
    composeRule.onNodeWithText("Offline / none / main").assertIsDisplayed()
  }

  @Test fun replacementRetiresTheWholeGatewayContextTogether() {
    val first = GatewayContext("first")
    val second = GatewayContext("second")
    var source by mutableStateOf<ChatGatewayFeature?>(first.feature)
    composeRule.setContent { GatewayProbe(source) }
    composeRule.onNodeWithText("first / first / agent:first:main").assertIsDisplayed()

    composeRule.runOnIdle { source = second.feature }
    composeRule.onNodeWithText("second / second / agent:second:main").assertIsDisplayed()
    composeRule.runOnIdle {
      first.connection.value = GatewayConnectionDisplay(true, "late-first", null)
      first.mainSessionKey.value = "agent:first:late"
    }
    composeRule.onNodeWithText("late-first / first / agent:first:late").assertDoesNotExist()
    composeRule.onNodeWithText("second / second / agent:second:main").assertIsDisplayed()
  }

  @androidx.compose.runtime.Composable
  private fun GatewayProbe(feature: ChatGatewayFeature?) {
    val state = feature.collectGatewayPresentation()
    Text("${state.connection.statusText} / ${state.activeStableId ?: "none"} / ${state.mainSessionKey}")
  }

  private class GatewayContext(
    id: String,
  ) {
    val connection = MutableStateFlow(GatewayConnectionDisplay(true, id, null))
    val mainSessionKey = MutableStateFlow("agent:$id:main")
    val feature =
      ChatGatewayFeature(
        status =
          ChatGatewayStatusFeature(
            connection = connection,
            remoteAddress = MutableStateFlow("$id.example"),
          ),
        scope =
          ChatGatewayScopeFeature(
            activeStableId = MutableStateFlow(id),
            catalogRevision = MutableStateFlow(1L),
            operatorScopes = MutableStateFlow(listOf("operator.admin")),
          ),
        routing =
          ChatGatewayRoutingFeature(
            mainSessionKey = mainSessionKey,
            defaultAgentId = MutableStateFlow(id),
          ),
      )
  }
}
