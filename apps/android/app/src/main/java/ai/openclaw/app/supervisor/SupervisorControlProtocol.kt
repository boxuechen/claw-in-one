package ai.openclaw.app.supervisor

import ai.openclaw.app.bootstrap.PINNED_CAPABILITY_PACKS
import ai.openclaw.app.bootstrap.PINNED_EXECUTION_ENVIRONMENT
import ai.openclaw.app.bootstrap.PINNED_OPENCLAW_RELEASE
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val SUPERVISOR_CONTROL_PROTOCOL_VERSION = 10
internal const val SUPERVISOR_CONTROL_MAX_BYTES = 4 * 1024
internal const val SUPERVISOR_CONTROL_MAX_AGE_SECONDS = 12 * 60 * 60L
internal const val SUPERVISOR_CONTROL_MAX_FUTURE_SKEW_SECONDS = 5 * 60L

/** Stable user intent. It is deliberately separate from physical install steps. */
@Serializable
internal enum class DevelopmentCapability(
  val wireName: String,
) {
  AndroidKotlin("android_kotlin"),
  AndroidNative("android_native"),
  Flutter("flutter"),
  GodotAndroid("godot_android"),
  ReactNative("react_native"),
  WebDevelopment("web_development"),
}

/** Stable physical component kind. Exact version is always part of its identity. */
@Serializable
internal enum class ComponentKey(
  val wireName: String,
) {
  OpenClawExecutionFoundation("openclaw_execution_foundation"),
  GeneralNodeRuntime("general_node_runtime"),
  ChromiumRuntime("chromium_runtime"),
  AdbRuntime("adb_runtime"),
  VScreenRuntimeAssets("vscreen_runtime_assets"),
  OpenClawRuntime("openclaw_runtime"),
  AndroidBuildFoundation("android_build_foundation"),
  AndroidSdk("android_sdk"),
  AndroidPlatform("android_platform"),
  AndroidGradle("android_gradle"),
  AndroidNdk("android_ndk"),
  AndroidCmake("android_cmake"),
  AndroidKotlinProfile("android_kotlin_profile"),
  AndroidNativeProfile("android_native_profile"),
  FlutterSdk("flutter_sdk"),
  FlutterProfile("flutter_profile"),
  GodotEngine("godot_engine"),
  GodotExportTemplates("godot_export_templates"),
  GodotAndroidProfile("godot_android_profile"),
  ReactNativeDistribution("react_native_distribution"),
  ReactNativeAndroidProfile("react_native_android_profile"),
  WebDistribution("web_distribution"),
  WebProfile("web_profile"),
}

/** Release-owned implementation step; Android may observe but never select one. */
@Serializable
internal data class CapabilityComponent(
  val key: ComponentKey,
  val version: String,
) {
  val wireName: String get() = "${key.wireName}@$version"

  companion object {
    val entries: List<CapabilityComponent> get() = ReleaseComponentGraph.all
    val OpenClaw: CapabilityComponent get() = ReleaseComponentGraph.openClaw
    val AndroidKotlin: CapabilityComponent get() = ReleaseComponentGraph.kotlinProfile
    val AndroidNative: CapabilityComponent get() = ReleaseComponentGraph.nativeProfile
    val Flutter: CapabilityComponent get() = ReleaseComponentGraph.flutterProfile
    val GodotAndroid: CapabilityComponent get() = ReleaseComponentGraph.godotProfile
    val ReactNative: CapabilityComponent get() = ReleaseComponentGraph.reactNativeProfile
    val WebDevelopment: CapabilityComponent get() = ReleaseComponentGraph.webProfile
  }
}

private object ReleaseComponentGraph {
  private data class Node(
    val identity: CapabilityComponent,
    val dependencies: List<CapabilityComponent>,
  )

  private val environment = PINNED_EXECUTION_ENVIRONMENT
  private val extensions = PINNED_CAPABILITY_PACKS
  private val packageSetVersion = "debian${environment.debianSeries}.g${environment.packageSetGeneration}"

