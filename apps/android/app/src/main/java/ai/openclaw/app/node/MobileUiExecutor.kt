package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.coroutines.flow.StateFlow

/** Exact Android surface against which one observation generation is valid. */
sealed interface MobileUiTarget {
  val packageName: String
  val displayId: Int

  data class MainDisplay(
    override val packageName: String,
  ) : MobileUiTarget {
    override val displayId: Int = 0
  }

  data class VScreenDisplay(
    override val packageName: String,
    override val displayId: Int,
    val attachmentId: String,
    val targetGeneration: Long,
    val bindingRevision: Long,
  ) : MobileUiTarget
}

/** Retained OpenClaw accessibility executor boundary used by product authorization adapters. */
interface MobileUiExecutor {
  val isConnected: StateFlow<Boolean>

  suspend fun handleObserve(
    target: MobileUiTarget,
    paramsJson: String?,
  ): GatewaySession.InvokeResult

  suspend fun handleAct(
    target: MobileUiTarget,
    paramsJson: String?,
  ): GatewaySession.InvokeResult

  fun isTargetAvailable(target: MobileUiTarget): Boolean
}
