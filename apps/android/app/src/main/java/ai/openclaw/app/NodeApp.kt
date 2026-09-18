package ai.openclaw.app

import ai.openclaw.app.androiduse.AndroidUseLeaseController
import ai.openclaw.app.appdelivery.DeviceHandoffGate
import ai.openclaw.app.bootstrap.BootstrapDeliveryController
import ai.openclaw.app.bootstrap.BootstrapHandoffController
import ai.openclaw.app.bootstrap.MediaStoreBootstrapHandoffRepository
import ai.openclaw.app.clawhub.ClawHubArtworkLoader
import ai.openclaw.app.clawhub.ClawHubHttpClientFactory
import ai.openclaw.app.clawhub.OkHttpClawHubApiClient
import ai.openclaw.app.eligibility.AndroidDeviceEligibilityController
import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.i18n.notifyNativeLocaleChanged
import ai.openclaw.app.plugin.catalog.ClawHubPluginCatalogRepository
import ai.openclaw.app.plugin.catalog.PluginDirectoryActions
import ai.openclaw.app.plugin.catalog.PluginDirectoryController
import ai.openclaw.app.plugin.catalog.PluginDirectoryFeature
import ai.openclaw.app.skill.catalog.ClawHubSkillCatalogRepository
import ai.openclaw.app.skill.catalog.SkillDirectoryActions
import ai.openclaw.app.skill.catalog.SkillDirectoryController
import ai.openclaw.app.skill.catalog.SkillDirectoryFeature
import ai.openclaw.app.supervisor.MediaStoreSupervisorControlRepository
import ai.openclaw.app.supervisor.SupervisorActivation
import ai.openclaw.app.supervisor.SupervisorControlController
import ai.openclaw.app.vscreen.VScreenTargetRegistry
import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.StrictMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Android Application singleton that owns process-wide secure prefs and lazy NodeRuntime startup.
 */
class NodeApp : Application() {
  internal val vscreenPresentation =
    ai.openclaw.app.ui.vscreen
      .VScreenPresentationCoordinator()
  internal val chatLaunchState =
    ai.openclaw.app.ui.shell
      .ChatLaunchState()
  val prefs: SecurePrefs by lazy { SecurePrefs(this) }
  internal val deviceEligibility by lazy { AndroidDeviceEligibilityController(this) }
  internal val bootstrapHandoffRepository by lazy {
    MediaStoreBootstrapHandoffRepository(this, prefs)
  }
  internal val supervisorControlRepository by lazy {
    MediaStoreSupervisorControlRepository(this, prefs)
  }
  internal val supervisorControl by lazy {
    SupervisorControlController(supervisorControlRepository)
  }
  internal val bootstrapHandoff by lazy {
    BootstrapHandoffController(
      repository = bootstrapHandoffRepository,
      activateSupervisor = { handoff ->
        supervisorControlRepository.activate(
          SupervisorActivation(
            supervisorId = handoff.requestId,
            secretHex = handoff.supervisorSecretHex,
            commandUri = handoff.supervisorCommandUri,
            statusUri = handoff.supervisorStatusUri,
          ),
        )
      },
    )
  }
  internal val bootstrapDelivery by lazy {
    BootstrapDeliveryController(this, bootstrapHandoffRepository)
  }

  // System share senders can create overlapping Activity tasks; keep one bounded process queue.
  internal val chatShareDraftSeq = AtomicLong()
  internal val chatShareDraftQueue = ChatShareDraftQueue()
  internal val conversationNotificationLaunchStore = ConversationNotificationLaunchStore()

