package ai.openclaw.app.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BootstrapAssetTest {
  @Test
  fun publishedBundleContainsEveryInstalledFileAndRelativePluginImport() {
    val context = RuntimeEnvironment.getApplication()
    val sources =
      MediaStoreBootstrapPublisher(
        context,
        object : BootstrapHandoffRepository {
          override fun loadActive(): BootstrapHandoffSession? = error("Not used for source publication")

          override fun saveActive(session: BootstrapHandoffSession): Boolean = error("Not used for source publication")

          override fun readEvents(session: BootstrapHandoffSession): BootstrapHandoffReadResult = error("Not used for source publication")

          override fun checkpoint(
            requestId: String,
            sequence: Long,
            progress: SupervisorProgress,
          ): Boolean = error("Not used for source publication")

          override fun discardActive(requestId: String?) = error("Not used for source publication")
        },
      ).bootstrapSources()
        .associateBy { it.relativePath + it.fileName }
    val installer = sources.getValue("install-supervisor.sh").content.toString(Charsets.UTF_8)
    val installed =
      installer
        .substringAfter("supervisor_assets=(")
        .substringBefore(")")
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    val generatedBySupervisor = setOf("web-development-v1/profile.json")
    for (name in installed) {
      val published = sources[name]
      assertTrue(
        "Installer requires unpublished file $name " +
          "(publishedBytes=${published?.content?.size}, " +
          "matching=${sources.keys.filter { it.endsWith(name.substringAfterLast('/')) }})",
        published?.content?.isNotEmpty() == true,
      )
      if (!name.endsWith(".mjs")) continue
      val parent = name.substringBeforeLast('/', "")
      val content = sources.getValue(name).content.toString(Charsets.UTF_8)
      for (match in Regex("[\"']\\./([^\"']+)[\"']").findAll(content)) {
        val dependency = listOf(parent, match.groupValues[1]).filter(String::isNotEmpty).joinToString("/")
        assertTrue(
          "$name requires uninstalled dependency $dependency",
          dependency in installed || dependency in generatedBySupervisor,
        )
      }
    }
    assertTrue("android-use/tool-authorization.mjs" in installed)
    assertTrue("product-skills/android-use/SKILL.md" in installed)
    assertTrue("android-developer-bridge/bridge/device-bridge.mjs" in installed)
    assertTrue("android-developer-bridge/app-delivery/device-service.mjs" in installed)
    assertTrue("android-developer-bridge/project-build/authorization.mjs" in installed)
    assertTrue("android-developer-bridge/vscreen/device-producer.mjs" in installed)
    assertTrue("vscreen-foundation/remote-producer.mjs" in installed)
    assertTrue("vscreen-foundation/source-relay.mjs" in installed)
    assertTrue("project-workspaces/runtime.mjs" in installed)
    assertTrue("project-workspaces/project-service.mjs" in installed)
    assertTrue("android-kotlin-compose-v1/new-project.mjs" in installed)
    assertTrue("android-kotlin-compose-v1/template/app/src/main/java/starter/MainActivity.kt" in installed)
    assertTrue("android-native-vulkan-v1/release.json" in installed)
    assertTrue("android-native-vulkan-v1/new-project.mjs" in installed)
    assertTrue("android-native-vulkan-v1/template/app/src/main/cpp/CMakeLists.txt" in installed)
    assertTrue("android-native-vulkan-v1/template/app/src/main/cpp/main.c" in installed)
    assertTrue("android-developer-bridge/skills/android-native-development/SKILL.md" in installed)
    assertTrue("flutter-android-v1/release.json" in installed)
    assertTrue("flutter-android-v1/new-project.mjs" in installed)
    assertTrue("android-developer-bridge/skills/flutter-development/SKILL.md" in installed)
    assertTrue("godot-android-v1/release.json" in installed)
    assertTrue("godot-android-v1/new-project.mjs" in installed)
    assertTrue("godot-android-v1/template/export_presets.cfg" in installed)
    assertTrue("godot-developer-bridge/skills/godot-android-development/SKILL.md" !in installed)
    assertTrue("android-developer-bridge/skills/godot-android-development/SKILL.md" in installed)
    assertTrue("reconcile-plugin-paths.mjs" in installed)
    assertTrue("reconcile-plugin-allowlist.mjs" in installed)
    assertTrue("install-bundled-product-plugins.mjs" in installed)
    assertTrue("gateway-config-cache.sh" in installed)
    sources
      .filterKeys { name ->
        name.startsWith("android-kotlin-compose-v1/template/") &&
          (name.endsWith(".kts") || name.endsWith(".kt") || name.endsWith(".properties"))
      }.forEach { (name, source) ->
        assertEquals("MediaStore must preserve $name without adding .txt", "application/octet-stream", source.mimeType)
      }
  }

  @Test
  fun bootstrapAssetOnlyTransfersControlToTheSupervisor() {
    val context = RuntimeEnvironment.getApplication()
    val script =
      context.assets
        .open("claw-in-one/bootstrap.sh")
        .bufferedReader()
        .use { it.readText() }

    assertTrue(script.startsWith("#!/bin/bash\nset -Eeuo pipefail\n"))
    assertTrue(script.contains("shared_dir=\${CLAW_IN_ONE_SHARED_DIR:-\$script_dir}"))
    assertTrue(script.contains("export CLAW_IN_ONE_SHARED_DIR=\"\$shared_dir\""))
    assertTrue(script.contains("CLAW_IN_ONE_CONFIG_FILE:?"))
    assertTrue(script.contains("exec bash \"\$shared_dir/install-supervisor.sh\""))
    assertTrue(!script.contains("bootstrap.executed"))
    assertTrue(!script.contains("bootstrap.log"))
    assertTrue(!script.contains("tee "))
    assertTrue(!script.contains("curl "))
    assertTrue(!script.contains("wget "))
  }

  @Test
  fun supervisorInstallerVerifiesAndStartsTheRustBinary() {
    val context = RuntimeEnvironment.getApplication()
    val script =
      context.assets
        .open("claw-in-one/install-supervisor.sh")
        .bufferedReader()
        .use { it.readText() }

    assertTrue(script.startsWith("#!/bin/bash\nset -Eeuo pipefail\n"))
    assertTrue(script.contains("CLAW_IN_ONE_HANDOFF_PROTOCOL_VERSION"))
    assertTrue(script.contains("CLAW_IN_ONE_HANDOFF_REQUEST_ID"))
    assertTrue(script.contains("CLAW_IN_ONE_HANDOFF_SECRET"))
    assertTrue(script.contains("CLAW_IN_ONE_HANDOFF_EVENTS_FILE"))
    assertTrue(script.contains("CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION"))
    assertTrue(script.contains("CLAW_IN_ONE_SUPERVISOR_SECRET"))
    assertTrue(script.contains("CLAW_IN_ONE_SUPERVISOR_COMMAND_FILE"))
    assertTrue(script.contains("CLAW_IN_ONE_SUPERVISOR_STATUS_FILE"))
    assertTrue(script.contains("openssl dgst -sha256 -mac HMAC"))
    assertTrue(script.contains("CLAW_IN_ONE_SUPERVISOR_BINARY_SHA256"))
    assertTrue(script.contains("sha256sum \"\$supervisor_binary\""))
    assertTrue(script.contains("emit_handoff_event bootstrap_executed '{}'"))
    assertTrue(script.contains("emit_handoff_event installing_supervisor '{}'"))
    assertTrue(script.contains("emit_handoff_event starting_supervisor '{}'"))
    assertTrue(script.contains("emit_handoff_event supervisor_ready \"\$supervisor_ready_payload\""))
    assertTrue(script.contains("sed '/^CLAW_IN_ONE_HANDOFF_/d'"))
    assertTrue(script.contains("remove_private_handoff_credentials"))
    assertTrue(script.contains("claw-in-one-supervisor.service"))
    assertTrue(script.contains("claw-in-one-supervisor\" serve --config"))
    assertTrue(script.contains("systemctl --user enable \"\$supervisor_service_name\""))
    assertTrue(script.contains("systemctl --user restart \"\$supervisor_service_name\""))
    assertTrue(script.contains("supervisor_root=\"\$install_root/supervisor\""))
    assertTrue(script.contains("rm -f -- \"\$handoff_config_file\""))
    assertTrue(script.contains("private_state_dir=\${XDG_STATE_HOME:-\"\$HOME/.local/state\"}/claw-in-one"))
    assertTrue(!script.contains("read_authenticated_control_command"))
    assertTrue(!script.contains("emit_control_status"))
    assertTrue(!script.contains("qr --json"))
    assertTrue(!script.contains("/dev/tcp"))
    assertTrue(!script.contains("HELLO"))
    assertTrue(!script.contains("AUTH OK"))
    assertTrue(!script.contains("READY OK"))
    assertTrue(!script.contains("curl "))
    assertTrue(!script.contains("wget "))
  }

  @Test
  fun supervisorAssetIsAnElfBinary() {
    val context = RuntimeEnvironment.getApplication()
    val header = context.assets.open("claw-in-one/claw-in-one-supervisor").use { it.readNBytes(4) }

    assertTrue(header.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
  }

  @Test
  fun gatewayAssetUsesTlsHealthChecksAndOfficialSetupCodes() {
    val context = RuntimeEnvironment.getApplication()
    val script =
      context.assets
        .open("claw-in-one/start-gateway.sh")
        .bufferedReader()
        .use { it.readText() }
    val supervisionPolicy =
      context.assets
        .open("claw-in-one/supervision-policy.sh")
        .bufferedReader()
        .use { it.readText() }

    assertTrue(script.startsWith("#!/bin/bash\nset -euo pipefail\n"))
    assertTrue(script.contains("gateway.tls.enabled true"))
    assertTrue(script.contains("gateway.tls.autoGenerate true"))
    assertTrue(script.contains("skills.load.extraDirs \"\$CLAW_IN_ONE_SHARED_DIR/product-skills\" add true"))
    assertTrue(script.contains("install-bundled-product-plugins.mjs"))
    assertTrue(script.contains("OPENCLAW_SUPERVISOR_MODE=external"))
    assertTrue(script.contains("healthz"))
    assertTrue(script.contains("readyz"))
    assertTrue(script.contains("qr --json"))
    assertTrue(script.contains("lib/node_modules/openclaw/dist/plugin-sdk/device-bootstrap.js"))
    assertTrue(script.contains("issueDeviceBootstrapToken"))
    assertTrue(script.contains("purpose: \"control-ui-owner\""))
    assertTrue(!script.contains("dashboard --json"))
    assertTrue(script.contains("result.access !== \"full\""))
    assertTrue(script.contains("payload.tlsFingerprint"))
    assertTrue(script.contains("flock -n 9"))
    assertTrue(script.contains("supervisor.state"))
    assertTrue(script.contains("openclaw-gateway"))
    assertTrue(script.contains("\"\$openclaw_bin\" gateway run"))
    assertTrue(!script.contains("run_openclaw gateway run"))
    assertTrue(script.contains("claw_in_one_restart_allowed"))
    assertTrue(script.contains("claw_in_one_restart_delay"))
    assertTrue(supervisionPolicy.contains("restart_count <= 5"))
    assertTrue(supervisionPolicy.contains("delay > 16"))
    assertTrue(script.contains("wait_for_gateway_probe healthz 30"))
    assertTrue(script.contains("wait_for_gateway_probe readyz 30"))
    assertTrue(script.contains("CLAW_IN_ONE_PAIRING_REQUIRED"))
    assertTrue(!script.contains("--token \"\$gateway_token\""))
  }

  @Test
  fun androidUsePluginAssetsAreBundledWithTheBootstrap() {
    val context = RuntimeEnvironment.getApplication()
    val entry =
      context.assets
        .open("claw-in-one/android-use/index.mjs")
        .bufferedReader()
        .use { it.readText() }
    val runtime =
      context.assets
        .open("claw-in-one/android-use/runtime.mjs")
        .bufferedReader()
        .use { it.readText() }
    val manifest =
      context.assets
        .open("claw-in-one/android-use/openclaw.plugin.json")
        .bufferedReader()
        .use { it.readText() }
    val skill =
      context.assets
        .open("claw-in-one/product-skills/android-use/SKILL.md")
        .bufferedReader()
        .use { it.readText() }

    assertTrue(entry.contains("registerAndroidUsePlugin"))
    assertTrue(runtime.contains("claw.android_use"))
    val authorization =
      context.assets
        .open("claw-in-one/android-use/tool-authorization.mjs")
        .bufferedReader()
        .use { it.readText() }
    assertTrue(authorization.contains("runWithWorkAdmission"))
    assertTrue(runtime.contains("createAndroidUseAuthorization"))
    assertTrue(manifest.contains("claw-in-one-android-use"))
    assertTrue(!manifest.contains("\"skills\""))
    assertTrue(skill.contains("name: android-use"))
    assertTrue(skill.contains("android_use"))
  }

  @Test
  fun vscreenFoundationPluginAssetsAreBundledWithTheBootstrap() {
    val context = RuntimeEnvironment.getApplication()
    val runtime =
      context.assets
        .open("claw-in-one/vscreen-foundation/runtime.mjs")
        .bufferedReader()
        .use { it.readText() }
    val display =
      context.assets
        .open("claw-in-one/vscreen-foundation/display-service.mjs")
        .bufferedReader()
        .use { it.readText() }
    val workloads =
      context.assets
        .open("claw-in-one/vscreen-foundation/workload-registry.mjs")
        .bufferedReader()
        .use { it.readText() }
    val remoteProducer =
      context.assets
        .open("claw-in-one/vscreen-foundation/remote-producer.mjs")
        .bufferedReader()
        .use { it.readText() }
    val sourceRelay =
      context.assets
        .open("claw-in-one/vscreen-foundation/source-relay.mjs")
        .bufferedReader()
        .use { it.readText() }
    val websocket =
      context.assets
        .open("claw-in-one/vscreen-foundation/websocket.mjs")
        .bufferedReader()
        .use { it.readText() }
    val manifest =
      context.assets
        .open("claw-in-one/vscreen-foundation/openclaw.plugin.json")
        .bufferedReader()
        .use { it.readText() }

    assertTrue(runtime.contains("registerVScreenFoundationPlugin"))
    assertTrue(display.contains("VScreenDisplayService"))
    assertTrue(workloads.contains("VScreenWorkloadRegistry"))
    assertTrue(remoteProducer.contains("createRemoteVScreenProducer"))
    assertTrue(sourceRelay.contains("createVScreenSourceRelay"))
    assertTrue(websocket.contains("Sec-WebSocket-Accept"))
    assertTrue(manifest.contains("claw-in-one-vscreen-foundation"))
  }

  @Test
  fun androidDeveloperBridgeAssetsAreBundledWithTheBootstrap() {
    val context = RuntimeEnvironment.getApplication()
    val entry =
      context.assets
        .open("claw-in-one/android-developer-bridge/index.mjs")
        .bufferedReader()
        .use { it.readText() }
    val runtime =
      context.assets
        .open("claw-in-one/android-developer-bridge/runtime.mjs")
        .bufferedReader()
        .use { it.readText() }
    val service =
      context.assets
        .open("claw-in-one/android-developer-bridge/bridge/device-bridge.mjs")
        .bufferedReader()
        .use { it.readText() }
    val protocol =
      context.assets
        .open("claw-in-one/android-developer-bridge/bridge/protocol.mjs")
        .bufferedReader()
        .use { it.readText() }
    val vscreenProducer =
      context.assets
        .open("claw-in-one/android-developer-bridge/vscreen/device-producer.mjs")
        .bufferedReader()
        .use { it.readText() }
    val vscreenRpc =
      context.assets
        .open("claw-in-one/android-developer-bridge/vscreen/rpc.mjs")
        .bufferedReader()
        .use { it.readText() }
    val vscreenHelper =
      context.assets
        .open("claw-in-one/android-developer-bridge/vscreen/helper.mjs")
        .bufferedReader()
        .use { it.readText() }
    val approval =
      context.assets
        .open("claw-in-one/android-developer-bridge/app-delivery/install-approval.mjs")
        .bufferedReader()
        .use { it.readText() }
    val installResult =
      context.assets
        .open("claw-in-one/android-developer-bridge/app-delivery/install-result.mjs")
        .bufferedReader()
        .use { it.readText() }
    val vscreenAssignment =
      context.assets
        .open("claw-in-one/android-developer-bridge/app-delivery/vscreen-assignment.mjs")
        .bufferedReader()
        .use { it.readText() }
    val tool =
      context.assets
        .open("claw-in-one/android-developer-bridge/app-delivery/tool.mjs")
        .bufferedReader()
        .use { it.readText() }
    val projectBuildTool =
      context.assets
        .open("claw-in-one/android-developer-bridge/project-build/tool.mjs")
        .bufferedReader()
        .use { it.readText() }
    val projectBuildAuthorization =
      context.assets
        .open("claw-in-one/android-developer-bridge/project-build/authorization.mjs")
        .bufferedReader()
        .use { it.readText() }
    val manifest =
      context.assets
        .open("claw-in-one/android-developer-bridge/openclaw.plugin.json")
        .bufferedReader()
        .use { it.readText() }

    assertTrue(entry.contains("registerAndroidDeveloperBridgePlugin"))
    assertTrue(runtime.contains("registerGatewayMethod"))
    assertTrue(service.contains("ADB_VENDOR_KEYS"))
    assertTrue(protocol.contains("claw.androidDevice.pair"))
    assertTrue(vscreenProducer.contains("AndroidVScreenDeviceProducer"))
    assertTrue(vscreenRpc.contains("createAndroidVScreenRpcAdapter"))
    assertTrue(vscreenHelper.contains("SCRCPY_SERVER_VERSION = \"4.1\""))
    assertTrue(vscreenHelper.contains("requireQualifiedScrcpyServer"))
    assertTrue(!vscreenHelper.contains("globalThis.fetch"))
    assertTrue(approval.contains("beforeToolCall"))
    assertTrue(installResult.contains("AndroidInstalledAppEventStore"))
    assertTrue(vscreenAssignment.contains("AndroidAppVScreenAssignmentStore"))
    assertTrue(tool.contains("consumeAuthorization"))
    assertTrue(projectBuildTool.contains("createAndroidProjectBuildTool"))
    assertTrue(projectBuildAuthorization.contains("createProjectBuildAuthorizationBroker"))
    assertTrue(manifest.contains("claw-in-one-android-developer-bridge"))
  }

  @Test
  fun projectWorkspacePluginAssetsAreBundledWithTheBootstrap() {
    val context = RuntimeEnvironment.getApplication()
    val runtime =
      context.assets
        .open("claw-in-one/project-workspaces/runtime.mjs")
        .bufferedReader()
        .use { it.readText() }
    val service =
      context.assets
        .open("claw-in-one/project-workspaces/project-service.mjs")
        .bufferedReader()
        .use { it.readText() }
    val manifest =
      context.assets
        .open("claw-in-one/project-workspaces/openclaw.plugin.json")
        .bufferedReader()
        .use { it.readText() }

    assertTrue(runtime.contains("PROJECT_WORKSPACE_METHODS.create"))
    assertTrue(runtime.contains("before_agent_run"))
    assertTrue(service.contains("Initialize ClawInOne project"))
    assertTrue(manifest.contains("claw-in-one-project-workspaces"))
  }
}