  val openClaw = CapabilityComponent(ComponentKey.OpenClawRuntime, PINNED_OPENCLAW_RELEASE.version)
  private val foundation = CapabilityComponent(ComponentKey.OpenClawExecutionFoundation, packageSetVersion)
  private val generalNode = CapabilityComponent(ComponentKey.GeneralNodeRuntime, environment.generalNodeVersion)
  private val chromium = CapabilityComponent(ComponentKey.ChromiumRuntime, packageSetVersion)
  private val adb = CapabilityComponent(ComponentKey.AdbRuntime, packageSetVersion)
  private val vscreen = CapabilityComponent(ComponentKey.VScreenRuntimeAssets, environment.scrcpyServerVersion)
  private val androidBuildFoundation = CapabilityComponent(ComponentKey.AndroidBuildFoundation, packageSetVersion)
  private val androidSdk =
    CapabilityComponent(
      ComponentKey.AndroidSdk,
      "cmd${extensions.androidCommandToolsVersion}.platform${extensions.androidPlatform}.build${extensions.androidBuildTools}",
    )
  private val gradle = CapabilityComponent(ComponentKey.AndroidGradle, extensions.androidGradleVersion)
  val kotlinProfile = CapabilityComponent(ComponentKey.AndroidKotlinProfile, "android-kotlin-compose-v1.g1")
  private val nativeNdk = CapabilityComponent(ComponentKey.AndroidNdk, extensions.androidNativeNdk)
  private val nativeCmake = CapabilityComponent(ComponentKey.AndroidCmake, extensions.androidNativeCmake)
  val nativeProfile = CapabilityComponent(ComponentKey.AndroidNativeProfile, "android-native-vulkan-v1.g1")
  private val flutterSdk = CapabilityComponent(ComponentKey.FlutterSdk, extensions.flutterVersion)
  private val flutterPlatform = CapabilityComponent(ComponentKey.AndroidPlatform, extensions.flutterAndroidPlatform)
  private val flutterNdk = CapabilityComponent(ComponentKey.AndroidNdk, extensions.flutterAndroidNdk)
  val flutterProfile = CapabilityComponent(ComponentKey.FlutterProfile, "flutter-android-v1.g2")
  private val godotEngine = CapabilityComponent(ComponentKey.GodotEngine, extensions.godotVersion)
  private val godotExportTemplates = CapabilityComponent(ComponentKey.GodotExportTemplates, extensions.godotVersion)
  val godotProfile = CapabilityComponent(ComponentKey.GodotAndroidProfile, "godot-android-v1.g1")
  private val reactNativeGradle = CapabilityComponent(ComponentKey.AndroidGradle, extensions.reactNativeGradleVersion)
  private val reactNativeNdk = CapabilityComponent(ComponentKey.AndroidNdk, extensions.reactNativeAndroidNdk)
  private val reactNativeDistribution =
    CapabilityComponent(ComponentKey.ReactNativeDistribution, "${extensions.reactNativeVersion}.g1")
  val reactNativeProfile = CapabilityComponent(ComponentKey.ReactNativeAndroidProfile, "react-native-android-v1.g1")
  private val webDistribution = CapabilityComponent(ComponentKey.WebDistribution, "vite${extensions.webViteVersion}.g1")
  val webProfile = CapabilityComponent(ComponentKey.WebProfile, "web-development-v1.g1")

