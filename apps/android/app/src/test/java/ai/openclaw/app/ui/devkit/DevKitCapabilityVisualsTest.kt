package ai.openclaw.app.ui.devkit

import ai.openclaw.app.devkit.DeveloperCapabilityId
import ai.openclaw.app.ui.design.DevelopmentBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevKitCapabilityVisualsTest {
  @Test
  fun officialSingleBrandCapabilitiesUseTheirBundledMarks() {
    val expected =
      mapOf(
        DeveloperCapabilityId.AndroidKotlin to DevelopmentBrand.Kotlin,
        DeveloperCapabilityId.Flutter to DevelopmentBrand.Flutter,
        DeveloperCapabilityId.GodotAndroid to DevelopmentBrand.Godot,
        DeveloperCapabilityId.ReactNative to DevelopmentBrand.ReactNative,
      )

    assertEquals(
      expected,
      expected.keys.associateWith { id ->
        (devKitCapabilityIconVisual(id) as DevKitCapabilityIconVisual.BrandMark).brand
      },
    )
  }

  @Test
  fun productAndCompositeCapabilitiesUseSemanticIcons() {
    val brandCapabilities =
      setOf(
        DeveloperCapabilityId.AndroidKotlin,
        DeveloperCapabilityId.Flutter,
        DeveloperCapabilityId.GodotAndroid,
        DeveloperCapabilityId.ReactNative,
      )

    DeveloperCapabilityId.entries
      .filterNot(brandCapabilities::contains)
      .forEach { id -> assertTrue(devKitCapabilityIconVisual(id) is DevKitCapabilityIconVisual.Semantic) }
  }
}
