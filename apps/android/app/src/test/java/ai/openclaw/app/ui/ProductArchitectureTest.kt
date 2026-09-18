package ai.openclaw.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

class ProductArchitectureTest {
  @Test
  fun runtimeBoundComposablesAreRoutesOrPlatformBindings() {
    val violations =
      uiSources().flatMap { path ->
        composableFunction.findAll(path.readText()).mapNotNull { match ->
          val name = match.groupValues[1]
          val parameters = match.groupValues[2]
          if (
            "MainViewModel" in parameters &&
            !name.endsWith("Route") &&
            !name.startsWith("remember")
          ) {
            "${path.fileName}:$name"
          } else {
            null
          }
        }
      }

    assertTrue("Runtime-bound composables must be named as routes: $violations", violations.isEmpty())
  }

  @Test
  fun productScreensDoNotDependOnMainViewModel() {
    val presentationFiles =
      listOf(
        "chat/ChatHeader.kt",
        "chat/ChatScreen.kt",
        "ai/AiSetupRoute.kt",
        "setup/SetupRoute.kt",
        "eligibility/CompatibilityScreen.kt",
        "settings/SettingsHomeScreen.kt",
        "environment/RuntimeEnvironmentScreen.kt",
        "sidebar/SidebarScreen.kt",
      )

    presentationFiles.forEach { relativePath ->
      val source = uiRoot().resolve(relativePath).readText()
      assertFalse("$relativePath must remain presentation-only", source.contains("MainViewModel"))
    }
  }

  @Test
  fun chatUsesOneScreenBoundaryWithoutLegacyFixedPanels() {
    val route = uiRoot().resolve("chat/ChatRoute.kt").readText()
    val removedPanels =
      listOf(
        "ChatPermissionControl",
        "ChatStopStatus",
        "ProgressCardPill",
        "AndroidAppResultRow",
        "WebProjectResultRow",
      )

    assertTrue("ChatRoute must render through ChatScreen", "ChatScreen(" in route)
    removedPanels.forEach { panel ->
      assertFalse("Legacy fixed Chat panel must stay removed: $panel", panel in route)
    }
  }

  @Test
  fun chatPresentationDoesNotDependOnCompositionOwners() {
    val forbiddenOwners = listOf("MainViewModel", "NodeRuntime")
    val violations =
      Files
        .walk(uiRoot().resolve("chat"))
        .use { paths ->
          paths
            .filter { it.extension == "kt" }
            .flatMap { path ->
              val source = path.readText()
              forbiddenOwners
                .filter(source::contains)
                .map { owner -> "${path.fileName}:$owner" }
                .stream()
            }.toList()
        }

    assertTrue("Chat presentation must consume feature contracts only: $violations", violations.isEmpty())
  }

  @Test
  fun chatDoesNotOwnApplicationRuntimeRecovery() {
    val forbiddenRuntimeDependencies =
      listOf(
        "ai.openclaw.app.runtime",
        "RuntimeAction",
        "RuntimeGate",
        "RuntimeEnvironment",
      )
    val violations =
      Files
        .walk(uiRoot().resolve("chat"))
        .use { paths ->
          paths
            .filter { it.extension == "kt" }
            .flatMap { path ->
              val source = path.readText()
              forbiddenRuntimeDependencies
                .filter(source::contains)
                .map { dependency -> "${path.fileName}:$dependency" }
                .stream()
            }.toList()
        }

    assertTrue("Chat must not own app-wide runtime recovery: $violations", violations.isEmpty())
  }

  @Test
  fun aiPresentationDoesNotDependOnCompositionOwners() {
    val forbiddenOwners = listOf("MainViewModel", "NodeRuntime")
    val providerFiles =
      listOf(
        uiRoot().resolve("ai/AiSetupRoute.kt"),
        uiRoot().resolve("settings/AiModelsRoute.kt"),
      )
    val violations =
      providerFiles.flatMap { path ->
        val source = path.readText()
        forbiddenOwners
          .filter(source::contains)
          .map { owner -> "${path.fileName}:$owner" }
      }

    assertTrue("AI presentation must consume its feature contracts only: $violations", violations.isEmpty())
  }