  private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  internal val deviceHandoffGate by lazy { DeviceHandoffGate() }
  internal val androidUseLeaseController by lazy {
    AndroidUseLeaseController(
      runtimeScope,
      consentGranted = { prefs.androidUseConsent.value },
      handoffBlocked = deviceHandoffGate::isActive,
    )
  }
  internal val vscreenTargets by lazy {
    VScreenTargetRegistry { retired -> androidUseLeaseController.revokeVScreen(retired) }
  }
  private val clawHubHttpClient by lazy { ClawHubHttpClientFactory.create(cacheDir) }
  private val clawHubApi by lazy {
    OkHttpClawHubApiClient(
      httpClient = clawHubHttpClient,
      userAgent = "ClawInOne/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})",
    )
  }
  internal val clawHubArtworkLoader by lazy { ClawHubArtworkLoader(clawHubHttpClient) }
  internal val pluginDirectory by lazy {
    PluginDirectoryController(runtimeScope, ClawHubPluginCatalogRepository(clawHubApi))
  }
  internal val pluginDirectoryFeature by lazy {
    PluginDirectoryFeature(
      state = pluginDirectory.state,
      actions =
        PluginDirectoryActions(
          refresh = pluginDirectory::refresh,
          search = pluginDirectory::search,
          loadNextPage = pluginDirectory::loadNextPage,
        ),
      loadArtwork = { packageName ->
        val directory = pluginDirectory.state.value
        val iconUrl =
          (directory.items + directory.searchResults.map { it.plugin })
            .firstOrNull { it.packageName.value == packageName }
            ?.iconUrl
            ?: return@PluginDirectoryFeature null
        clawHubArtworkLoader.load(iconUrl)
      },
    )
  }
  internal val skillDirectory by lazy {
    SkillDirectoryController(runtimeScope, ClawHubSkillCatalogRepository(clawHubApi))
  }
  internal val skillDirectoryFeature by lazy {
    SkillDirectoryFeature(
      state = skillDirectory.state,
      actions =
        SkillDirectoryActions(
          refresh = skillDirectory::refresh,
          search = skillDirectory::search,
          loadNextPage = skillDirectory::loadNextPage,
        ),
    )
  }
  private val runtimeLock = Any()
  private var runtimeInstance: NodeRuntime? = null

  /**
   * Returns the single NodeRuntime for this process, creating it on first use.
   */
  fun ensureRuntime(): NodeRuntime =
    synchronized(runtimeLock) {
      runtimeInstance ?: NodeRuntime(this, prefs).also { runtimeInstance = it }
    }

  /** Creates a cold-process runtime with foreground-only capabilities disabled before publication. */
  internal fun ensureBackgroundRuntime(): NodeRuntime =
    synchronized(runtimeLock) {
      runtimeInstance
        ?: NodeRuntime(this, prefs, initialForeground = false).also { runtimeInstance = it }
    }

  internal fun ensureScreenshotFixtureRuntime(fixture: AndroidScreenshotRuntimeFixture): NodeRuntime =
    synchronized(runtimeLock) {
      runtimeInstance?.also { runtime ->
        check(runtime.mode == NodeRuntimeMode.ScreenshotFixture) {
          "NodeRuntime already started in live mode"
        }
      } ?: NodeRuntime.forScreenshotFixture(this, prefs, fixture).also { runtimeInstance = it }
    }

  /**
   * Reads the runtime without forcing startup, used by lifecycle probes and services.
   */
  fun peekRuntime(): NodeRuntime? = synchronized(runtimeLock) { runtimeInstance }

  /** Disconnects the current or concurrently constructing runtime without blocking the caller. */
  internal fun disconnectRuntimeAsync() {
    // The process-owned scope outlives a stopping service, so cancellation cannot
    // strand an Activity-created runtime that the service has not observed yet.
    runtimeScope.launch { peekRuntime()?.disconnect() }
  }

  internal fun launchRuntimeTask(block: suspend () -> Unit) {
    runtimeScope.launch { block() }
  }

  /** Clears pairing auth without racing lazy process-runtime construction. */
  suspend fun resetGatewaySetupAuth(stableId: String): Boolean {
    val runtime =
      synchronized(runtimeLock) {
        runtimeInstance
          ?: NodeRuntime.forGatewayAuthReset(this, prefs).also { runtimeInstance = it }
      }
    return runtime.resetGatewaySetupAuth(stableId)
  }

  override fun onCreate() {
    super.onCreate()
    ensureDefaultAppLanguage(this)
    if (BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy
          .Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
      StrictMode.setVmPolicy(
        StrictMode.VmPolicy
          .Builder()
          .detectAll()
          .penaltyLog()
          .build(),
      )
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    // The process runtime survives Activity recreation, so retained text needs an
    // explicit locale refresh signal.
    NativeStringResources.setConfigurationLocales(newConfig)
    notifyNativeLocaleChanged()
  }
}
