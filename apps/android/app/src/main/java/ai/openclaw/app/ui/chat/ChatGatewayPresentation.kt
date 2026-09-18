package ai.openclaw.app.ui.chat

import ai.openclaw.app.GatewayConnectionDisplay
import ai.openclaw.app.chat.ChatGatewayFeature
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key

/** Flattened UI projection; replacement retires every Gateway-context subscription together. */
internal data class ChatGatewayPresentation(
  val connection: GatewayConnectionDisplay = GatewayConnectionDisplay(false, "Offline", null),
  val activeStableId: String? = null,
  val catalogRevision: Long = 0L,
  val operatorScopes: List<String> = emptyList(),
  val mainSessionKey: String = "main",
  val defaultAgentId: String? = null,
  val remoteAddress: String? = null,
)

@Composable
internal fun ChatGatewayFeature?.collectGatewayPresentation(): ChatGatewayPresentation =
  key(this) {
    val source = this
    ChatGatewayPresentation(
      connection =
        source
          ?.status
          ?.connection
          ?.collectAsState()
          ?.value
          ?: GatewayConnectionDisplay(false, "Offline", null),
      activeStableId =
        source
          ?.scope
          ?.activeStableId
          ?.collectAsState()
          ?.value,
      catalogRevision =
        source
          ?.scope
          ?.catalogRevision
          ?.collectAsState()
          ?.value ?: 0L,
      operatorScopes =
        source
          ?.scope
          ?.operatorScopes
          ?.collectAsState()
          ?.value
          .orEmpty(),
      mainSessionKey =
        source
          ?.routing
          ?.mainSessionKey
          ?.collectAsState()
          ?.value ?: "main",
      defaultAgentId =
        source
          ?.routing
          ?.defaultAgentId
          ?.collectAsState()
          ?.value,
      remoteAddress =
        source
          ?.status
          ?.remoteAddress
          ?.collectAsState()
          ?.value,
    )
  }