  @Test
  fun aiOwnersDoNotDependOnCompositionOrUi() {
    val sources =
      listOf("AiSetupController.kt")
        .map { mainSourceRoot().resolve("ai/openclaw/app/ai/$it").readText() }
    val forbiddenDependencies = listOf("MainViewModel", "NodeRuntime", "import ai.openclaw.app.ui")

    sources.forEach { source ->
      forbiddenDependencies.forEach { dependency ->
        assertFalse("AI controllers must remain independently constructible: $dependency", source.contains(dependency))
      }
    }
  }

  @Test
  fun extensionPresentationDoesNotDependOnCompositionOwners() {
    val forbiddenOwners = listOf("MainViewModel", "NodeRuntime")
    val violations =
      Files
        .walk(uiRoot().resolve("extensions"))
        .use { paths ->
          paths
            .filter { it.extension == "kt" }
            .flatMap { path ->
              val source = path.readText()
              forbiddenOwners
                .filter(source::contains)
                .map { owner -> "${path.fileName}:$owner" }
                .stream()
            }.toList()
        }

    assertTrue("Extension presentation must consume feature contracts only: $violations", violations.isEmpty())
  }

  @Test
  fun extensionOwnersDoNotDependOnCompositionOrUi() {
    val ownerFiles =
      listOf(
        "plugin/PluginController.kt",
        "skill/SkillController.kt",
        "mcp/McpConfigController.kt",
      )
    val forbiddenDependencies = listOf("MainViewModel", "NodeRuntime", "import ai.openclaw.app.ui")

    ownerFiles.forEach { relativePath ->
      val source = mainSourceRoot().resolve("ai/openclaw/app/$relativePath").readText()
      forbiddenDependencies.forEach { dependency ->
        assertFalse("$relativePath must remain independently constructible: $dependency", source.contains(dependency))
      }
    }
  }

  @Test
  fun runtimeAndTerminalOwnersStayOutsideCompositionAndUi() {
    val ownerFiles =
      listOf(
        "runtime/RuntimeCoordinator.kt",
        "runtime/RuntimeState.kt",
        "terminal/TerminalController.kt",
      )
    val forbiddenDependencies = listOf("MainViewModel", "NodeRuntime", "import ai.openclaw.app.ui")

    ownerFiles.forEach { relativePath ->
      val source = mainSourceRoot().resolve("ai/openclaw/app/$relativePath").readText()
      forbiddenDependencies.forEach { dependency ->
        assertFalse("$relativePath must remain independently constructible: $dependency", source.contains(dependency))
      }
    }
  }

  @Test
  fun runtimeAndTerminalPresentationUseFeatureContracts() {
    val presentationFiles =
      listOf("environment/RuntimeEnvironmentRoute.kt") +
        Files
          .walk(uiRoot().resolve("runtime"))
          .use { paths ->
            paths
              .filter { it.extension == "kt" }
              .map(uiRoot()::relativize)
              .map(Path::toString)
              .toList()
          } +
        Files
          .walk(uiRoot().resolve("terminal"))
          .use { paths ->
            paths
              .filter { it.extension == "kt" }
              .map(uiRoot()::relativize)
              .map(Path::toString)
              .toList()
          }
    val forbiddenOwners = listOf("MainViewModel", "NodeRuntime")

    val violations =
      presentationFiles.flatMap { relativePath ->
        val source = uiRoot().resolve(relativePath).readText()
        forbiddenOwners.filter(source::contains).map { owner -> "$relativePath:$owner" }
      }

    assertTrue("Runtime presentation must consume feature contracts only: $violations", violations.isEmpty())
  }

  @Test
  fun settingsPresentationDoesNotDependOnCompositionOwners() {
    val forbiddenOwners = listOf("MainViewModel", "NodeRuntime")
    val violations =
      Files
        .walk(uiRoot().resolve("settings"))
        .use { paths ->
          paths
            .filter { it.extension == "kt" }
            .flatMap { path ->
              val source = path.readText()
              forbiddenOwners
                .filter(source::contains)
                .map { owner -> "${path.fileName}:$owner" }
                .stream()
            }.toList()
        }

    assertTrue("Settings presentation must consume feature contracts only: $violations", violations.isEmpty())
  }

  @Test
  fun settingsCompositionContractDoesNotOwnRuntimeOrUi() {
    val source =
      mainSourceRoot()
        .resolve("ai/openclaw/app/settings/SettingsFeatures.kt")
        .readText()
    val forbiddenDependencies = listOf("MainViewModel", "NodeRuntime", "import ai.openclaw.app.ui")

    forbiddenDependencies.forEach { dependency ->
      assertFalse("Settings composition must remain a pure feature contract: $dependency", source.contains(dependency))
    }
  }

