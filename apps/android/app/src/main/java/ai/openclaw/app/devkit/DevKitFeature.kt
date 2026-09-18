package ai.openclaw.app.devkit

import ai.openclaw.app.androiddevice.AndroidDeviceConnectionState
import ai.openclaw.app.runtime.RuntimeFeature
import ai.openclaw.app.runtime.RuntimeState
import ai.openclaw.app.skill.SkillState
import ai.openclaw.app.skill.eligibleSkillReferences
import ai.openclaw.app.vscreen.VScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

internal class AndroidDeviceCapabilityFeature(
  val state: StateFlow<AndroidDeviceConnectionState>,
  val actions: AndroidDeviceCapabilityActions,
)

internal data class AndroidDeviceCapabilityActions(
  val refresh: () -> Unit,
  val reconnect: (String?) -> Unit,
  val forget: () -> Unit,
  val dismissNotice: () -> Unit,
)

internal class AndroidUseCapabilityFeature(
  val state: StateFlow<AndroidUseCapabilityState>,
  val actions: AndroidUseCapabilityActions,
)

internal data class AndroidUseCapabilityState(
  val enabled: Boolean = false,
  val serviceAvailable: Boolean = false,
  val preventSleep: Boolean = false,
  val available: Boolean = false,
  val authorization: AndroidUseAuthorizationStatus = AndroidUseAuthorizationStatus.Checking,
  val authorizing: Boolean = false,
  val notice: AndroidUseAuthorizationNotice? = null,
)

internal data class AndroidUseSkillRefreshSignal(
  val capabilityReady: Boolean,
  val gatewayConnected: Boolean,
  val inventoryLoaded: Boolean,
  val skillEligible: Boolean,
) {
  val shouldRefresh: Boolean
    get() = capabilityReady && gatewayConnected && inventoryLoaded && !skillEligible
}

internal fun androidUseSkillRefreshSignal(
  androidUse: AndroidUseCapabilityState,
  skills: SkillState,
): AndroidUseSkillRefreshSignal =
  AndroidUseSkillRefreshSignal(
    capabilityReady =
      androidUse.enabled &&
        androidUse.serviceAvailable &&
        androidUse.available &&
        androidUse.authorization == AndroidUseAuthorizationStatus.Approved &&
        !androidUse.authorizing,
    gatewayConnected = skills.connected,
    inventoryLoaded = skills.loaded,
    skillEligible = ANDROID_USE_SKILL_REFERENCE in skills.summary.skills.eligibleSkillReferences(),
  )

internal enum class AndroidUseAuthorizationStatus {
  Checking,
  Approved,
  ApprovalRequired,
  ReapprovalRequired,
  Unapproved,
  Unsupported,
}

internal enum class AndroidUseAuthorizationNotice {
  ApprovalFailed,
}

internal data class AndroidUseCapabilityActions(
  val setPreventSleep: (Boolean) -> Unit,
  val setEnabled: (Boolean) -> Unit,
  val refreshAuthorization: () -> Unit,
  val authorizeCurrentPhone: () -> Unit,
  val dismissNotice: () -> Unit,
)

internal class DevKitFeature(
  val catalog: Flow<DeveloperCapabilityCatalogState>,
  val initialCatalog: () -> DeveloperCapabilityCatalogState,
  val environment: RuntimeFeature,
  val deviceConnection: AndroidDeviceCapabilityFeature,
  val androidUse: AndroidUseCapabilityFeature,
  val development: DevelopmentCapabilityActions,
  val refresh: () -> Unit,
  val refreshSkills: () -> Unit,
)

internal data class DevelopmentCapabilityActions(
  val install: (ai.openclaw.app.supervisor.DevelopmentCapability) -> Unit,
  val retry: (ai.openclaw.app.supervisor.DevelopmentCapability) -> Unit,
  val repair: (ai.openclaw.app.supervisor.DevelopmentCapability) -> Unit,
)

internal fun developerCapabilityCatalog(
  environment: StateFlow<RuntimeState>,
  device: StateFlow<AndroidDeviceConnectionState>,
  vscreen: StateFlow<VScreenState>,
  androidUse: StateFlow<AndroidUseCapabilityState>,
  skills: StateFlow<SkillState>,
): Flow<DeveloperCapabilityCatalogState> =
  combine(environment, device, vscreen, androidUse, skills) { environmentState, deviceState, vscreenState, androidUseState, skillState ->
    resolveDeveloperCapabilityCatalog(
      DeveloperCapabilitySources(
        environment = environmentState,
        device = deviceState,
        vscreen = vscreenState,
        androidUse = androidUseState,
        skills = skillState.toDeveloperSkillInventory(),
      ),
    )
  }

internal fun developerCapabilityCatalogSnapshot(
  environment: StateFlow<RuntimeState>,
  device: StateFlow<AndroidDeviceConnectionState>,
  vscreen: StateFlow<VScreenState>,
  androidUse: StateFlow<AndroidUseCapabilityState>,
  skills: StateFlow<SkillState>,
): DeveloperCapabilityCatalogState =
  resolveDeveloperCapabilityCatalog(
    DeveloperCapabilitySources(
      environment.value,
      device.value,
      vscreen.value,
      androidUse.value,
      skills.value.toDeveloperSkillInventory(),
    ),
  )

private fun SkillState.toDeveloperSkillInventory(): DeveloperSkillInventory =
  DeveloperSkillInventory(
    connected = connected,
    loaded = loaded,
    eligibleReferences = summary.skills.eligibleSkillReferences(),
  )
