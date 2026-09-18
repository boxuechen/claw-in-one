package ai.openclaw.app.supervisor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SupervisorControlProtocolTest {
  private val supervisorId = "a".repeat(32)
  private val secret = "b".repeat(64)
  private val planId = "c".repeat(32)
  private val supervisorBootId = "d".repeat(32)
  private val gatewayGeneration = "e".repeat(32)
  private val now = 1_788_400_000L

  @Test
  fun commandWireFormatMatchesProtocolV10AndSeparatesRequiredSetupFromExtensions() {
    val line =
      signSupervisorCommand(
        secretHex = secret,
        supervisorId = supervisorId,
        sequence = 7,
        timestampEpochSeconds = now,
        command = SupervisorCommand.applyCapabilities(planId, setOf(DevelopmentCapability.Flutter)),
      )
    assertEquals(7, line.trimEnd().split('|').size)
    assertEquals(
      "10|$supervisorId|7|$now|apply_capabilities|$planId:flutter",
      line.substringBeforeLast('|'),
    )
    assertEquals(
      listOf(
        ComponentKey.OpenClawExecutionFoundation,
        ComponentKey.GeneralNodeRuntime,
        ComponentKey.ChromiumRuntime,
        ComponentKey.AdbRuntime,
        ComponentKey.VScreenRuntimeAssets,
        ComponentKey.OpenClawRuntime,
        ComponentKey.AndroidBuildFoundation,
        ComponentKey.AndroidSdk,
        ComponentKey.AndroidGradle,
        ComponentKey.FlutterSdk,
        ComponentKey.AndroidPlatform,
        ComponentKey.AndroidNdk,
        ComponentKey.FlutterProfile,
      ),
      resolveComponents(listOf(DevelopmentCapability.Flutter))
        .map(CapabilityComponent::key),
    )
  }

  @Test
  fun emptyExtensionSelectionStillResolvesRequiredSetup() {
    val command = SupervisorCommand.applyCapabilities(planId, emptySet())
    val line = signSupervisorCommand(secret, supervisorId, 1, now, command)

    assertEquals("10|$supervisorId|1|$now|apply_capabilities|$planId:-", line.substringBeforeLast('|'))
    assertEquals(
      listOf(
        ComponentKey.OpenClawExecutionFoundation,
        ComponentKey.GeneralNodeRuntime,
        ComponentKey.ChromiumRuntime,
        ComponentKey.AdbRuntime,
        ComponentKey.VScreenRuntimeAssets,
        ComponentKey.OpenClawRuntime,
      ),
      resolveComponents(emptyList()).map(CapabilityComponent::key),
    )
  }

  @Test
  fun authenticatedCapabilityStatusIsAccepted() {
    val line = statusLine("capabilities_ready", plan = true, ready = "android_kotlin,flutter")
    assertEquals(
      SupervisorStatusVerificationResult.Accepted(
        SupervisorStatus(
          supervisorBootId = supervisorBootId,
          eventSequence = 1,
          commandSequence = 0,
          timestampEpochSeconds = now,
          stage = SupervisorStatusStage.CapabilitiesReady,
          exitCode = 0,
          planId = planId,
          selectedCapabilities = listOf(DevelopmentCapability.AndroidKotlin, DevelopmentCapability.Flutter),
          resolvedComponents = resolveComponents(listOf(DevelopmentCapability.AndroidKotlin, DevelopmentCapability.Flutter)),
          readyCapabilities = listOf(DevelopmentCapability.AndroidKotlin, DevelopmentCapability.Flutter),
        ),
      ),
      verifySupervisorStatus(line, supervisorId, secret, now),
    )
  }

  @Test
  fun godotPlanAddsEngineAndTemplatesWithoutAnNdk() {
    val resolved =
      resolveComponents(listOf(DevelopmentCapability.GodotAndroid))

    assertEquals(1, resolved.count { it.key == ComponentKey.AndroidSdk })
    assertEquals(
      listOf(ComponentKey.GodotEngine, ComponentKey.GodotExportTemplates, ComponentKey.GodotAndroidProfile),
      resolved.map(CapabilityComponent::key).filter { it.name.startsWith("Godot") },
    )
    assertEquals(0, resolved.count { it.key == ComponentKey.AndroidNdk || it.key == ComponentKey.AndroidCmake })
    assertEquals(0, resolved.count { it.key == ComponentKey.AndroidGradle || it.key == ComponentKey.AndroidKotlinProfile })
  }

  @Test
  fun reactNativePlanUsesVersionedNodeGradleAndNdkWithSharedCmake() {
    val selected = listOf(DevelopmentCapability.ReactNative)
    val resolved = resolveComponents(selected)

    assertEquals(1, resolved.count { it.key == ComponentKey.AndroidSdk })
    assertEquals(1, resolved.count { it.key == ComponentKey.AndroidCmake })
    assertEquals(1, resolved.count { it.key == ComponentKey.AndroidGradle })
    assertEquals(1, resolved.count { it.key == ComponentKey.GeneralNodeRuntime })
    assertEquals(1, resolved.count { it.key == ComponentKey.ReactNativeDistribution })
    val cmake = resolved.single { it.key == ComponentKey.AndroidCmake }
    assertEquals(
      setOf(DevelopmentCapability.ReactNative),
      activeCapabilityConsumers(cmake, selected),
    )
    assertEquals(
      DevelopmentCapability.ReactNative,
      uniqueActiveCapabilityConsumer(cmake, selected),
    )
    assertEquals(
      setOf(DevelopmentCapability.AndroidNative, DevelopmentCapability.ReactNative),
      capabilityConsumers(cmake),
    )
  }

  @Test
  fun webPlanReusesRequiredGeneralNodeWithoutReactNativeComponents() {
    val selected = listOf(DevelopmentCapability.WebDevelopment)
    val resolved = resolveComponents(selected)

    assertEquals(
      listOf(
        ComponentKey.OpenClawExecutionFoundation,
        ComponentKey.GeneralNodeRuntime,
        ComponentKey.ChromiumRuntime,
        ComponentKey.AdbRuntime,
        ComponentKey.VScreenRuntimeAssets,
        ComponentKey.OpenClawRuntime,
        ComponentKey.WebDistribution,
        ComponentKey.WebProfile,
      ),
      resolved.map(CapabilityComponent::key),
    )
    assertEquals(
      setOf(DevelopmentCapability.WebDevelopment),
      activeCapabilityConsumers(resolved.single { it.key == ComponentKey.WebDistribution }, selected),
    )
    assertEquals(0, resolved.count { it.key == ComponentKey.AndroidSdk || it.key == ComponentKey.AndroidBuildFoundation })
  }

  @Test
  fun legacyV9AndNonCanonicalCapabilitiesFailClosed() {
    assertEquals(
      SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.UnsupportedVersion),
      verifySupervisorStatus(statusLine("capabilities_ready", plan = true).replaceFirst("10|", "9|"), supervisorId, secret, now),
    )
    assertEquals(
      SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.Malformed),
      verifySupervisorStatus(
        statusLine("capabilities_ready", plan = true, selected = "flutter,android_kotlin"),
        supervisorId,
        secret,
        now,
      ),
    )
    assertEquals(
      SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.Malformed),
      verifySupervisorStatus(
        statusLine(
          "capabilities_ready",
          plan = true,
          selected = "flutter",
          resolved =
            resolveComponents(listOf(DevelopmentCapability.Flutter))
              .joinToString(",", transform = CapabilityComponent::wireName),
          ready = "android_kotlin",
        ),
        supervisorId,
        secret,
        now,
      ),
    )
  }

  @Test
  fun progressRequiresTheMatchingPhysicalComponent() {
    val accepted =
      statusLine(
        stage = "downloading_component",
        plan = true,
        current = CapabilityComponent.Flutter.wireName,
        completedBytes = 25,
        totalBytes = 100,
      )
    val status = (verifySupervisorStatus(accepted, supervisorId, secret, now) as SupervisorStatusVerificationResult.Accepted).status
    assertEquals(CapabilityComponent.Flutter, status.currentComponent)
    assertEquals(25L, status.completedBytes)
    assertEquals(
      SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.Malformed),
      verifySupervisorStatus(
        statusLine(
          stage = "downloading_component",
          plan = true,
          current = CapabilityComponent.AndroidNative.wireName,
          completedBytes = 25,
          totalBytes = 100,
        ),
        supervisorId,
        secret,
        now,
      ),
    )
  }

  @Test
  fun onlyGatewayPairingReadyCanCarryASetupCode() {
    val setupCode = "a_valid_gateway_setup_code"
    val accepted = verifySupervisorStatus(statusLine("gateway_pairing_ready", plan = true, setupCode = setupCode), supervisorId, secret, now)
    assertEquals(setupCode, (accepted as SupervisorStatusVerificationResult.Accepted).status.setupCode)
    assertEquals(
      SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.Malformed),
      verifySupervisorStatus(statusLine("capabilities_ready", plan = true, setupCode = setupCode), supervisorId, secret, now),
    )
    assertEquals(
      SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.Malformed),
      verifySupervisorStatus(statusLine("gateway_ready", plan = true, setupCode = setupCode), supervisorId, secret, now),
    )
  }

  private fun statusLine(
    stage: String,
    plan: Boolean,
    exitCode: Int = 0,
    selected: String = "android_kotlin,flutter",
    resolved: String =
      resolveComponents(listOf(DevelopmentCapability.AndroidKotlin, DevelopmentCapability.Flutter))
        .joinToString(",", transform = CapabilityComponent::wireName),
    ready: String = "-",
    current: String = "-",
    setupCode: String = "-",
    completedBytes: Long? = null,
    totalBytes: Long? = null,
  ): String {
    val fields =
      listOf(
        SUPERVISOR_CONTROL_PROTOCOL_VERSION.toString(),
        supervisorId,
        supervisorBootId,
        "1",
        "0",
        now.toString(),
        stage,
        if (stage.startsWith("gateway_") && stage != "gateway_not_started") gatewayGeneration else "-",
        if (stage.startsWith("gateway_") && stage != "gateway_not_started") "1" else "-",
        exitCode.toString(),
        if (plan) planId else "-",
        if (plan) selected else "-",
        if (plan) resolved else "-",
        ready,
        current,
        completedBytes?.toString() ?: "-",
        totalBytes?.toString() ?: "-",
        setupCode,
      )
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(ByteArray(32) { 0xbb.toByte() }, "HmacSHA256"))
    val signature =
      mac
        .doFinal(fields.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return (fields + signature).joinToString("|") + "\n"
  }
}