  @Test
  fun obsoleteAdvancedManagementSupportIsRemovedButPhoneApprovalRemains() {
    val runtime =
      mainSourceRoot()
        .resolve("ai/openclaw/app/NodeRuntime.kt")
        .readText()
    val viewModel =
      mainSourceRoot()
        .resolve("ai/openclaw/app/MainViewModel.kt")
        .readText()
    val pairing =
      mainSourceRoot()
        .resolve("ai/openclaw/app/gateway/LocalGatewayPairing.kt")
        .readText()
    val removedSupport =
      listOf(
        "SkillWorkshop",
        "GatewayNodesDevicesSummary",
        "GatewayDevicePairing",
        "GatewayDreamingSummary",
        "GatewayWorkspaceListing",
        "gatewayAgents",
        "backgroundGatewayStatuses",
        "secondaryOperatorSessions",
      )

    removedSupport.forEach { symbol ->
      assertFalse("Obsolete advanced support must not survive in NodeRuntime: $symbol", runtime.contains(symbol))
      assertFalse("Obsolete forwarding must not survive in MainViewModel: $symbol", viewModel.contains(symbol))
    }
    listOf("entries", "activeStableId").forEach { symbol ->
      assertFalse("The local pairing store must not regain Gateway registry state: $symbol", pairing.contains(symbol))
    }
    listOf("connectedStableIds", "setConnectionEnabled", "BackgroundGatewayFleet").forEach { symbol ->
      assertFalse("The local pairing store must not regain Gateway fleet state: $symbol", pairing.contains(symbol))
      assertFalse("Obsolete background-Gateway runtime support must be removed: $symbol", runtime.contains(symbol))
    }
    assertTrue(runtime.contains("refreshNodeCapabilityApprovalFromGateway"))
    assertTrue(runtime.contains("requestGatewayData(gatewayScope, \"node.list\", \"{}\")"))
  }

  @Test
  fun chatContentBoundaryCannotRegainDeferredRenderers() {
    val sourceRoot = mainSourceRoot().resolve("ai/openclaw/app")
    val removedSources =
      listOf(
        sourceRoot.resolve("chat/ChatContentFeature.kt"),
        sourceRoot.resolve("ui/chat/ChatWidget.kt"),
        sourceRoot.resolve("ui/chat/ChatMediaPlayer.kt"),
        sourceRoot.resolve("ui/chat/ChatMathRenderer.kt"),
        sourceRoot.resolve("ui/chat/ChatMathSegmenter.kt"),
      )
    removedSources.forEach { path ->
      assertFalse("Deferred renderer owner must stay removed: ${path.fileName}", Files.exists(path))
    }
    assertFalse(
      "Bundled KaTeX assets must stay removed",
      Files.exists(androidAppRoot().resolve("src/main/assets/katex")),
    )

    val model =
      sourceRoot
        .resolve("chat/ChatModels.kt")
        .readText()
        .substringAfter("data class ChatMessageContent(")
        .substringBefore("\n)")
    listOf("val url:", "val openUrl:", "val alt:", "val width:", "val height:", "val sizeBytes:").forEach { field ->
      assertFalse("Chat content must not regain renderer-only field $field", model.contains(field))
    }
    assertTrue(model.contains("val artifactId: String?"))
    assertTrue(model.contains("val base64: String?"))

    val parser = sourceRoot.resolve("chat/ChatHistoryCodec.kt").readText()
    listOf("\"canvas\" ->", "\"audio\", \"video\" ->", "MANAGED_MEDIA_PATH_REGEX").forEach { branch ->
      assertFalse("Chat parsing must not regain a deferred-content branch: $branch", parser.contains(branch))
    }
    assertTrue(parser.contains("type = \"unsupported\""))
  }

  @Test
  fun removedProductArtifactsStayRemoved() {
    val cronBenchmark =
      androidAppRoot()
        .toAbsolutePath()
        .normalize()
        .parent
        .resolve("benchmark/src/main/java/ai/openclaw/app/benchmark/CronJobNavigationTest.kt")
    assertFalse("Automations benchmark must stay removed", Files.exists(cronBenchmark))

    val nativeStrings =
      mainSourceRoot()
        .resolve("ai/openclaw/app/i18n/NativeStringResources.kt")
        .readText()
    val removedResourceIds =
      listOf(
        "native_d8cb9e6d596bcb9d",
        "native_05dd0c51d15c5db7",
        "native_6be2e6236d34642d",
        "native_3824a9f4dafe92c6",
        "native_dfe114ba9a41375d",
      )
    removedResourceIds.forEach { resourceId ->
      assertFalse("Removed product resource must stay absent: $resourceId", nativeStrings.contains(resourceId))
    }
  }

