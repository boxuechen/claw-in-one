package ai.openclaw.app.ui.setup

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.onboarding.EnvironmentSetupStep
import ai.openclaw.app.onboarding.FirstRunFailure
import ai.openclaw.app.onboarding.FirstRunState
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.ComponentKey
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.capabilityConsumers
import ai.openclaw.app.ui.design.ClawTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class SetupProgressState {
  Complete,
  Active,
  Pending,
  Failed,
}

internal data class SetupProgressItem(
  val component: CapabilityComponent,
  val state: SetupProgressState,
  val detail: String,
)

internal fun setupProgressItems(state: FirstRunState): List<SetupProgressItem> {
  val running =
    (state as? FirstRunState.EnvironmentSetup)?.step as? EnvironmentSetupStep.Installing
  val failed = ((state as? FirstRunState.Failed)?.failure as? FirstRunFailure.Setup)
  val status = running?.status ?: failed?.status
  val selected = running?.resolvedComponents ?: failed?.status?.resolvedComponents ?: return emptyList()
  val currentIndex = status?.currentComponent?.let(selected::indexOf) ?: -1
  return selected.mapIndexed { index, component ->
    val progressState =
      when {
        currentIndex < 0 -> SetupProgressState.Pending
        index < currentIndex -> SetupProgressState.Complete
        index > currentIndex -> SetupProgressState.Pending
        status?.stage == SupervisorStatusStage.CapabilityFailed -> SetupProgressState.Failed
        status?.stage?.isReadyFor(component) == true -> SetupProgressState.Complete
        else -> SetupProgressState.Active
      }
    SetupProgressItem(
      component = component,
      state = progressState,
      detail =
        when (progressState) {
          SetupProgressState.Complete -> nativeString("Ready")
          SetupProgressState.Active -> status?.stage?.let(::setupStageLabel) ?: nativeString("Starting…")
          SetupProgressState.Pending -> nativeString("Waiting")
          SetupProgressState.Failed -> nativeString("Stopped")
        },
    )
  }
}

private fun SupervisorStatusStage.isReadyFor(component: CapabilityComponent): Boolean =
  if (component.key == ComponentKey.OpenClawRuntime) {
    this == SupervisorStatusStage.OpenClawReady
  } else {
    this == SupervisorStatusStage.ComponentReady
  }

internal fun setupComponentTitle(component: CapabilityComponent): String =
  when (component.key) {
    ComponentKey.OpenClawExecutionFoundation -> nativeString("OpenClaw environment")
    ComponentKey.GeneralNodeRuntime -> nativeString("Node ${component.version}")
    ComponentKey.ChromiumRuntime -> nativeString("Chromium")
    ComponentKey.AdbRuntime -> nativeString("Android device runtime")
    ComponentKey.VScreenRuntimeAssets -> nativeString("VScreen runtime")
    ComponentKey.OpenClawRuntime -> nativeString("OpenClaw")
    ComponentKey.AndroidBuildFoundation -> nativeString("Android build environment")
    ComponentKey.AndroidSdk -> nativeString("Android SDK")
    ComponentKey.AndroidPlatform -> nativeString("Android platform ${component.version}")
    ComponentKey.AndroidGradle -> nativeString("Gradle ${component.version}")
    ComponentKey.AndroidNdk -> nativeString("Android NDK ${component.version}")
    ComponentKey.AndroidCmake -> nativeString("CMake ${component.version}")
    ComponentKey.AndroidKotlinProfile -> nativeString("Kotlin Android development")
    ComponentKey.AndroidNativeProfile -> nativeString("Android Native development")
    ComponentKey.FlutterSdk -> nativeString("Flutter SDK")
    ComponentKey.FlutterProfile -> nativeString("Flutter Android development")
    ComponentKey.GodotEngine -> nativeString("Godot Engine ${component.version}")
    ComponentKey.GodotExportTemplates -> nativeString("Godot Android export templates")
    ComponentKey.GodotAndroidProfile -> nativeString("Godot Android development")
    ComponentKey.ReactNativeDistribution -> nativeString("React Native ${component.version}")
    ComponentKey.ReactNativeAndroidProfile -> nativeString("React Native Android development")
    ComponentKey.WebDistribution -> nativeString("Web dependencies ${component.version}")
    ComponentKey.WebProfile -> nativeString("Web development")
  }

internal fun setupComponentShortName(component: CapabilityComponent): String =
  when (component.key) {
    ComponentKey.OpenClawExecutionFoundation,
    ComponentKey.GeneralNodeRuntime,
    ComponentKey.ChromiumRuntime,
    ComponentKey.AdbRuntime,
    ComponentKey.VScreenRuntimeAssets,
    -> nativeString("OpenClaw environment")
    ComponentKey.OpenClawRuntime -> nativeString("OpenClaw")
    else ->
      when (val consumers = capabilityConsumers(component)) {
        setOf(DevelopmentCapability.AndroidKotlin) -> nativeString("Android development")
        setOf(DevelopmentCapability.AndroidNative) -> nativeString("Android Native")
        setOf(DevelopmentCapability.Flutter) -> nativeString("Flutter")
        setOf(DevelopmentCapability.GodotAndroid) -> nativeString("Godot Android")
        setOf(DevelopmentCapability.ReactNative) -> nativeString("React Native")
        setOf(DevelopmentCapability.WebDevelopment) -> nativeString("Web development")
        else -> nativeString("Shared developer tools")
      }
  }

@Composable
internal fun SetupProgressList(
  items: List<SetupProgressItem>,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    items.forEach { item ->
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ClawTheme.radii.row),
        color = ClawTheme.colors.surface,
        border = BorderStroke(1.dp, ClawTheme.colors.border),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          SetupProgressMark(item.state)
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = nativeString(setupComponentTitle(item.component)),
              style = ClawTheme.type.body,
              color = ClawTheme.colors.text,
            )
            Text(
              text = nativeString(item.detail),
              style = ClawTheme.type.caption,
              color = if (item.state == SetupProgressState.Failed) ClawTheme.colors.danger else ClawTheme.colors.textMuted,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SetupProgressMark(state: SetupProgressState) {
  if (state == SetupProgressState.Active) {
    CircularProgressIndicator(
      modifier = Modifier.size(16.dp),
      color = ClawTheme.colors.text,
      strokeWidth = 2.dp,
    )
    return
  }
  Box(
    modifier =
      Modifier
        .size(10.dp)
        .background(
          color =
            when (state) {
              SetupProgressState.Complete -> ClawTheme.colors.success
              SetupProgressState.Failed -> ClawTheme.colors.danger
              SetupProgressState.Pending -> ClawTheme.colors.textSubtle
              SetupProgressState.Active -> error("rendered above")
            },
          shape = CircleShape,
        ),
  )
}