  val required = listOf(foundation, generalNode, chromium, adb, vscreen, openClaw)
  private val nodes =
    listOf(
      Node(foundation, emptyList()),
      Node(generalNode, listOf(foundation)),
      Node(chromium, listOf(foundation)),
      Node(adb, listOf(foundation)),
      Node(vscreen, listOf(foundation)),
      Node(openClaw, listOf(foundation)),
      Node(androidBuildFoundation, listOf(foundation)),
      Node(androidSdk, listOf(androidBuildFoundation)),
      Node(gradle, listOf(androidSdk)),
      Node(kotlinProfile, listOf(gradle)),
      Node(nativeNdk, listOf(androidSdk)),
      Node(nativeCmake, listOf(androidSdk)),
      Node(nativeProfile, listOf(gradle, nativeNdk, nativeCmake)),
      Node(flutterSdk, listOf(foundation)),
      Node(flutterPlatform, listOf(androidSdk)),
      Node(flutterNdk, listOf(androidSdk)),
      Node(flutterProfile, listOf(gradle, flutterSdk, flutterPlatform, flutterNdk)),
      Node(godotEngine, listOf(foundation)),
      Node(godotExportTemplates, listOf(foundation)),
      Node(godotProfile, listOf(androidSdk, godotEngine, godotExportTemplates)),
      Node(reactNativeGradle, listOf(androidSdk)),
      Node(reactNativeNdk, listOf(androidSdk)),
      Node(reactNativeDistribution, listOf(generalNode)),
      Node(
        reactNativeProfile,
        listOf(androidSdk, reactNativeGradle, reactNativeNdk, nativeCmake, reactNativeDistribution),
      ),
      Node(webDistribution, listOf(generalNode)),
      Node(webProfile, listOf(foundation, webDistribution)),
    )
  private val roots =
    mapOf(
      DevelopmentCapability.AndroidKotlin to kotlinProfile,
      DevelopmentCapability.AndroidNative to nativeProfile,
      DevelopmentCapability.Flutter to flutterProfile,
      DevelopmentCapability.GodotAndroid to godotProfile,
      DevelopmentCapability.ReactNative to reactNativeProfile,
      DevelopmentCapability.WebDevelopment to webProfile,
    )
  val all = nodes.map(Node::identity)

  fun resolve(selected: Collection<DevelopmentCapability>): List<CapabilityComponent> {
    val included = linkedSetOf<CapabilityComponent>()

    fun include(component: CapabilityComponent) {
      if (component in included) return
      val node = checkNotNull(nodes.singleOrNull { it.identity == component })
      node.dependencies.forEach(::include)
      included += component
    }

    required.forEach(::include)
    DevelopmentCapability.entries.filter(selected::contains).forEach { include(checkNotNull(roots[it])) }
    return nodes.map(Node::identity).filter(included::contains)
  }

  fun consumers(component: CapabilityComponent): Set<DevelopmentCapability> =
    if (component in required) {
      emptySet()
    } else {
      DevelopmentCapability.entries
        .filterTo(linkedSetOf()) { capability -> component in resolve(listOf(capability)) }
    }
}

internal fun capabilityConsumers(component: CapabilityComponent): Set<DevelopmentCapability> = ReleaseComponentGraph.consumers(component)

internal fun activeCapabilityConsumers(
  component: CapabilityComponent,
  selected: Collection<DevelopmentCapability>,
): Set<DevelopmentCapability> = capabilityConsumers(component).filterTo(linkedSetOf(), selected::contains)

internal fun uniqueActiveCapabilityConsumer(
  component: CapabilityComponent,
  selected: Collection<DevelopmentCapability>,
): DevelopmentCapability? =
  activeCapabilityConsumers(component, selected)
    .singleOrNull()

@Serializable
internal enum class SupervisorOperation(
  val wireName: String,
) {
  Probe("probe"),
  ApplyCapabilities("apply_capabilities"),
  RetryCapabilities("retry_capabilities"),
  SkipCapability("skip_capability"),
  EnsureGateway("ensure_gateway"),
  RequestGatewayPairing("request_gateway_pairing"),
}