  @Test
  fun localNodeServiceDoesNotClaimAnExternalConnectedDevice() {
    val manifest = androidAppRoot().resolve("src/main/AndroidManifest.xml").readText()
    val service = mainSourceRoot().resolve("ai/openclaw/app/NodeForegroundService.kt").readText()

    assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
    assertTrue(manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    assertFalse(manifest.contains("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
    assertFalse(manifest.contains("android:foregroundServiceType=\"connectedDevice\""))
    assertTrue(service.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
    assertFalse(service.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE"))
  }

  @Test
  fun appEntryRequiresAvfAndKeepsEligibilityOutsideBootstrap() {
    val manifest = androidAppRoot().resolve("src/main/AndroidManifest.xml").readText()
    val bootstrap = mainSourceRoot().resolve("ai/openclaw/app/bootstrap")
    val eligibility = mainSourceRoot().resolve("ai/openclaw/app/eligibility/DeviceEligibility.kt")

    assertTrue(manifest.contains("android.software.virtualization_framework"))
    assertTrue(manifest.contains("android:required=\"true\""))
    assertTrue(Files.exists(eligibility))
    assertFalse(Files.exists(bootstrap.resolve("LinuxEnvironment.kt")))
  }

  @Test
  fun onboardingReviewIsDebugOnlyAndDoesNotOwnFirstRunBehavior() {
    val appRoot = androidAppRoot()
    val debugReview =
      appRoot
        .resolve("src/debug/java/ai/openclaw/app/ui/onboardingreview/AndroidOnboardingReview.kt")
        .readText()
    val releaseReview =
      appRoot
        .resolve("src/release/java/ai/openclaw/app/ui/onboardingreview/AndroidOnboardingReview.kt")
        .readText()

    assertTrue(debugReview.contains("androidOnboardingReviewAvailable = true"))
    assertFalse(debugReview.contains("FirstRunCoordinator"))
    assertFalse(debugReview.contains("completeOnboarding"))
    assertFalse(debugReview.contains("launchDeviceEligibilityAction"))
    assertTrue(releaseReview.contains("androidOnboardingReviewAvailable = false"))
    assertTrue(releaseReview.contains("parseAndroidOnboardingReviewScene(intent: Intent?): String? = null"))
  }

  @Test
  fun bootstrapDoesNotDependOnProductUiOrChat() {
    val violations =
      Files
        .walk(mainSourceRoot().resolve("ai/openclaw/app/bootstrap"))
        .use { paths ->
          paths
            .filter { it.extension == "kt" }
            .filter { path ->
              val source = path.readText()
              source.contains("import ai.openclaw.app.ui") ||
                source.contains("import ai.openclaw.app.chat")
            }.map { it.fileName.toString() }
            .toList()
        }

    assertTrue("Bootstrap must not depend on UI or Chat: $violations", violations.isEmpty())
  }

  private fun uiSources(): List<Path> =
    Files.walk(uiRoot()).use { paths ->
      paths.filter { it.extension == "kt" }.toList()
    }

  private fun uiRoot(): Path = mainSourceRoot().resolve("ai/openclaw/app/ui")

  private fun androidAppRoot(): Path {
    val candidates =
      listOf(
        Path.of("."),
        Path.of("apps/android/app"),
      )
    return candidates.firstOrNull { Files.isDirectory(it.resolve("src/main/java")) } ?: error("Android App root not found")
  }

  private fun mainSourceRoot(): Path {
    val candidates =
      listOf(
        Path.of("src/main/java"),
        Path.of("apps/android/app/src/main/java"),
      )
    return candidates.firstOrNull(Files::isDirectory) ?: error("Android main source root not found")
  }

  private companion object {
    val composableFunction =
      Regex(
        pattern =
          "@Composable\\s+(?:(?:internal|private|public)\\s+)?fun\\s+(\\w+)\\s*\\((.*?)\\)\\s*(?::[^\\{]+)?\\{",
        option = RegexOption.DOT_MATCHES_ALL,
      )
  }
}
