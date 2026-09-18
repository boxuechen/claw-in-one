package ai.openclaw.app.androiduse

import ai.openclaw.app.gateway.GatewaySession
import ai.openclaw.app.vscreen.VScreenTargetReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidUseHandler internal constructor(
  @Suppress("UNUSED_PARAMETER") leaseController: AndroidUseLeaseController,
  @Suppress("UNUSED_PARAMETER") vscreenTargets: VScreenTargetReader,
  @Suppress("UNUSED_PARAMETER") currentOperatorConnection: () -> GatewaySession.RequestLease? = { null },
  @Suppress("UNUSED_PARAMETER") vscreenTargetPackage: (ai.openclaw.app.vscreen.VScreenTarget) -> String? = { null },
) {
  val isConnected: StateFlow<Boolean> = MutableStateFlow(false)

  suspend fun handle(
    @Suppress("UNUSED_PARAMETER") node: GatewaySession.RequestLease,
    @Suppress("UNUSED_PARAMETER") paramsJson: String?,
  ): GatewaySession.InvokeResult =
    GatewaySession.InvokeResult.error(
      code = "ANDROID_USE_UNAVAILABLE",
      message = "ANDROID_USE_UNAVAILABLE: accessibility control is not available on this build",
    )
}
