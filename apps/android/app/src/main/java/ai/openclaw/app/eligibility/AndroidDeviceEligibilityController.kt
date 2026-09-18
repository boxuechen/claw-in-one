package ai.openclaw.app.eligibility

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val LINUX_TERMINAL_PACKAGE = "com.android.virtualization.terminal"
internal const val AVF_SYSTEM_FEATURE = "android.software.virtualization_framework"
internal const val SYSTEM_UPDATE_SETTINGS_ACTION = "android.settings.SYSTEM_UPDATE_SETTINGS"

internal enum class DeviceEligibilityAction {
  OpenDeviceInfo,
  OpenDeveloperSettings,
  OpenSystemUpdate,
  OpenTerminal,
}

internal fun deviceEligibilityIntent(action: DeviceEligibilityAction): Intent =
  when (action) {
    DeviceEligibilityAction.OpenDeviceInfo -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
    DeviceEligibilityAction.OpenDeveloperSettings -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    DeviceEligibilityAction.OpenSystemUpdate -> Intent(SYSTEM_UPDATE_SETTINGS_ACTION)
    DeviceEligibilityAction.OpenTerminal ->
      Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(LINUX_TERMINAL_PACKAGE)
  }

internal fun launchDeviceEligibilityAction(
  context: Context,
  action: DeviceEligibilityAction,
): Boolean =
  runCatching {
    val intent =
      if (action == DeviceEligibilityAction.OpenTerminal) {
        context.packageManager.getLaunchIntentForPackage(LINUX_TERMINAL_PACKAGE)
          ?: return false
      } else {
        deviceEligibilityIntent(action)
      }
    context.startActivity(intent)
    true
  }.getOrDefault(false)

internal fun readDeviceEligibilityEvidence(context: Context): DeviceEligibilityEvidence {
  val packageManager = context.packageManager
  val terminalPackage = readTerminalPackageEvidence(packageManager)
  return DeviceEligibilityEvidence(
    androidApi = Build.VERSION.SDK_INT,
    supportedAbis = Build.SUPPORTED_ABIS.toList(),
    avfAvailable = runCatching { packageManager.hasSystemFeature(AVF_SYSTEM_FEATURE) }.getOrNull(),
    terminalPackage = terminalPackage,
    developerOptionsEnabled =
      runCatching {
        Settings.Global.getInt(
          context.contentResolver,
          Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
          0,
        ) == 1
      }.getOrNull(),
    terminalLaunchable =
      if (terminalPackage == TerminalPackageEvidence.Unknown) {
        null
      } else {
        runCatching { packageManager.getLaunchIntentForPackage(LINUX_TERMINAL_PACKAGE) != null }.getOrNull()
      },
  )
}

@Suppress("DEPRECATION")
private fun readTerminalPackageEvidence(packageManager: PackageManager): TerminalPackageEvidence =
  try {
    val app =
      packageManager.getApplicationInfo(
        LINUX_TERMINAL_PACKAGE,
        PackageManager.MATCH_DISABLED_COMPONENTS,
      )
    val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    if (app.flags and systemFlags != 0) TerminalPackageEvidence.System else TerminalPackageEvidence.UserInstalled
  } catch (_: PackageManager.NameNotFoundException) {
    TerminalPackageEvidence.Missing
  } catch (_: RuntimeException) {
    TerminalPackageEvidence.Unknown
  }

/** Process owner for current Android evidence; it never persists a readiness receipt. */
internal class AndroidDeviceEligibilityController(
  private val evidenceReader: () -> DeviceEligibilityEvidence,
) {
  constructor(context: Context) : this(
    evidenceReader = { readDeviceEligibilityEvidence(context.applicationContext) },
  )

  private val _state = MutableStateFlow(DeviceEligibilitySnapshot())
  val state: StateFlow<DeviceEligibilitySnapshot> = _state.asStateFlow()

  fun refresh() {
    val evidence = runCatching(evidenceReader).getOrElse { DeviceEligibilityEvidence() }
    _state.value = DeviceEligibilitySnapshot(evidence, resolveDeviceEligibility(evidence))
  }
}
