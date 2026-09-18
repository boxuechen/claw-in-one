package ai.openclaw.app

import ai.openclaw.app.gateway.LocalGatewayPairing
import ai.openclaw.app.gateway.testGatewayEndpoint
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeRuntimeAgentSelectionTest {
  @Before
  fun clearNavigationPreferences() {
    RuntimeEnvironment
      .getApplication()
      .getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  @Test
  fun switchingConversationPersistsOnlyTheProductSelectionForTheActiveGateway() {
    val app = RuntimeEnvironment.getApplication()
    val securePrefs =
      app.getSharedPreferences(
        "openclaw.node.secure.test.${UUID.randomUUID()}",
        Context.MODE_PRIVATE,
      )
    val prefs = SecurePrefs(app, securePrefsOverride = securePrefs)
    val endpoint = testGatewayEndpoint("127.0.0.1", 18789)
    prefs.localGatewayPairing.replace(
      LocalGatewayPairing.from(
        endpoint = endpoint,
        credentials = GatewayCredentials(),
      ),
    )
    // This checks local navigation/persistence, not asynchronous connection adoption.
    // Use the live runtime's reconnect-suppressed entry point so a startup connection
    // to the dummy endpoint cannot reset selection between switching and persisting.
    val runtime = NodeRuntime.forGatewayAuthReset(app, prefs)

    try {
      runtime.chatDirectory.navigation.select(" agent:main:dashboard:fresh ", " main ")

      assertEquals("agent:main:dashboard:fresh", runtime.chatHistory.selection.key.value)
      assertEquals("main", runtime.chatHistory.selection.ownerAgentId.value)
      assertEquals(
        ChatConversationSelection("agent:main:dashboard:fresh", "main"),
        prefs.loadChatConversationSelection(endpoint.stableId),
      )
    } finally {
      closeNodeRuntimeTestFixture(runtime)
    }
  }
}
