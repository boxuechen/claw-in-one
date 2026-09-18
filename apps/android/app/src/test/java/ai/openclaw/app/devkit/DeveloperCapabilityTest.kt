package ai.openclaw.app.devkit

import ai.openclaw.app.GatewaySkillSummary
import ai.openclaw.app.GatewaySkillsSummary
import ai.openclaw.app.androiddevice.AndroidDeviceAvailability
import ai.openclaw.app.androiddevice.AndroidDeviceConnectionState
import ai.openclaw.app.androiddevice.AndroidDeviceSnapshot
import ai.openclaw.app.androiddevice.AndroidDeviceStatus
import ai.openclaw.app.androiddevice.AndroidDeviceTarget
import ai.openclaw.app.runtime.LocalServiceState
import ai.openclaw.app.runtime.RuntimeSetup
import ai.openclaw.app.runtime.RuntimeState
import ai.openclaw.app.skill.SkillState
import ai.openclaw.app.supervisor.CapabilityComponent
import ai.openclaw.app.supervisor.DevelopmentCapability
import ai.openclaw.app.supervisor.SupervisorStatusStage
import ai.openclaw.app.supervisor.resolveComponents
import ai.openclaw.app.vscreen.VScreenRuntime
import ai.openclaw.app.vscreen.VScreenState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeveloperCapabilityTest {
  @Test
  fun catalogPublishesOnlyTheFixedImplementedCapabilitiesInProductOrder() {
    val catalog = resolveDeveloperCapabilityCatalog(readySources())

    assertEquals(DeveloperCapabilityId.entries.toList(), catalog.capabilities.map(DeveloperCapability::id))
    assertEquals(
      listOf(
        DeveloperCapabilityGroup.Environment,
        DeveloperCapabilityGroup.Device,
        DeveloperCapabilityGroup.Device,
        DeveloperCapabilityGroup.Device,
        DeveloperCapabilityGroup.Development,
        DeveloperCapabilityGroup.Development,
        DeveloperCapabilityGroup.Development,
        DeveloperCapabilityGroup.Development,
        DeveloperCapabilityGroup.Development,
        DeveloperCapabilityGroup.Development,
      ),
      catalog.capabilities.map(DeveloperCapability::group),
    )
    assertTrue(catalog.ready)
    assertFalse(catalog.needsAttention)
  }

  @Test
  fun developmentProfilesAreOptionalExtensionsWhileBuiltInsAreNotInstallable() {
    val catalog = resolveDeveloperCapabilityCatalog(readySources())

    catalog.capabilities
      .filter { it.group == DeveloperCapabilityGroup.Development }
      .forEach { capability ->
        assertEquals(DeveloperCapabilityProvisioning.OptionalExtension, capability.provisioning)
        assertEquals(DeveloperCapabilityStatus.NotInstalled, capability.status)
        assertEquals(setOf(DeveloperCapabilityAction.Install), capability.allowedActions)
      }
    catalog.capabilities
      .filter { it.provisioning == DeveloperCapabilityProvisioning.BuiltIn }
      .forEach { capability -> assertFalse(DeveloperCapabilityAction.Install in capability.allowedActions) }
  }

  @Test
  fun disabledAndroidUseDoesNotBlockDeveloperReadyAndOffersOnlyEnable() {
    val capability = resolveDeveloperCapabilityCatalog(readySources()).capability(DeveloperCapabilityId.AndroidUse)

    assertEquals(DeveloperCapabilityStatus.Disabled, capability.status)
    assertEquals(setOf(DeveloperCapabilityAction.Enable), capability.allowedActions)
    assertTrue(resolveDeveloperCapabilityCatalog(readySources()).ready)
  }

  @Test
  fun backgroundProbeKeepsTheLastVerifiedHealthyEnvironmentReady() {
    val sources = readySources()
    val catalog =
      resolveDeveloperCapabilityCatalog(
        sources.copy(
          environment =
            sources.environment.copy(
              localService = LocalServiceState.LastKnown,
              operationInProgress = true,
            ),
        ),
      )

    assertEquals(
      DeveloperCapabilityStatus.Ready,
      catalog.capability(DeveloperCapabilityId.OpenClawEnvironment).status,
    )
    assertTrue(catalog.ready)
  }

  @Test
  fun enabledAndroidUseNeedsPermissionUntilAuthorizationAndServiceAreReady() {
    val needsPermission =
      resolveDeveloperCapabilityCatalog(
        readySources(
          androidUse =
            AndroidUseCapabilityState(
              enabled = true,
              authorization = AndroidUseAuthorizationStatus.ApprovalRequired,
            ),
        ),
      ).capability(DeveloperCapabilityId.AndroidUse)
    assertEquals(DeveloperCapabilityStatus.NeedsPermission, needsPermission.status)
    assertEquals(
      setOf(DeveloperCapabilityAction.Disable, DeveloperCapabilityAction.Repair),
      needsPermission.allowedActions,
    )

    val ready =
      resolveDeveloperCapabilityCatalog(
        readySources(
          androidUse =
            AndroidUseCapabilityState(
              enabled = true,
              serviceAvailable = true,
              available = true,
              authorization = AndroidUseAuthorizationStatus.Approved,
            ),
        ),
      ).capability(DeveloperCapabilityId.AndroidUse)
    assertEquals(DeveloperCapabilityStatus.Ready, ready.status)
    assertEquals(setOf(DeveloperCapabilityAction.Disable), ready.allowedActions)
  }

  @Test
  fun readyAndroidUseRefreshesTheStandardSkillInventoryOnlyUntilItsSkillIsEligible() {
    val androidUse =
      AndroidUseCapabilityState(
        enabled = true,
        serviceAvailable = true,
        available = true,
        authorization = AndroidUseAuthorizationStatus.Approved,
      )

    assertFalse(
      androidUseSkillRefreshSignal(
        androidUse,
        SkillState(connected = true, loaded = false),
      ).shouldRefresh,
    )
    assertTrue(
      androidUseSkillRefreshSignal(
        androidUse,
        SkillState(connected = true, loaded = true),
      ).shouldRefresh,
    )
    assertFalse(
      androidUseSkillRefreshSignal(
        androidUse,
        readySkillState("android-use"),
      ).shouldRefresh,
    )
  }

  @Test
  fun androidUseSkillRefreshWaitsForCapabilityAuthorityAndGatewayConnection() {
    val readySkills = readySkillState("android-use")

    assertFalse(
      androidUseSkillRefreshSignal(
        AndroidUseCapabilityState(enabled = true, authorization = AndroidUseAuthorizationStatus.Approved),
        readySkills,
      ).shouldRefresh,
    )
    assertFalse(
      androidUseSkillRefreshSignal(
        AndroidUseCapabilityState(
          enabled = true,
          serviceAvailable = true,
          available = true,
          authorization = AndroidUseAuthorizationStatus.Approved,
        ),
        SkillState(connected = false, loaded = false),
      ).shouldRefresh,
    )
  }

  @Test
  fun deviceOfflineAndVScreenFailureRemainSeparateRecoveryFacts() {
    val sources =
      readySources().copy(
        device =
          AndroidDeviceConnectionState(
            availability = AndroidDeviceAvailability.Available,
            snapshot = readyDeviceSnapshot().copy(status = AndroidDeviceStatus.Offline, connected = false),
          ),
        vscreen = VScreenState(runtime = VScreenRuntime.Unavailable, message = "producer unavailable"),
      )
    val catalog = resolveDeveloperCapabilityCatalog(sources)

    assertEquals(
      setOf(DeveloperCapabilityAction.Reconnect, DeveloperCapabilityAction.Refresh),
      catalog.capability(DeveloperCapabilityId.AndroidDeviceConnection).allowedActions,
    )
    assertEquals(DeveloperCapabilityStatus.NeedsRepair, catalog.capability(DeveloperCapabilityId.VScreen).status)
    assertTrue(catalog.needsAttention)
  }

  @Test
  fun kotlinProgressComesOnlyFromSupervisorBytesAndStage() {
    val sources = readySources()
    val environment =
      sources.environment.copy(
        operationInProgress = true,
        setup =
          checkNotNull(sources.environment.setup).copy(
            stage = SupervisorStatusStage.DownloadingComponent,
            selectedCapabilities = listOf(DevelopmentCapability.AndroidKotlin),
            resolvedComponents = resolveComponents(listOf(DevelopmentCapability.AndroidKotlin)),
            readyCapabilities = emptyList(),
            currentComponent = CapabilityComponent.AndroidKotlin,
            completedBytes = 25,
            totalBytes = 100,
          ),
      )
    val capability = resolveDeveloperCapabilityCatalog(sources.copy(environment = environment)).capability(DeveloperCapabilityId.AndroidKotlin)

    assertEquals(DeveloperCapabilityStatus.Downloading, capability.status)
    assertEquals(DeveloperCapabilityProgress(25, 100), capability.progress)

    val invalidProgress =
      resolveDeveloperCapabilityCatalog(
        sources.copy(
          environment =
            environment.copy(
              setup = checkNotNull(environment.setup).copy(completedBytes = 101),
            ),
        ),
      ).capability(DeveloperCapabilityId.AndroidKotlin)
    assertNull(invalidProgress.progress)
  }

  @Test
  fun catalogReadsLiveOwnerStateWithoutKeepingASecondReadinessCopy() =
    runTest {
      val sources = readySources()
      val device = MutableStateFlow(sources.device)
      val observed = mutableListOf<DeveloperCapabilityCatalogState>()
      val catalog =
        developerCapabilityCatalog(
          environment = MutableStateFlow(sources.environment),
          device = device,
          vscreen = MutableStateFlow(sources.vscreen),
          androidUse = MutableStateFlow(sources.androidUse),
          skills = MutableStateFlow(readySkillState()),
        )
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.collect { observed += it } }
      runCurrent()

      assertTrue(observed.last().ready)
      device.value =
        sources.device.copy(
          snapshot = readyDeviceSnapshot().copy(status = AndroidDeviceStatus.Offline, connected = false),
        )
      runCurrent()

      assertEquals(
        DeveloperCapabilityStatus.NeedsRepair,
        observed.last().capability(DeveloperCapabilityId.AndroidDeviceConnection).status,
      )
      assertTrue(observed.last().ready)
    }

  @Test
  fun flutterIsOptionalAndFollowsLiveSupervisorState() {
    val notInstalled = resolveDeveloperCapabilityCatalog(readySources()).capability(DeveloperCapabilityId.Flutter)
    assertEquals(DeveloperCapabilityStatus.NotInstalled, notInstalled.status)
    assertEquals(setOf(DeveloperCapabilityAction.Install), notInstalled.allowedActions)

    val environment =
      readySources().environment.copy(
        setup =
          checkNotNull(readySources().environment.setup).copy(
            selectedCapabilities = listOf(DevelopmentCapability.Flutter),
            resolvedComponents = resolveComponents(listOf(DevelopmentCapability.Flutter)),
            readyCapabilities = listOf(DevelopmentCapability.Flutter),
          ),
      )
    val ready = resolveDeveloperCapabilityCatalog(readySources().copy(environment = environment)).capability(DeveloperCapabilityId.Flutter)
    assertEquals(DeveloperCapabilityStatus.Ready, ready.status)
    assertEquals(setOf(DeveloperCapabilityAction.UseInChat), ready.allowedActions)
    assertEquals("flutter-development", ready.skillReference)
  }

  @Test
  fun reactNativeIsOptionalAndFollowsLiveSupervisorState() {
    val notInstalled =
      resolveDeveloperCapabilityCatalog(readySources())
        .capability(DeveloperCapabilityId.ReactNative)
    assertEquals(DeveloperCapabilityStatus.NotInstalled, notInstalled.status)
    assertEquals(setOf(DeveloperCapabilityAction.Install), notInstalled.allowedActions)

    val selected = listOf(DevelopmentCapability.ReactNative)
    val environment =
      readySources().environment.copy(
        setup =
          checkNotNull(readySources().environment.setup).copy(
            selectedCapabilities = selected,
            resolvedComponents = resolveComponents(selected),
            readyCapabilities = selected,
          ),
      )
    val ready =
      resolveDeveloperCapabilityCatalog(readySources().copy(environment = environment))
        .capability(DeveloperCapabilityId.ReactNative)
    assertEquals(DeveloperCapabilityStatus.Ready, ready.status)
    assertEquals(setOf(DeveloperCapabilityAction.UseInChat), ready.allowedActions)
  }

  @Test
  fun developmentMappingIsCompleteUniqueAndUsesShortChatLabels() {
    assertEquals(DevelopmentCapability.entries, developerCapabilityDefinitions.map { it.developmentCapability })
    assertEquals(6, developerCapabilityDefinitions.map { it.id }.distinct().size)
    assertEquals(6, developerCapabilityDefinitions.map { it.skillReference }.distinct().size)
    assertEquals(
      listOf("Android", "Native", "Flutter", "Godot", "React Native", "Web"),
      developerCapabilityDefinitions.map { it.chatLabel },
    )
  }

  @Test
  fun qualifiedProfileWaitsForGatewayInventoryAndRepairsMissingSkill() {
    val selected = listOf(DevelopmentCapability.AndroidKotlin)
    val environment =
      readySources().environment.copy(
        setup =
          checkNotNull(readySources().environment.setup).copy(
            selectedCapabilities = selected,
            resolvedComponents = resolveComponents(selected),
            readyCapabilities = selected,
          ),
      )

    val checking =
      resolveDeveloperCapabilityCatalog(
        readySources().copy(environment = environment, skills = DeveloperSkillInventory()),
      ).capability(DeveloperCapabilityId.AndroidKotlin)
    assertEquals(DeveloperCapabilityStatus.Checking, checking.status)

    val missing =
      resolveDeveloperCapabilityCatalog(
        readySources().copy(
          environment = environment,
          skills = DeveloperSkillInventory(connected = true, loaded = true),
        ),
      ).capability(DeveloperCapabilityId.AndroidKotlin)
    assertEquals(DeveloperCapabilityStatus.NeedsRepair, missing.status)
    assertEquals(setOf(DeveloperCapabilityAction.Repair), missing.allowedActions)

    val ready = resolveDeveloperCapabilityCatalog(readySources().copy(environment = environment)).capability(DeveloperCapabilityId.AndroidKotlin)
    assertEquals(DeveloperCapabilityStatus.Ready, ready.status)
    assertEquals("android-development", ready.skillReference)
    assertEquals(setOf(DeveloperCapabilityAction.UseInChat), ready.allowedActions)
  }

  private fun DeveloperCapabilityCatalogState.capability(id: DeveloperCapabilityId) = capabilities.single { it.id == id }

  private fun readySources(
    androidUse: AndroidUseCapabilityState = AndroidUseCapabilityState(),
  ) = DeveloperCapabilitySources(
    environment =
      RuntimeState(
        localService = LocalServiceState.Responding,
        gatewayConnected = true,
        gatewayVersion = "2026.9.1",
        setup =
          RuntimeSetup(
            stage = SupervisorStatusStage.GatewayReady,
            planId = "a".repeat(32),
            selectedCapabilities = emptyList(),
            resolvedComponents = resolveComponents(emptyList()),
            readyCapabilities = emptyList(),
            currentComponent = null,
            completedBytes = null,
            totalBytes = null,
            exitCode = 0,
          ),
      ),
    device =
      AndroidDeviceConnectionState(
        availability = AndroidDeviceAvailability.Available,
        snapshot = readyDeviceSnapshot(),
      ),
    vscreen = VScreenState(),
    androidUse = androidUse,
    skills =
      DeveloperSkillInventory(
        connected = true,
        loaded = true,
        eligibleReferences = developerCapabilityDefinitions.mapTo(linkedSetOf()) { it.skillReference },
      ),
  )

  private fun readySkillState(vararg additionalSkillReferences: String) =
    SkillState(
      connected = true,
      loaded = true,
      summary =
        GatewaySkillsSummary(
          skills =
            (developerCapabilityDefinitions.map { definition -> definition.skillReference } + additionalSkillReferences)
              .distinct()
              .map(::skill),
        ),
    )

  private fun skill(name: String) =
    GatewaySkillSummary(
      skillKey = name,
      name = name,
      description = null,
      source = "plugin",
      emoji = null,
      disabled = false,
      eligible = true,
      blockedByAllowlist = false,
      blockedByAgentFilter = false,
      bundled = false,
      missingCount = 0,
      installCount = 0,
    )

  private fun readyDeviceSnapshot() =
    AndroidDeviceSnapshot(
      status = AndroidDeviceStatus.Ready,
      paired = true,
      connected = true,
      reasonCode = null,
      verificationId = "b".repeat(32),
      target =
        AndroidDeviceTarget(
          id = "c".repeat(64),
          product = "shiba",
          model = "Pixel 8",
          androidApi = 37,
        ),
    )
}