@Serializable
internal data class SupervisorCommand(
  val operation: SupervisorOperation,
  val argument: String,
) {
  init {
    require(isValid())
  }

  fun isValid(): Boolean =
    when (operation) {
      SupervisorOperation.Probe,
      SupervisorOperation.EnsureGateway,
      SupervisorOperation.RequestGatewayPairing,
      -> argument == "-"
      SupervisorOperation.ApplyCapabilities -> parseCapabilityRequest(argument) != null
      SupervisorOperation.RetryCapabilities -> argument.matches(PLAN_ID_REGEX)
      SupervisorOperation.SkipCapability -> parseSkipArgument(argument) != null
    }

  fun selectedCapabilities(): List<DevelopmentCapability>? =
    if (operation == SupervisorOperation.ApplyCapabilities) {
      parseCapabilityRequest(argument)?.second
    } else {
      null
    }

  companion object {
    val Probe = SupervisorCommand(SupervisorOperation.Probe, "-")
    val EnsureGateway = SupervisorCommand(SupervisorOperation.EnsureGateway, "-")
    val RequestGatewayPairing = SupervisorCommand(SupervisorOperation.RequestGatewayPairing, "-")

    fun applyCapabilities(
      planId: String,
      capabilities: Set<DevelopmentCapability>,
    ): SupervisorCommand {
      require(planId.matches(PLAN_ID_REGEX))
      val selected = DevelopmentCapability.entries.filter(capabilities::contains)
      return SupervisorCommand(
        SupervisorOperation.ApplyCapabilities,
        "$planId:${selected.toOptionalWireList()}",
      )
    }

    fun retryCapabilities(planId: String): SupervisorCommand = SupervisorCommand(SupervisorOperation.RetryCapabilities, planId)

    fun skipCapability(
      planId: String,
      capability: DevelopmentCapability,
    ): SupervisorCommand =
      SupervisorCommand(
        SupervisorOperation.SkipCapability,
        "$planId:${capability.wireName}",
      )
  }
}

internal enum class SupervisorStatusStage(
  val wireName: String,
) {
  SupervisorReady("supervisor_ready"),
  CapabilitiesRequired("capabilities_required"),
  CapabilityPlanAccepted("capability_plan_accepted"),
  DownloadingNode("downloading_node"),
  VerifyingNode("verifying_node"),
  InstallingNode("installing_node"),
  DownloadingOpenClaw("downloading_openclaw"),
  VerifyingOpenClaw("verifying_openclaw"),
  InstallingOpenClaw("installing_openclaw"),
  VerifyingOpenClawInstallation("verifying_openclaw_installation"),
  OpenClawReady("openclaw_ready"),
  DownloadingComponent("downloading_component"),
  VerifyingComponent("verifying_component"),
  InstallingComponent("installing_component"),
  ComponentReady("component_ready"),
  CapabilitiesReady("capabilities_ready"),
  CapabilityFailed("capability_failed"),
  CapabilityPlanRejected("capability_plan_rejected"),
  GatewayNotStarted("gateway_not_started"),
  GatewayConfiguring("gateway_configuring"),
  GatewayStarting("gateway_starting"),
  GatewayHealthy("gateway_healthy"),
  GatewayReady("gateway_ready"),
  GatewayPairingReady("gateway_pairing_ready"),
  GatewayPairingFailed("gateway_pairing_failed"),
  GatewayFailed("gateway_failed"),
}

internal data class SupervisorStatus(
  val supervisorBootId: String,
  val eventSequence: Long,
  val commandSequence: Long,
  val timestampEpochSeconds: Long,
  val stage: SupervisorStatusStage,
  val gatewayGeneration: String? = null,
  val ensureAttemptId: Long? = null,
  val exitCode: Int,
  val planId: String? = null,
  val selectedCapabilities: List<DevelopmentCapability> = emptyList(),
  val resolvedComponents: List<CapabilityComponent> = emptyList(),
  val readyCapabilities: List<DevelopmentCapability> = emptyList(),
  val currentComponent: CapabilityComponent? = null,
  val setupCode: String? = null,
  val completedBytes: Long? = null,
  val totalBytes: Long? = null,
)

internal enum class SupervisorControlVerificationError {
  Malformed,
  WrongSupervisor,
  UnsupportedVersion,
  AuthenticationFailed,
  Expired,
}

internal sealed interface SupervisorStatusVerificationResult {
  data class Accepted(
    val status: SupervisorStatus,
  ) : SupervisorStatusVerificationResult

  data class Rejected(
    val error: SupervisorControlVerificationError,
  ) : SupervisorStatusVerificationResult
}

internal fun signSupervisorCommand(
  secretHex: String,
  supervisorId: String,
  sequence: Long,
  timestampEpochSeconds: Long,
  command: SupervisorCommand,
): String {
  requireSupervisorIdentity(supervisorId, secretHex)
  require(sequence > 0)
  require(timestampEpochSeconds > 0)
  require(command.isValid())
  val fields =
    listOf(
      SUPERVISOR_CONTROL_PROTOCOL_VERSION.toString(),
      supervisorId,
      sequence.toString(),
      timestampEpochSeconds.toString(),
      command.operation.wireName,
      command.argument,
    )
  return (fields + supervisorControlMac(secretHex, fields)).joinToString("|") + "\n"
}

