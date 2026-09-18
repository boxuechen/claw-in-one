package ai.openclaw.app.androiduse

import ai.openclaw.app.accessibility.OpenClawAccessibilityService
import android.content.Intent
import kotlinx.coroutines.delay

private const val TARGET_READY_ATTEMPTS = 20
private const val TARGET_READY_POLL_MS = 100L

/** Accessibility-service-owned target handoff used only after canonical Gateway approval. */
internal class AndroidUseTargetAppController : AndroidUseTargetController {
  override suspend fun bringToForeground(packageName: String): AndroidUseTargetResult {
    val service =
      OpenClawAccessibilityService.instance
        ?: return AndroidUseTargetResult.Failed(
          "SERVICE_DISABLED",
          "Android accessibility control is unavailable",
        )
    if (service.foregroundPackageName() == packageName) return AndroidUseTargetResult.Ready

    val launchIntent =
      service.packageManager
        .getLaunchIntentForPackage(packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ?: return AndroidUseTargetResult.Failed(
          "TARGET_NOT_LAUNCHABLE",
          "The approved Android app cannot be opened",
        )
    val launched = runCatching { service.startActivity(launchIntent) }.isSuccess
    if (!launched) {
      return AndroidUseTargetResult.Failed(
        "TARGET_LAUNCH_FAILED",
        "The approved Android app could not be opened",
      )
    }
    repeat(TARGET_READY_ATTEMPTS) {
      if (service.foregroundPackageName() == packageName) return AndroidUseTargetResult.Ready
      delay(TARGET_READY_POLL_MS)
    }
    return AndroidUseTargetResult.Failed(
      "TARGET_NOT_FOREGROUND",
      "The approved Android app did not reach the foreground",
    )
  }
}
