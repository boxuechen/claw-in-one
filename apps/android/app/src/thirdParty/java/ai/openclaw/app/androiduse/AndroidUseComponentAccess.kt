package ai.openclaw.app.androiduse

import ai.openclaw.app.accessibility.AccessibilityComponentController
import android.content.Context

internal fun setAndroidUseComponentsEnabled(
  context: Context,
  enabled: Boolean,
) {
  AccessibilityComponentController(context).setEnabled(enabled)
}
