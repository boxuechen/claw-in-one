package ai.openclaw.app.ui.devkit

import ai.openclaw.app.devkit.DeveloperCapability
import ai.openclaw.app.devkit.DeveloperCapabilityAction
import ai.openclaw.app.devkit.DeveloperCapabilityCatalogState
import ai.openclaw.app.devkit.DeveloperCapabilityGroup
import ai.openclaw.app.devkit.DeveloperCapabilityId
import ai.openclaw.app.devkit.DeveloperCapabilityStatus
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawScaffold
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun DevKitHomeScreen(
  state: DeveloperCapabilityCatalogState,
  onOpen: (DeveloperCapabilityId) -> Unit,
  onBack: () -> Unit,
) {
  ClawScaffold(
    contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp),
    containerColor = ClawTheme.colors.canvas,
  ) {
    Column(Modifier.fillMaxSize()) {
      DevKitNavigationHeader(
        title = nativeString("DevKit"),
        onBack = onBack,
        backModifier = Modifier.testTag("devkit-back"),
      )
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag("devkit-list"),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        if (state.needsAttention) {
          item {
            Text(
              nativeString("Needs attention"),
              style = ClawTheme.type.caption,
              color = ClawTheme.colors.warning,
              modifier = Modifier.testTag("devkit-summary"),
            )
          }
        }
        DeveloperCapabilityGroup.entries.forEach { group ->
          val capabilities = state.capabilities.filter { it.group == group }
          if (capabilities.isNotEmpty()) {
            item {
              Text(
                developerCapabilityGroupLabel(group),
                style = ClawTheme.type.section,
                color = ClawTheme.colors.textMuted,
              )
            }
            item {
              ClawPanel(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Column {
                  capabilities.forEachIndexed { index, capability ->
                    DevKitCapabilityRow(capability = capability, onClick = { onOpen(capability.id) })
                    if (index != capabilities.lastIndex) {
                      HorizontalDivider(color = ClawTheme.colors.border.copy(alpha = 0.82f))
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DevKitCapabilityRow(
  capability: DeveloperCapability,
  onClick: () -> Unit,
) {
  val statusLabel = developerCapabilityStatusLabel(capability.status)
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .heightIn(min = ClawTheme.sizes.minimumTouchTarget)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { stateDescription = statusLabel }
        .testTag("devkit-${capability.id.name}"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    DevKitCapabilityIcon(capability.id)
    Column(
      modifier = Modifier.weight(1f).padding(vertical = 11.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(developerCapabilityTitle(capability.id), style = ClawTheme.type.body, color = ClawTheme.colors.text)
      capability.detail?.let { detail ->
        Text(
          detail,
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.textMuted,
        )
      }
      capability.progress?.let { progress ->
        LinearProgressIndicator(
          progress = { progress.completedBytes.toFloat() / progress.totalBytes },
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
      }
    }
    Text(
      statusLabel,
      style = ClawTheme.type.caption,
      color = if (capability.status.needsAttention()) ClawTheme.colors.warning else ClawTheme.colors.textMuted,
    )
  }
}

@Composable
internal fun DevKitCapabilityDetailScreen(
  capability: DeveloperCapability,
  onPrimaryAction: () -> Unit,
  onBack: () -> Unit,
) {
  DevKitDetailFrame(
    title = developerCapabilityTitle(capability.id),
    subtitle = developerCapabilityDescription(capability.id),
    onBack = onBack,
  ) {
    ClawPanel {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(nativeString("Status"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
        Text(
          developerCapabilityStatusLabel(capability.status),
          style = ClawTheme.type.body,
          color = if (capability.status.needsAttention()) ClawTheme.colors.warning else ClawTheme.colors.textMuted,
        )
        capability.progress?.let { progress ->
          LinearProgressIndicator(
            progress = { progress.completedBytes.toFloat() / progress.totalBytes },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          )
        }
        Text(
          nativeString(
            when (capability.provisioning) {
              ai.openclaw.app.devkit.DeveloperCapabilityProvisioning.RequiredEnvironment -> "Required environment"
              ai.openclaw.app.devkit.DeveloperCapabilityProvisioning.BuiltIn -> "Built into ClawInOne"
              ai.openclaw.app.devkit.DeveloperCapabilityProvisioning.OptionalExtension -> "Managed by DevKit"
            },
          ),
          style = ClawTheme.type.caption,
          color = ClawTheme.colors.textMuted,
        )
      }
    }
    when {
      DeveloperCapabilityAction.UseInChat in capability.allowedActions ->
        ClawPrimaryButton(
          nativeString("Use in Chat"),
          onPrimaryAction,
          Modifier.fillMaxWidth().testTag("devkit-use-in-chat"),
        )
      capability.id == DeveloperCapabilityId.VScreen && capability.status == DeveloperCapabilityStatus.NeedsRepair ->
        ClawPrimaryButton(nativeString("Try VScreen again"), onPrimaryAction, Modifier.fillMaxWidth())
      DeveloperCapabilityAction.Open in capability.allowedActions ->
        ClawPrimaryButton(nativeString("Open VScreen"), onPrimaryAction, Modifier.fillMaxWidth())
      DeveloperCapabilityAction.Repair in capability.allowedActions ->
        ClawPrimaryButton(
          nativeString(
            if (
              capability.id in
              setOf(
                DeveloperCapabilityId.AndroidNative,
                DeveloperCapabilityId.AndroidKotlin,
                DeveloperCapabilityId.Flutter,
                DeveloperCapabilityId.GodotAndroid,
                DeveloperCapabilityId.ReactNative,
                DeveloperCapabilityId.WebDevelopment,
              )
            ) {
              "Repair"
            } else {
              "Open environment"
            },
          ),
          onPrimaryAction,
          Modifier.fillMaxWidth(),
        )
      DeveloperCapabilityAction.Install in capability.allowedActions ->
        ClawPrimaryButton(nativeString("Install"), onPrimaryAction, Modifier.fillMaxWidth())
      DeveloperCapabilityAction.Retry in capability.allowedActions ->
        ClawPrimaryButton(nativeString("Try again"), onPrimaryAction, Modifier.fillMaxWidth())
    }
  }
}

@Composable
internal fun developerCapabilityTitle(id: DeveloperCapabilityId): String =
  nativeString(
    when (id) {
      DeveloperCapabilityId.OpenClawEnvironment -> "OpenClaw environment"
      DeveloperCapabilityId.AndroidDeviceConnection -> "Android device connection"
      DeveloperCapabilityId.VScreen -> "VScreen"
      DeveloperCapabilityId.AndroidUse -> "Android Use"
      DeveloperCapabilityId.AndroidKotlin -> "Android Kotlin"
      DeveloperCapabilityId.AndroidNative -> "Android Native"
      DeveloperCapabilityId.Flutter -> "Flutter"
      DeveloperCapabilityId.GodotAndroid -> "Godot Android"
      DeveloperCapabilityId.ReactNative -> "React Native"
      DeveloperCapabilityId.WebDevelopment -> "Web Development"
    },
  )

@Composable
private fun developerCapabilityDescription(id: DeveloperCapabilityId): String =
  nativeString(
    when (id) {
      DeveloperCapabilityId.OpenClawEnvironment -> "The required local runtime for OpenClaw, Chromium, ADB, and VScreen."
      DeveloperCapabilityId.AndroidDeviceConnection -> "Connect the verified phone for app delivery and VScreen."
      DeveloperCapabilityId.VScreen -> "Open the global interactive Android screen."
      DeveloperCapabilityId.AndroidUse -> "Allow AI to inspect and operate Android when requested."
      DeveloperCapabilityId.AndroidKotlin -> "Install the pinned Kotlin, Compose, and Android build stack when needed."
      DeveloperCapabilityId.AndroidNative -> "Build ARM64 Android apps with the pinned NDK, CMake, and Vulkan starter."
      DeveloperCapabilityId.Flutter -> "Build Android apps with the pinned native ARM64 Flutter and Dart toolchain."
      DeveloperCapabilityId.GodotAndroid -> "Create interactive 3D Android games with pinned native ARM64 Godot and export templates."
      DeveloperCapabilityId.ReactNative -> "Build standalone ARM64 Android apps with pinned React Native, Developer Node, and Hermes."
      DeveloperCapabilityId.WebDevelopment -> "Build React and TypeScript frontends with a bounded Node API server for Android Chrome."
    },
  )

@Composable
private fun developerCapabilityGroupLabel(group: DeveloperCapabilityGroup): String =
  nativeString(
    when (group) {
      DeveloperCapabilityGroup.Environment -> "Environment"
      DeveloperCapabilityGroup.Device -> "Device capabilities"
      DeveloperCapabilityGroup.Development -> "Development stacks"
    },
  )

@Composable
internal fun developerCapabilityStatusLabel(status: DeveloperCapabilityStatus): String =
  nativeString(
    when (status) {
      DeveloperCapabilityStatus.Checking -> "Checking…"
      DeveloperCapabilityStatus.NotInstalled -> "Not installed"
      DeveloperCapabilityStatus.Downloading -> "Downloading"
      DeveloperCapabilityStatus.Installing -> "Installing"
      DeveloperCapabilityStatus.Verifying -> "Verifying"
      DeveloperCapabilityStatus.Ready -> "Ready"
      DeveloperCapabilityStatus.Disabled -> "Disabled"
      DeveloperCapabilityStatus.NeedsPermission -> "Needs permission"
      DeveloperCapabilityStatus.NeedsRepair -> "Needs repair"
      DeveloperCapabilityStatus.Failed -> "Failed"
    },
  )

private fun DeveloperCapabilityStatus.needsAttention() =
  this in
    setOf(
      DeveloperCapabilityStatus.NeedsPermission,
      DeveloperCapabilityStatus.NeedsRepair,
      DeveloperCapabilityStatus.Failed,
    )