internal fun verifySupervisorStatus(
  line: String,
  expectedSupervisorId: String,
  secretHex: String,
  nowEpochSeconds: Long,
): SupervisorStatusVerificationResult {
  if (
    !expectedSupervisorId.matches(SUPERVISOR_ID_REGEX) ||
    !secretHex.matches(SUPERVISOR_SECRET_REGEX) ||
    line.isBlank() ||
    line.toByteArray(StandardCharsets.UTF_8).size > SUPERVISOR_CONTROL_MAX_BYTES
  ) {
    return rejectedMalformed()
  }
  val fields = line.removeSuffix("\n").split('|')
  if (fields.size != 19 || fields.any(String::isEmpty)) return rejectedMalformed()

  val version = fields[0].toIntOrNull()
  val supervisorId = fields[1]
  val supervisorBootId = fields[2]
  val eventSequence = fields[3].toLongOrNull()
  val commandSequence = fields[4].toLongOrNull()
  val timestamp = fields[5].toLongOrNull()
  val stage = SupervisorStatusStage.entries.firstOrNull { it.wireName == fields[6] }
  val gatewayGenerationField = fields[7]
  val ensureAttemptIdField = fields[8]
  val exitCode = fields[9].toIntOrNull()
  val planIdField = fields[10]
  val selectedField = fields[11]
  val resolvedField = fields[12]
  val readyField = fields[13]
  val currentField = fields[14]
  val completedBytesField = fields[15]
  val totalBytesField = fields[16]
  val setupCodeField = fields[17]
  val actualMac = fields[18]
  if (
    version == null ||
    eventSequence == null ||
    commandSequence == null ||
    timestamp == null ||
    stage == null ||
    exitCode == null ||
    !supervisorBootId.matches(RUNTIME_ID_REGEX) ||
    eventSequence <= 0 ||
    commandSequence < 0 ||
    exitCode !in 0..255 ||
    !actualMac.matches(SUPERVISOR_MAC_REGEX)
  ) {
    return rejectedMalformed()
  }
  if (version != SUPERVISOR_CONTROL_PROTOCOL_VERSION) {
    return SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.UnsupportedVersion)
  }
  if (supervisorId != expectedSupervisorId) {
    return SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.WrongSupervisor)
  }
  val expectedMac = supervisorControlMac(secretHex, fields.dropLast(1))
  if (
    !MessageDigest.isEqual(
      actualMac.toByteArray(StandardCharsets.US_ASCII),
      expectedMac.toByteArray(StandardCharsets.US_ASCII),
    )
  ) {
    return SupervisorStatusVerificationResult.Rejected(
      SupervisorControlVerificationError.AuthenticationFailed,
    )
  }
  if (
    timestamp <= 0 ||
    timestamp < nowEpochSeconds - SUPERVISOR_CONTROL_MAX_AGE_SECONDS ||
    timestamp > nowEpochSeconds + SUPERVISOR_CONTROL_MAX_FUTURE_SKEW_SECONDS
  ) {
    return SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.Expired)
  }

  val planId = planIdField.takeUnless { it == "-" }
  val gatewayGeneration = gatewayGenerationField.takeUnless { it == "-" }
  val ensureAttemptId = ensureAttemptIdField.takeUnless { it == "-" }?.toLongOrNull()
  val gatewayStage =
    stage in
      setOf(
        SupervisorStatusStage.GatewayConfiguring,
        SupervisorStatusStage.GatewayStarting,
        SupervisorStatusStage.GatewayHealthy,
        SupervisorStatusStage.GatewayReady,
        SupervisorStatusStage.GatewayPairingReady,
        SupervisorStatusStage.GatewayPairingFailed,
        SupervisorStatusStage.GatewayFailed,
      )
  if (
    (gatewayGenerationField == "-") != (ensureAttemptIdField == "-") ||
    gatewayStage != (gatewayGeneration != null && ensureAttemptId != null) ||
    (gatewayGeneration != null && !gatewayGeneration.matches(RUNTIME_ID_REGEX)) ||
    (ensureAttemptId != null && ensureAttemptId <= 0)
  ) {
    return rejectedMalformed()
  }
  val selected = if (selectedField == "-") emptyList() else parseCapabilityList(selectedField)
  val resolved = resolvedField.takeUnless { it == "-" }?.let(::parseComponentList)
  val ready = if (readyField == "-") emptyList() else parseCapabilityList(readyField)
  val current = currentField.takeUnless { it == "-" }?.let(::parseComponent)
  val requiresPlan =
    stage !in
      setOf(
        SupervisorStatusStage.SupervisorReady,
        SupervisorStatusStage.CapabilitiesRequired,
      )
  if (
    (planId == null && selectedField != "-") ||
    (planId == null) != (resolved == null) ||
    (planId != null && !planId.matches(PLAN_ID_REGEX)) ||
    requiresPlan != (planId != null) ||
    selected == null ||
    (resolvedField != "-" && resolved == null) ||
    ready == null ||
    ready.any { it !in selected } ||
    (currentField != "-" && current == null) ||
    (current != null && resolved?.contains(current) != true) ||
    (planId != null && resolved != resolveComponents(selected)) ||
    !validStageComponent(stage, current)
  ) {
    return rejectedMalformed()
  }

  val completedBytes = completedBytesField.takeUnless { it == "-" }?.toLongOrNull()
  val totalBytes = totalBytesField.takeUnless { it == "-" }?.toLongOrNull()
  val hasProgress = completedBytesField != "-" || totalBytesField != "-"
  val downloadStage =
    stage in
      setOf(
        SupervisorStatusStage.DownloadingNode,
        SupervisorStatusStage.DownloadingOpenClaw,
        SupervisorStatusStage.DownloadingComponent,
      )
  if (
    (completedBytesField == "-") != (totalBytesField == "-") ||
    (hasProgress && (completedBytes == null || totalBytes == null)) ||
    hasProgress != downloadStage ||
    (hasProgress && (completedBytes!! < 0 || totalBytes!! <= 0 || completedBytes > totalBytes))
  ) {
    return rejectedMalformed()
  }

  val setupCode = setupCodeField.takeUnless { it == "-" }
  if (
    (stage == SupervisorStatusStage.GatewayPairingReady && setupCode?.matches(SETUP_CODE_REGEX) != true) ||
    (stage != SupervisorStatusStage.GatewayPairingReady && setupCode != null)
  ) {
    return rejectedMalformed()
  }
  val failureStage =
    stage in
      setOf(
        SupervisorStatusStage.CapabilityFailed,
        SupervisorStatusStage.CapabilityPlanRejected,
        SupervisorStatusStage.GatewayPairingFailed,
        SupervisorStatusStage.GatewayFailed,
      )
  if (failureStage != (exitCode != 0)) return rejectedMalformed()

  return SupervisorStatusVerificationResult.Accepted(
    SupervisorStatus(
      supervisorBootId = supervisorBootId,
      eventSequence = eventSequence,
      commandSequence = commandSequence,
      timestampEpochSeconds = timestamp,
      stage = stage,
      gatewayGeneration = gatewayGeneration,
      ensureAttemptId = ensureAttemptId,
      exitCode = exitCode,
      planId = planId,
      selectedCapabilities = if (planId == null) emptyList() else selected,
      resolvedComponents = resolved.orEmpty(),
      readyCapabilities = ready,
      currentComponent = current,
      setupCode = setupCode,
      completedBytes = completedBytes,
      totalBytes = totalBytes,
    ),
  )
}

