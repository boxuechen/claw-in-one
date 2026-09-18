package ai.openclaw.app.devkit

import ai.openclaw.app.androiddevice.AndroidDeviceAvailability
import ai.openclaw.app.androiddevice.AndroidDeviceConnectionState
import ai.openclaw.app.androiddevice.AndroidDeviceStatus
import ai.openclaw.app.runtime.LocalServiceState
import ai.openclaw.app.runtime.RuntimeState
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.activeCapabilityConsumers
import ai.openclaw.app.vscreen.VScreenRuntime
import ai.openclaw.app.vscreen.VScreenState

internal enum class DeveloperCapabilityId {
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

internal enum class DeveloperCapabilityGroup {
  Environment,
  Device,
  Development,
}

internal enum class DeveloperCapabilityProvisioning {
  RequiredEnvironment,
  BuiltIn,
  OptionalExtension,
}

internal enum class DeveloperCapabilityActivationPolicy {
  AlwaysOn,
  UserControlled,
  OnDemand,
}

internal enum class DeveloperCapabilityStatus {
  Checking,
  NotInstalled,
  Downloading,
  Installing,
  Verifying,
  Ready,
  Disabled,
  NeedsPermission,
  NeedsRepair,
  Failed,
}

internal enum class DeveloperCapabilityAction {
  Refresh,
  Install,
  Retry,
  Reconnect,
  Repair,
  Open,
  Enable,
  Disable,
  UseInChat,
}

internal data class DeveloperCapabilityProgress(
  val completedBytes: Long,
  val totalBytes: Long,
) {
  init {
    require(completedBytes >= 0)
    require(totalBytes > 0)
    require(completedBytes <= totalBytes)
  }
}

internal data class DeveloperCapability(
  val id: DeveloperCapabilityId,
  val group: DeveloperCapabilityGroup,
  val provisioning: DeveloperCapabilityProvisioning,
  val activationPolicy: DeveloperCapabilityActivationPolicy,
  val status: DeveloperCapabilityStatus,
  val allowedActions: Set<DeveloperCapabilityAction>,
  val skillReference: String? = null,
  val detail: String? = null,
  val progress: DeveloperCapabilityProgress? = null,
) {
  init {
    if (provisioning != DeveloperCapabilityProvisioning.OptionalExtension) {
      require(status != DeveloperCapabilityStatus.NotInstalled)
    }
    if (DeveloperCapabilityAction.Install in allowedActions) {
      require(provisioning == DeveloperCapabilityProvisioning.OptionalExtension)
    }
    if (DeveloperCapabilityAction.UseInChat in allowedActions) {
      require(provisioning == DeveloperCapabilityProvisioning.OptionalExtension)
      require(status == DeveloperCapabilityStatus.Ready)
      require(!skillReference.isNullOrBlank())
    }
    if (activationPolicy != DeveloperCapabilityActivationPolicy.UserControlled) {
      require(DeveloperCapabilityAction.Enable !in allowedActions)
      require(DeveloperCapabilityAction.Disable !in allowedActions)
      require(status != DeveloperCapabilityStatus.Disabled)
    }
    require(progress == null || status in progressStatuses)
  }
}

internal data class DeveloperCapabilityCatalogState(
  val capabilities: List<DeveloperCapability>,
) {
  init {
    require(capabilities.map(DeveloperCapability::id) == DeveloperCapabilityId.entries)
  }

  val ready: Boolean
    get() =
      capabilities
        .filter { it.provisioning == DeveloperCapabilityProvisioning.RequiredEnvironment }
        .all { it.status == DeveloperCapabilityStatus.Ready }

  val needsAttention: Boolean
    get() =
      capabilities.any { capability ->
        capability.status in
          setOf(
            DeveloperCapabilityStatus.NeedsPermission,
            DeveloperCapabilityStatus.NeedsRepair,
            DeveloperCapabilityStatus.Failed,
          )
      }
}

internal data class DeveloperCapabilitySources(
  val environment: RuntimeState,
  val device: AndroidDeviceConnectionState,
  val vscreen: VScreenState,
  val androidUse: AndroidUseCapabilityState,
  val skills: DeveloperSkillInventory = DeveloperSkillInventory(),
)

internal data class DeveloperSkillInventory(
  val connected: Boolean = false,
  val loaded: Boolean = false,
  val eligibleReferences: Set<String> = emptySet(),
)

internal fun resolveDeveloperCapabilityCatalog(
  sources: DeveloperCapabilitySources,
): DeveloperCapabilityCatalogState =
  DeveloperCapabilityCatalogState(
    capabilities =
      listOf(
        sources.environment.toOpenClawEnvironmentCapability(),
        sources.device.toAndroidDeviceCapability(),
        sources.toVScreenCapability(),
        sources.androidUse.toDeveloperCapability(),
        sources.environment.toAndroidKotlinCapability(sources.skills),
        sources.environment.toAndroidNativeCapability(sources.skills),
        sources.environment.toFlutterCapability(sources.skills),
        sources.environment.toGodotAndroidCapability(sources.skills),
        sources.environment.toReactNativeCapability(sources.skills),
        sources.environment.toWebDevelopmentCapability(sources.skills),
      ),
  )

private fun RuntimeState.toOpenClawEnvironmentCapability(): DeveloperCapability {
  val lastVerifiedReady =
    gatewayConnected &&
      localService in
      setOf(
        LocalServiceState.Checking,
        LocalServiceState.Responding,
        LocalServiceState.LastKnown,
      )
  val status =
    when {
      lastVerifiedReady -> DeveloperCapabilityStatus.Ready
      operationInProgress -> DeveloperCapabilityStatus.Checking
      localService in setOf(LocalServiceState.Unchecked, LocalServiceState.Checking) -> DeveloperCapabilityStatus.Checking
      else -> DeveloperCapabilityStatus.NeedsRepair
    }
  return DeveloperCapability(
    id = DeveloperCapabilityId.OpenClawEnvironment,
    group = DeveloperCapabilityGroup.Environment,
    provisioning = DeveloperCapabilityProvisioning.RequiredEnvironment,
    activationPolicy = DeveloperCapabilityActivationPolicy.AlwaysOn,
    status = status,
    allowedActions =
      when (status) {
        DeveloperCapabilityStatus.Ready -> setOf(DeveloperCapabilityAction.Refresh)
        DeveloperCapabilityStatus.Checking -> emptySet()
        else -> setOf(DeveloperCapabilityAction.Repair, DeveloperCapabilityAction.Refresh)
      },
    detail = gatewayVersion,
  )
}

private fun AndroidDeviceConnectionState.toAndroidDeviceCapability(): DeveloperCapability {
  val status =
    when {
      refreshing || operation != null -> DeveloperCapabilityStatus.Checking
      availability == AndroidDeviceAvailability.GatewayOffline -> DeveloperCapabilityStatus.NeedsRepair
      availability == AndroidDeviceAvailability.Unsupported -> DeveloperCapabilityStatus.NeedsRepair
      snapshot == null -> DeveloperCapabilityStatus.Checking
      snapshot.status == AndroidDeviceStatus.Ready -> DeveloperCapabilityStatus.Ready
      snapshot.status in setOf(AndroidDeviceStatus.SetupRequired, AndroidDeviceStatus.Revoked) ->
        DeveloperCapabilityStatus.NeedsPermission
      snapshot.status == AndroidDeviceStatus.Offline -> DeveloperCapabilityStatus.NeedsRepair
      snapshot.status == AndroidDeviceStatus.Unavailable -> DeveloperCapabilityStatus.NeedsRepair
      else -> DeveloperCapabilityStatus.Checking
    }
  val actions =
    when {
      status == DeveloperCapabilityStatus.Ready -> setOf(DeveloperCapabilityAction.Refresh)
      status == DeveloperCapabilityStatus.NeedsPermission -> setOf(DeveloperCapabilityAction.Repair, DeveloperCapabilityAction.Refresh)
      snapshot?.status == AndroidDeviceStatus.Offline ->
        setOf(DeveloperCapabilityAction.Reconnect, DeveloperCapabilityAction.Refresh)
      status == DeveloperCapabilityStatus.NeedsRepair -> setOf(DeveloperCapabilityAction.Repair, DeveloperCapabilityAction.Refresh)
      else -> emptySet()
    }
  return DeveloperCapability(
    id = DeveloperCapabilityId.AndroidDeviceConnection,
    group = DeveloperCapabilityGroup.Device,
    provisioning = DeveloperCapabilityProvisioning.BuiltIn,
    activationPolicy = DeveloperCapabilityActivationPolicy.AlwaysOn,
    status = status,
    allowedActions = actions,
    detail = snapshot?.target?.model,
  )
}

private fun DeveloperCapabilitySources.toVScreenCapability(): DeveloperCapability {
  val status =
    when {
      !environment.gatewayConnected -> DeveloperCapabilityStatus.NeedsRepair
      vscreen.runtime in setOf(VScreenRuntime.Starting, VScreenRuntime.Closing, VScreenRuntime.Reconnecting) ->
        DeveloperCapabilityStatus.Checking
      vscreen.runtime == VScreenRuntime.Ready -> DeveloperCapabilityStatus.Ready
      !vscreen.message.isNullOrBlank() -> DeveloperCapabilityStatus.NeedsRepair
      else -> DeveloperCapabilityStatus.Ready
    }
  return DeveloperCapability(
    id = DeveloperCapabilityId.VScreen,
    group = DeveloperCapabilityGroup.Device,
    provisioning = DeveloperCapabilityProvisioning.BuiltIn,
    activationPolicy = DeveloperCapabilityActivationPolicy.OnDemand,
    status = status,
    allowedActions =
      buildSet {
        if (environment.gatewayConnected) add(DeveloperCapabilityAction.Open)
        if (status == DeveloperCapabilityStatus.NeedsRepair) add(DeveloperCapabilityAction.Repair)
      },
  )
}

private fun AndroidUseCapabilityState.toDeveloperCapability(): DeveloperCapability {
  val status =
    when {
      !enabled -> DeveloperCapabilityStatus.Disabled
      authorizing || authorization == AndroidUseAuthorizationStatus.Checking -> DeveloperCapabilityStatus.Checking
      authorization == AndroidUseAuthorizationStatus.Unsupported -> DeveloperCapabilityStatus.NeedsRepair
      authorization != AndroidUseAuthorizationStatus.Approved || !serviceAvailable || !available ->
        DeveloperCapabilityStatus.NeedsPermission
      else -> DeveloperCapabilityStatus.Ready
    }
  return DeveloperCapability(
    id = DeveloperCapabilityId.AndroidUse,
    group = DeveloperCapabilityGroup.Device,
    provisioning = DeveloperCapabilityProvisioning.BuiltIn,
    activationPolicy = DeveloperCapabilityActivationPolicy.UserControlled,
    status = status,
    allowedActions =
      buildSet {
        add(if (enabled) DeveloperCapabilityAction.Disable else DeveloperCapabilityAction.Enable)
        if (enabled && status in setOf(DeveloperCapabilityStatus.NeedsPermission, DeveloperCapabilityStatus.NeedsRepair)) {
          add(DeveloperCapabilityAction.Repair)
        }
      },
  )
}

private fun RuntimeState.toAndroidKotlinCapability(skills: DeveloperSkillInventory): DeveloperCapability =
  toOptionalDevelopmentCapability(
    definition = developerCapabilityDefinition(DevelopmentCapability.AndroidKotlin),
    skills = skills,
    readyDetail = "Kotlin · Compose · Android SDK 37",
  )

private fun RuntimeState.toAndroidNativeCapability(skills: DeveloperSkillInventory): DeveloperCapability =
  toOptionalDevelopmentCapability(
    definition = developerCapabilityDefinition(DevelopmentCapability.AndroidNative),
    skills = skills,
    readyDetail = "NDK 29 · CMake 3.22 · ARM64 Vulkan",
  )

private fun RuntimeState.toFlutterCapability(skills: DeveloperSkillInventory): DeveloperCapability =
  toOptionalDevelopmentCapability(
    definition = developerCapabilityDefinition(DevelopmentCapability.Flutter),
    skills = skills,
    readyDetail = "Flutter 3.47.4 · Dart 3.13.3",
  )

private fun RuntimeState.toGodotAndroidCapability(skills: DeveloperSkillInventory): DeveloperCapability =
  toOptionalDevelopmentCapability(
    definition = developerCapabilityDefinition(DevelopmentCapability.GodotAndroid),
    skills = skills,
    readyDetail = "Godot 4.7.2 · Android ARM64",
  )

private fun RuntimeState.toReactNativeCapability(skills: DeveloperSkillInventory): DeveloperCapability =
  toOptionalDevelopmentCapability(
    definition = developerCapabilityDefinition(DevelopmentCapability.ReactNative),
    skills = skills,
    readyDetail = "React Native 0.87.1 · Node 22 · Hermes · Android ARM64",
  )

private fun RuntimeState.toWebDevelopmentCapability(skills: DeveloperSkillInventory): DeveloperCapability =
  toOptionalDevelopmentCapability(
    definition = developerCapabilityDefinition(DevelopmentCapability.WebDevelopment),
    skills = skills,
    readyDetail = "React 19 · TypeScript 7 · Vite 8 · Node 22",
  )

private fun RuntimeState.toOptionalDevelopmentCapability(
  definition: DeveloperCapabilityDefinition,
  skills: DeveloperSkillInventory,
  readyDetail: String,
): DeveloperCapability {
  val capability = definition.developmentCapability
  val setup = setup
  val stage = setup?.stage
  val selected = setup?.selectedCapabilities.orEmpty()
  val profileReady = capability in setup?.readyCapabilities.orEmpty()
  val status =
    when {
      setup == null && localService in setOf(LocalServiceState.Unchecked, LocalServiceState.Checking) ->
        DeveloperCapabilityStatus.Checking
      setup == null -> DeveloperCapabilityStatus.NotInstalled
      profileReady && (!skills.connected || !skills.loaded) -> DeveloperCapabilityStatus.Checking
      profileReady && definition.skillReference !in skills.eligibleReferences -> DeveloperCapabilityStatus.NeedsRepair
      profileReady -> DeveloperCapabilityStatus.Ready
      stage == SupervisorStatusStage.DownloadingComponent && setup.currentComponent?.let { capability in activeCapabilityConsumers(it, selected) } == true ->
        DeveloperCapabilityStatus.Downloading
      stage == SupervisorStatusStage.VerifyingComponent && setup.currentComponent?.let { capability in activeCapabilityConsumers(it, selected) } == true ->
        DeveloperCapabilityStatus.Verifying
      stage == SupervisorStatusStage.InstallingComponent && setup.currentComponent?.let { capability in activeCapabilityConsumers(it, selected) } == true ->
        DeveloperCapabilityStatus.Installing
      stage == SupervisorStatusStage.CapabilityFailed && setup.currentComponent?.let { capability in activeCapabilityConsumers(it, selected) } == true ->
        DeveloperCapabilityStatus.Failed
      capability !in selected -> DeveloperCapabilityStatus.NotInstalled
      stage == SupervisorStatusStage.CapabilitiesReady -> DeveloperCapabilityStatus.NotInstalled
      operationInProgress -> DeveloperCapabilityStatus.Installing
      else -> DeveloperCapabilityStatus.NeedsRepair
    }
  val progress =
    if (
      status in progressStatuses &&
      setup?.completedBytes != null &&
      setup.totalBytes != null &&
      setup.totalBytes > 0 &&
      setup.completedBytes in 0..setup.totalBytes
    ) {
      DeveloperCapabilityProgress(setup.completedBytes, setup.totalBytes)
    } else {
      null
    }
  return DeveloperCapability(
    id = definition.id,
    group = DeveloperCapabilityGroup.Development,
    provisioning = DeveloperCapabilityProvisioning.OptionalExtension,
    activationPolicy = DeveloperCapabilityActivationPolicy.OnDemand,
    status = status,
    allowedActions =
      when (status) {
        DeveloperCapabilityStatus.NotInstalled -> setOf(DeveloperCapabilityAction.Install)
        DeveloperCapabilityStatus.Failed -> setOf(DeveloperCapabilityAction.Retry)
        DeveloperCapabilityStatus.NeedsRepair -> setOf(DeveloperCapabilityAction.Repair)
        DeveloperCapabilityStatus.Ready -> setOf(DeveloperCapabilityAction.UseInChat)
        else -> emptySet()
      },
    skillReference = definition.skillReference,
    detail = if (status == DeveloperCapabilityStatus.Ready) readyDetail else null,
    progress = progress,
  )
}

private val progressStatuses =
  setOf(
    DeveloperCapabilityStatus.Downloading,
    DeveloperCapabilityStatus.Installing,
    DeveloperCapabilityStatus.Verifying,
  )
