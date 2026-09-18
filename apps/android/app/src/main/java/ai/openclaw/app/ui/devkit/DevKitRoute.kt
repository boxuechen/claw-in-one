package ai.openclaw.app.ui.devkit

import ai.openclaw.app.devkit.DevKitFeature
import ai.openclaw.app.devkit.DeveloperCapabilityAction
import ai.openclaw.app.devkit.DeveloperCapabilityGroup
import ai.openclaw.app.devkit.DeveloperCapabilityId
import ai.openclaw.app.devkit.DeveloperCapabilityStatus
import ai.openclaw.app.devkit.developerCapabilityDefinition
import ai.openclaw.app.ui.environment.RuntimeEnvironmentRoute
import ai.openclaw.app.ui.environment.RuntimeEnvironmentRouteActions
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Adapts authoritative owner features into the product-level DevKit destination. */
@Composable
internal fun DevKitRoute(
  feature: DevKitFeature,
  environmentActions: RuntimeEnvironmentRouteActions,
  destination: DevKitDestination,
  onDestinationChange: (DevKitDestination) -> Unit,
  onOpenVScreen: () -> Unit,
  onUseSkillInChat: (String) -> Unit,
  onBack: () -> Unit,
) {
  val catalog by feature.catalog.collectAsState(initial = feature.initialCatalog())
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var previousDevelopmentStatuses by
    remember(feature) { mutableStateOf(emptyMap<DeveloperCapabilityId, DeveloperCapabilityStatus>()) }
  BackHandler(onBack = onBack)
  LaunchedEffect(feature) { feature.refresh() }
  LaunchedEffect(catalog.capabilities) {
    val currentStatuses =
      catalog.capabilities
        .filter { it.group == DeveloperCapabilityGroup.Development }
        .associate { it.id to it.status }
    val newlyQualifiedWithoutSkill =
      currentStatuses.any { (id, status) ->
        status == DeveloperCapabilityStatus.NeedsRepair &&
          previousDevelopmentStatuses[id] != DeveloperCapabilityStatus.NeedsRepair
      }
    previousDevelopmentStatuses = currentStatuses
    if (newlyQualifiedWithoutSkill) feature.refreshSkills()
  }
  LaunchedEffect(feature, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      withContext(Dispatchers.IO) { feature.environment.observe() }
    }
  }

  when (destination) {
    DevKitDestination.Home ->
      DevKitHomeScreen(
        state = catalog,
        onOpen = { onDestinationChange(it.destination()) },
        onBack = onBack,
      )
    DevKitDestination.OpenClawEnvironment ->
      RuntimeEnvironmentRoute(
        feature = feature.environment,
        actions = environmentActions,
        onClose = onBack,
        sheet = false,
        onBack = onBack,
        showClose = false,
      )
    DevKitDestination.AndroidDeviceConnection ->
      AndroidDeviceCapabilityRoute(feature = feature.deviceConnection, onBack = onBack)
    DevKitDestination.AndroidUse ->
      AndroidUseCapabilityRoute(feature = feature.androidUse, onBack = onBack)
    DevKitDestination.VScreen ->
      DevKitCapabilityDetailScreen(
        capability = catalog.capability(DeveloperCapabilityId.VScreen),
        onPrimaryAction = onOpenVScreen,
        onBack = onBack,
      )
    DevKitDestination.AndroidKotlin,
    DevKitDestination.AndroidNative,
    DevKitDestination.Flutter,
    DevKitDestination.GodotAndroid,
    DevKitDestination.ReactNative,
    DevKitDestination.WebDevelopment,
    -> {
      val developmentCapability = checkNotNull(destination.developmentCapability())
      val capability = catalog.capability(developerCapabilityDefinition(developmentCapability).id)
      DevKitCapabilityDetailScreen(
        capability = capability,
        onPrimaryAction = {
          when {
            DeveloperCapabilityAction.UseInChat in capability.allowedActions ->
              onUseSkillInChat(checkNotNull(capability.skillReference))
            DeveloperCapabilityAction.Install in capability.allowedActions ->
              feature.development.install(developmentCapability)
            DeveloperCapabilityAction.Retry in capability.allowedActions ->
              feature.development.retry(developmentCapability)
            DeveloperCapabilityAction.Repair in capability.allowedActions ->
              feature.development.repair(developmentCapability)
          }
        },
        onBack = onBack,
      )
    }
  }
}

private fun ai.openclaw.app.devkit.DeveloperCapabilityCatalogState.capability(id: DeveloperCapabilityId) = capabilities.single { it.id == id }