internal fun resolveComponents(selected: List<DevelopmentCapability>): List<CapabilityComponent> = ReleaseComponentGraph.resolve(selected)

internal fun requiredSetupComponents(): List<CapabilityComponent> = ReleaseComponentGraph.required

private fun validStageComponent(
  stage: SupervisorStatusStage,
  current: CapabilityComponent?,
): Boolean {
  val expectedKey =
    when (stage) {
      SupervisorStatusStage.DownloadingNode,
      SupervisorStatusStage.VerifyingNode,
      SupervisorStatusStage.InstallingNode,
      SupervisorStatusStage.DownloadingOpenClaw,
      SupervisorStatusStage.VerifyingOpenClaw,
      SupervisorStatusStage.InstallingOpenClaw,
      SupervisorStatusStage.VerifyingOpenClawInstallation,
      SupervisorStatusStage.OpenClawReady,
      ->
        ComponentKey.OpenClawRuntime
      SupervisorStatusStage.DownloadingComponent,
      SupervisorStatusStage.VerifyingComponent,
      SupervisorStatusStage.InstallingComponent,
      SupervisorStatusStage.ComponentReady,
      SupervisorStatusStage.CapabilityFailed,
      ->
        return current != null
      else -> null
    }
  return current?.key == expectedKey
}

