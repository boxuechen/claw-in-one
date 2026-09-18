package ai.openclaw.app.androiduse

internal sealed interface AndroidUseTargetResult {
  data object Ready : AndroidUseTargetResult

  data class Failed(
    val code: String,
    val message: String,
  ) : AndroidUseTargetResult
}

/** Brings one reviewed package to the foreground before a control lease is minted. */
internal fun interface AndroidUseTargetController {
  suspend fun bringToForeground(packageName: String): AndroidUseTargetResult
}
