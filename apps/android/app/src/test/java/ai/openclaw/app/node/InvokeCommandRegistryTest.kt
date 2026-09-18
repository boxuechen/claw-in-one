package ai.openclaw.app.node

import ai.openclaw.app.androiduse.ANDROID_USE_NODE_COMMAND
import ai.openclaw.app.protocol.OpenClawCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvokeCommandRegistryTest {
  @Test
  fun unavailableAndroidUseAdvertisesNoNodeSurface() {
    assertTrue(InvokeCommandRegistry.advertisedCapabilities(androidUseAvailable = false).isEmpty())
    assertTrue(InvokeCommandRegistry.advertisedCommands(androidUseAvailable = false).isEmpty())
  }

  @Test
  fun availableAndroidUseIsTheEntireNodeSurface() {
    assertEquals(
      listOf(OpenClawCapability.MobileUI.rawValue, "clawAndroidUseConsentV1"),
      InvokeCommandRegistry.advertisedCapabilities(androidUseAvailable = true),
    )
    assertEquals(
      listOf(ANDROID_USE_NODE_COMMAND),
      InvokeCommandRegistry.advertisedCommands(androidUseAvailable = true),
    )
    assertEquals(listOf(ANDROID_USE_NODE_COMMAND), InvokeCommandRegistry.all)
  }

  @Test
  fun containsRejectsEveryGenericCommand() {
    assertTrue(InvokeCommandRegistry.contains(ANDROID_USE_NODE_COMMAND))
    assertFalse(InvokeCommandRegistry.contains("device.status"))
    assertFalse(InvokeCommandRegistry.contains("debug.logs"))
  }
}