private fun parseCapabilityRequest(value: String): Pair<String, List<DevelopmentCapability>>? {
  val (planId, capabilities) = value.splitOnce(':') ?: return null
  val selected = if (capabilities == "-") emptyList() else parseCapabilityList(capabilities) ?: return null
  return if (planId.matches(PLAN_ID_REGEX)) planId to selected else null
}

private fun parseSkipArgument(value: String): Pair<String, DevelopmentCapability>? {
  val (planId, capabilityValue) = value.splitOnce(':') ?: return null
  val capability = parseCapability(capabilityValue) ?: return null
  return if (planId.matches(PLAN_ID_REGEX)) planId to capability else null
}

private fun parseCapabilityList(value: String): List<DevelopmentCapability>? {
  if (value.isEmpty()) return null
  val parsed = value.split(',').map { parseCapability(it) ?: return null }
  return parsed.takeIf { it == DevelopmentCapability.entries.filter(parsed::contains) }
}

private fun parseComponentList(value: String): List<CapabilityComponent>? {
  if (value.isEmpty()) return null
  val parsed = value.split(',').map { parseComponent(it) ?: return null }
  return parsed.takeIf { it.distinct().size == it.size }
}

private fun parseCapability(value: String): DevelopmentCapability? = DevelopmentCapability.entries.firstOrNull { it.wireName == value }

private fun parseComponent(value: String): CapabilityComponent? = ReleaseComponentGraph.all.firstOrNull { it.wireName == value }

private fun List<DevelopmentCapability>.toWireList(): String = joinToString(",", transform = DevelopmentCapability::wireName)

private fun List<DevelopmentCapability>.toOptionalWireList(): String = if (isEmpty()) "-" else toWireList()

private fun String.splitOnce(delimiter: Char): Pair<String, String>? {
  val index = indexOf(delimiter)
  if (index <= 0 || index == lastIndex || indexOf(delimiter, index + 1) >= 0) return null
  return substring(0, index) to substring(index + 1)
}

private fun rejectedMalformed() = SupervisorStatusVerificationResult.Rejected(SupervisorControlVerificationError.Malformed)

private fun requireSupervisorIdentity(
  supervisorId: String,
  secretHex: String,
) {
  require(supervisorId.matches(SUPERVISOR_ID_REGEX))
  require(secretHex.matches(SUPERVISOR_SECRET_REGEX))
}

private fun supervisorControlMac(
  secretHex: String,
  fields: List<String>,
): String {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(secretHex.hexToBytes(), "HmacSHA256"))
  return mac
    .doFinal(fields.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun String.hexToBytes(): ByteArray =
  chunked(2)
    .map { value -> value.toInt(16).toByte() }
    .toByteArray()

private val SUPERVISOR_ID_REGEX = Regex("[0-9a-f]{32}")
private val SUPERVISOR_SECRET_REGEX = Regex("[0-9a-f]{64}")
private val SUPERVISOR_MAC_REGEX = Regex("[0-9a-f]{64}")
private val RUNTIME_ID_REGEX = Regex("[0-9a-f]{32}")
private val PLAN_ID_REGEX = Regex("[0-9a-f]{32}")
private val SETUP_CODE_REGEX = Regex("[A-Za-z0-9_-]{16,3072}")
