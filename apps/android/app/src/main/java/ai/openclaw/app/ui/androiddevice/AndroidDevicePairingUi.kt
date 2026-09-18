package ai.openclaw.app.ui.androiddevice

import ai.openclaw.app.androiddevice.AndroidDevicePairingPrompt
import ai.openclaw.app.i18n.nativeString
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

internal data class AndroidDevicePairingUi(
  val issue: String?,
  val start: () -> Unit,
  val openDeveloperOptions: () -> Unit,
  val openNotificationSettings: () -> Unit,
)

/** Shared platform presentation for the one-time same-phone pairing handoff. */
@Composable
internal fun rememberAndroidDevicePairingUi(): AndroidDevicePairingUi {
  val context = LocalContext.current
  val pairingPrompt = remember(context) { AndroidDevicePairingPrompt(context) }
  var issue by remember { mutableStateOf<String?>(null) }

  fun launchSystemPairing() {
    if (pairingPrompt.show()) {
      issue = null
      openDeveloperOptions(context)
    } else {
      issue = nativeString("Allow notifications so the pairing dialog can stay open while you enter its details.")
    }
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        launchSystemPairing()
      } else {
        issue = nativeString("Notifications are required only during the one-time development pairing flow.")
      }
    }

  return AndroidDevicePairingUi(
    issue = issue,
    start = {
      if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
      ) {
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        launchSystemPairing()
      }
    },
    openDeveloperOptions = { openDeveloperOptions(context) },
    openNotificationSettings = { openNotificationSettings(context) },
  )
}

private fun openDeveloperOptions(context: Context) {
  val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }
}

private fun openNotificationSettings(context: Context) {
  val intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }
}
