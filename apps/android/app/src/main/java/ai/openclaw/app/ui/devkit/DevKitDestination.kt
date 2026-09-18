package ai.openclaw.app.ui.devkit

import ai.openclaw.app.devkit.DeveloperCapabilityId
import ai.openclaw.app.supervisor.DevelopmentCapability

internal enum class DevKitDestination {
  Home,
  OpenClawEnvironment,
  AndroidDeviceConnection,
  VScreen,
  AndroidUse,
  AndroidKotlin,
  AndroidNative,
  Flutter,
  GodotAndroid,
  ReactNative,
  WebDevelopment,
}

internal fun DeveloperCapabilityId.destination(): DevKitDestination =
  when (this) {
    DeveloperCapabilityId.OpenClawEnvironment -> DevKitDestination.OpenClawEnvironment
    DeveloperCapabilityId.AndroidDeviceConnection -> DevKitDestination.AndroidDeviceConnection
    DeveloperCapabilityId.VScreen -> DevKitDestination.VScreen
    DeveloperCapabilityId.AndroidUse -> DevKitDestination.AndroidUse
    DeveloperCapabilityId.AndroidKotlin -> DevKitDestination.AndroidKotlin
    DeveloperCapabilityId.AndroidNative -> DevKitDestination.AndroidNative
    DeveloperCapabilityId.Flutter -> DevKitDestination.Flutter
    DeveloperCapabilityId.GodotAndroid -> DevKitDestination.GodotAndroid
    DeveloperCapabilityId.ReactNative -> DevKitDestination.ReactNative
    DeveloperCapabilityId.WebDevelopment -> DevKitDestination.WebDevelopment
  }

internal fun DevKitDestination.developmentCapability(): DevelopmentCapability? =
  when (this) {
    DevKitDestination.AndroidKotlin -> DevelopmentCapability.AndroidKotlin
    DevKitDestination.AndroidNative -> DevelopmentCapability.AndroidNative
    DevKitDestination.Flutter -> DevelopmentCapability.Flutter
    DevKitDestination.GodotAndroid -> DevelopmentCapability.GodotAndroid
    DevKitDestination.ReactNative -> DevelopmentCapability.ReactNative
    DevKitDestination.WebDevelopment -> DevelopmentCapability.WebDevelopment
    DevKitDestination.Home,
    DevKitDestination.OpenClawEnvironment,
    DevKitDestination.AndroidDeviceConnection,
    DevKitDestination.VScreen,
    DevKitDestination.AndroidUse,
    -> null
  }
