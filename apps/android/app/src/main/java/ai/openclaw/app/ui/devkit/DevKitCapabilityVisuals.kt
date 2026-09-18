package ai.openclaw.app.ui.devkit

import ai.openclaw.app.devkit.DeveloperCapabilityId
import ai.openclaw.app.ui.design.ClawTheme
import ai.openclaw.app.ui.design.DevelopmentBrand
import ai.openclaw.app.ui.design.DevelopmentBrandIcon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal sealed interface DevKitCapabilityIconVisual {
  data class BrandMark(
    val brand: DevelopmentBrand,
    val size: Dp,
  ) : DevKitCapabilityIconVisual

  data class Semantic(
    val imageVector: ImageVector,
  ) : DevKitCapabilityIconVisual
}

internal fun devKitCapabilityIconVisual(id: DeveloperCapabilityId): DevKitCapabilityIconVisual =
  when (id) {
    DeveloperCapabilityId.OpenClawEnvironment -> DevKitCapabilityIconVisual.Semantic(Icons.Default.Computer)
    DeveloperCapabilityId.AndroidDeviceConnection -> DevKitCapabilityIconVisual.Semantic(Icons.Default.PhoneAndroid)
    DeveloperCapabilityId.VScreen -> DevKitCapabilityIconVisual.Semantic(Icons.AutoMirrored.Filled.ScreenShare)
    DeveloperCapabilityId.AndroidUse -> DevKitCapabilityIconVisual.Semantic(Icons.Default.TouchApp)
    DeveloperCapabilityId.AndroidKotlin ->
      DevKitCapabilityIconVisual.BrandMark(DevelopmentBrand.Kotlin, size = 22.dp)
    DeveloperCapabilityId.AndroidNative -> DevKitCapabilityIconVisual.Semantic(Icons.Default.Memory)
    DeveloperCapabilityId.Flutter ->
      DevKitCapabilityIconVisual.BrandMark(DevelopmentBrand.Flutter, size = 22.dp)
    DeveloperCapabilityId.GodotAndroid ->
      DevKitCapabilityIconVisual.BrandMark(DevelopmentBrand.Godot, size = 24.dp)
    DeveloperCapabilityId.ReactNative ->
      DevKitCapabilityIconVisual.BrandMark(DevelopmentBrand.ReactNative, size = 26.dp)
    DeveloperCapabilityId.WebDevelopment -> DevKitCapabilityIconVisual.Semantic(Icons.Default.Language)
  }

@Composable
internal fun DevKitCapabilityIcon(
  id: DeveloperCapabilityId,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.size(28.dp).testTag("devkit-icon-${id.name}"),
    contentAlignment = Alignment.Center,
  ) {
    when (val visual = devKitCapabilityIconVisual(id)) {
      is DevKitCapabilityIconVisual.BrandMark ->
        DevelopmentBrandIcon(
          brand = visual.brand,
          size = visual.size,
        )
      is DevKitCapabilityIconVisual.Semantic ->
        Icon(
          imageVector = visual.imageVector,
          contentDescription = null,
          tint = ClawTheme.colors.textMuted,
          modifier = Modifier.size(ClawTheme.sizes.standardIcon),
        )
    }
  }
}
