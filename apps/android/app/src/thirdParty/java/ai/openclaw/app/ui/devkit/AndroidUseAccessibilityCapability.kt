package ai.openclaw.app.ui.devkit

import ai.openclaw.app.R
import ai.openclaw.app.devkit.AndroidUseCapabilityFeature
import ai.openclaw.app.i18n.nativeString
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun FlavorAndroidUseCapability(feature: AndroidUseCapabilityFeature) {
  val context = LocalContext.current
  val state by feature.state.collectAsState()
  var showDisclosure by rememberSaveable { mutableStateOf(false) }

  fun setControlEnabled(checked: Boolean) {
    if (checked) {
      showDisclosure = true
      return
    }
    feature.actions.setEnabled(false)
  }

  DevKitTogglePanel(
    rows =
      listOf(
        DevKitToggleRow(
          title = nativeString("Android Use"),
          subtitle =
            if (state.enabled) stringResource(R.string.android_use_all_chats) else stringResource(R.string.android_use_disabled),
          icon = Icons.AutoMirrored.Filled.ScreenShare,
          checked = state.enabled,
          onCheckedChange = ::setControlEnabled,
        ),
      ),
  )

  if (state.enabled && !state.serviceAvailable) {
    TextButton(onClick = { openAccessibilitySettings(context) }) {
      Text(stringResource(R.string.android_use_accessibility_repair))
    }
  }

  if (showDisclosure) {
    AccessibilityControlDisclosureDialog(
      onDismiss = { showDisclosure = false },
      onAgree = {
        showDisclosure = false
        feature.actions.setEnabled(true)
        if (!state.serviceAvailable) openAccessibilitySettings(context)
      },
    )
  }
}

@Composable
private fun AccessibilityControlDisclosureDialog(
  onDismiss: () -> Unit,
  onAgree: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.android_use_enable_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          stringResource(R.string.android_use_consent_disclosure),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onAgree) {
        Text(stringResource(R.string.android_use_allow_enable))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(nativeString("Not Now"))
      }
    },
  )
}

private fun openAccessibilitySettings(context: Context) {
  val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  context.startActivity(intent)
}
