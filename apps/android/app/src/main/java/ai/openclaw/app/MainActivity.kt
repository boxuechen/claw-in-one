package ai.openclaw.app

import ai.openclaw.app.i18n.NativeStringResources
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.OpenClawTheme
import ai.openclaw.app.ui.RootRoute
import ai.openclaw.app.ui.onboardingreview.AndroidOnboardingReviewRoute
import ai.openclaw.app.ui.onboardingreview.parseAndroidOnboardingReviewScene
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main Android activity that owns Compose UI attachment and runtime UI wiring.
 */
class MainActivity : AppCompatActivity() {
  private val viewModel: MainViewModel by viewModels()
  private var initializedViewModel: MainViewModel? = null
  private var didStartViewModelCollectors = false
  private var foreground = false
  private val pendingIntentRouter = MainActivityPendingIntentRouter()
  private val runtimeUiStarter = MainActivityRuntimeUiStarter()
  private val mainDisplayPolicy = MainActivityDisplayPolicy()
  private var screenshotLaunch: AndroidScreenshotLaunch? = null
  private var onboardingReviewScene: String? = null

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(NativeStringResources.localizeActivityBaseContext(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    returnTaskToMainDisplay()
    pendingIntentRouter.setInitialIntent(intent)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    screenshotLaunch = parseAndroidScreenshotLaunchIntent(intent)
    onboardingReviewScene = parseAndroidOnboardingReviewScene(intent)
    if (screenshotLaunch != null) {
      hideScreenshotModeStatusBar()
    }

    setContent {
      var reviewScene by remember { mutableStateOf(onboardingReviewScene) }
      var activeViewModel by remember { mutableStateOf<MainViewModel?>(null) }

      if (reviewScene != null) {
        OpenClawTheme {
          AndroidOnboardingReviewRoute(
            initialScene = reviewScene,
            onClose = { reviewScene = null },
            modifier = Modifier.fillMaxSize(),
          )
        }
      } else {
        LaunchedEffect(Unit) {
          withFrameNanos { }
          withContext(Dispatchers.Default) {
            (application as NodeApp).prefs
          }
          val readyViewModel = viewModel
          screenshotLaunch?.let(readyViewModel::enterScreenshotFixture)
          activateViewModel(readyViewModel)
          activeViewModel = readyViewModel
        }

        val currentViewModel = activeViewModel
        if (currentViewModel == null) {
          OpenClawTheme {
            StartupSurface()
          }
        } else {
          val appearanceThemeMode by currentViewModel.appearanceThemeMode.collectAsState()
          val gatewayAccentArgb by currentViewModel.gatewayAccentArgb.collectAsState()
          OpenClawTheme(themeMode = appearanceThemeMode, accentArgb = gatewayAccentArgb) {
            RootRoute(viewModel = currentViewModel)
          }
        }
      }
    }
  }

  private fun hideScreenshotModeStatusBar() {
    WindowCompat
      .getInsetsController(window, window.decorView)
      .apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.statusBars())
      }
  }

  override fun onStart() {
    super.onStart()
    foreground = true
    initializedViewModel?.setForeground(true)
  }

  override fun onResume() {
    super.onResume()
    returnTaskToMainDisplay()
  }

  override fun onConfigurationChanged(newConfiguration: Configuration) {
    super.onConfigurationChanged(newConfiguration)
    returnTaskToMainDisplay()
  }

  override fun onStop() {
    foreground = false
    if (shouldNotifyRuntimeBackgrounded(isChangingConfigurations)) {
      initializedViewModel?.setForeground(false)
    }
    super.onStop()
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    returnTaskToMainDisplay()
    if (intent.action == ACTION_RETURN_TO_MAIN_DISPLAY) return
    setIntent(intent)
    val accepted =
      pendingIntentRouter.onNewIntent(intent) { routedIntent ->
        initializedViewModel?.let { handleLaunchIntent(viewModel = it, intent = routedIntent) }
      }
    if (!accepted) return
  }

  /** ClawInOne owns VScreen and must never become a workload inside its own display. */
  private fun returnTaskToMainDisplay(currentDisplayId: Int = currentActivityDisplayId()) {
    mainDisplayPolicy.ensureDefaultDisplay(currentDisplayId) {
      val options =
        ActivityOptions
          .makeBasic()
          .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
          .toBundle()
      val redirectIntent =
        Intent(this, MainActivity::class.java)
          .setAction(ACTION_RETURN_TO_MAIN_DISPLAY)
          .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_CLEAR_TOP or
              Intent.FLAG_ACTIVITY_SINGLE_TOP,
          )
      startActivity(redirectIntent, options)
    }
  }

  private fun currentActivityDisplayId(): Int =
    runCatching { display?.displayId }
      .getOrNull()
      ?: window.decorView.display?.displayId
      ?: Display.DEFAULT_DISPLAY

  private companion object {
    const val ACTION_RETURN_TO_MAIN_DISPLAY = "ai.openclaw.app.action.RETURN_TO_MAIN_DISPLAY"
  }

  /**
   * Wires MainViewModel only after Activity first draw and background prefs warm-up.
   */
  private fun activateViewModel(readyViewModel: MainViewModel) {
    if (initializedViewModel != null) return
    initializedViewModel = readyViewModel
    readyViewModel.setForeground(foreground)
    startViewModelCollectors(readyViewModel)
    if (!readyViewModel.claimInitialIntentRouting()) {
      pendingIntentRouter.discardInitialIntent()
    }
    pendingIntentRouter.activate { initialIntent ->
      handleLaunchIntent(viewModel = readyViewModel, intent = initialIntent)
    }
    readyViewModel.reportShareLaunchOverflow(pendingIntentRouter.takeShareOverflowCount())
  }

  /**
   * Starts lifecycle collectors after ViewModel construction so they cannot force early startup.
   */
  private fun startViewModelCollectors(readyViewModel: MainViewModel) {
    if (didStartViewModelCollectors) return
    didStartViewModelCollectors = true

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        readyViewModel.preventSleep.collect { enabled ->
          if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          }
        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        readyViewModel.runtimeInitialized.collect { ready ->
          runtimeUiStarter.onRuntimeInitialized(
            ready = ready,
            startRuntimeUi = screenshotLaunch == null,
            startNodeService = {
              NodeForegroundService.start(this@MainActivity)
            },
          )
        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        readyViewModel.shareLaunchOverflowRevision.collect { revision ->
          if (revision == 0L) return@collect
          repeat(readyViewModel.takeShareLaunchOverflowCount()) {
            Toast
              .makeText(
                this@MainActivity,
                nativeString("Too many shares are waiting to be added."),
                Toast.LENGTH_SHORT,
              ).show()
          }
        }
      }
    }
  }

  /**
   * Routes assistant/app-action intents into ViewModel state without recreating the activity.
   */
  private fun handleLaunchIntent(
    viewModel: MainViewModel,
    intent: Intent?,
  ) {
    if (intent?.isShareLaunchIntent() == true) {
      viewModel.handleShareLaunchIntent(intent)
      return
    }
    parseConversationNotificationLaunchIntent(
      intent = intent,
      takeTarget = (application as NodeApp).conversationNotificationLaunchStore::take,
    )?.let { target ->
      viewModel.openConversationNotification(target)
      return
    }
    val request = parseAssistantLaunchIntent(intent) ?: return
    viewModel.handleAssistantLaunch(request)
  }
}

/** Queues shares until ViewModel activation while retaining only the latest ordinary launch intent. */
internal class MainActivityPendingIntentRouter {
  private data class PendingLaunchIntent(
    val sequence: Long,
    val intent: Intent,
    val initial: Boolean,
  )

  private var activated = false
  private var sequence = 0L
  private val pendingShareIntents = ArrayDeque<PendingLaunchIntent>()
  private var pendingNonShareIntent: PendingLaunchIntent? = null
  private var shareOverflowCount = 0

  fun setInitialIntent(intent: Intent?) {
    if (!activated && intent != null) store(intent = intent, initial = true)
  }

  fun onNewIntent(
    intent: Intent,
    routeIntent: (Intent) -> Unit,
  ): Boolean {
    if (activated) {
      routeIntent(intent)
      return true
    }
    return store(intent = intent, initial = false)
  }

  fun discardInitialIntent() {
    if (activated) return
    pendingShareIntents.removeAll { it.initial }
    if (pendingNonShareIntent?.initial == true) pendingNonShareIntent = null
  }

  fun activate(routeIntent: (Intent) -> Unit): Boolean {
    if (activated) return false
    activated = true
    (pendingShareIntents + listOfNotNull(pendingNonShareIntent))
      .sortedBy(PendingLaunchIntent::sequence)
      .forEach { pending -> routeIntent(pending.intent) }
    pendingShareIntents.clear()
    pendingNonShareIntent = null
    return true
  }

  fun takeShareOverflowCount(): Int =
    shareOverflowCount.also {
      shareOverflowCount = 0
    }

  private fun store(
    intent: Intent,
    initial: Boolean,
  ): Boolean {
    val pending = PendingLaunchIntent(sequence = sequence++, intent = intent, initial = initial)
    if (!intent.isShareLaunchIntent()) {
      pendingNonShareIntent = pending
      return true
    }
    if (pendingShareIntents.size >= MAX_PENDING_CHAT_SHARES) {
      shareOverflowCount += 1
      return false
    }
    pendingShareIntents.addLast(pending)
    return true
  }
}

private fun Intent.isShareLaunchIntent(): Boolean = action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE

/** Keeps launch intents one-shot across same-process Activity recreation, but not process death. */
internal class MainActivityInitialIntentGate {
  private var claimed = false

  fun claim(): Boolean {
    if (claimed) return false
    claimed = true
    return true
  }
}

internal fun shouldNotifyRuntimeBackgrounded(isChangingConfigurations: Boolean): Boolean = !isChangingConfigurations

internal class MainActivityDisplayPolicy {
  private var returnInProgress = false

  fun ensureDefaultDisplay(
    currentDisplayId: Int,
    returnTask: () -> Unit,
  ): Boolean {
    if (currentDisplayId == Display.DEFAULT_DISPLAY) {
      returnInProgress = false
      return false
    }
    if (returnInProgress) return false

    returnInProgress = true
    returnTask()
    return true
  }
}

/** Preserves one-shot runtime UI startup while allowing screenshot fixtures to skip side effects. */
internal class MainActivityRuntimeUiStarter {
  private var completed = false

  fun onRuntimeInitialized(
    ready: Boolean,
    startRuntimeUi: Boolean,
    startNodeService: () -> Unit,
  ) {
    if (!ready || completed) return
    if (!startRuntimeUi) {
      completed = true
      return
    }
    completed = true
    startNodeService()
  }
}

@Composable
private fun StartupSurface() {
  Surface(
    modifier = Modifier.fillMaxSize(),
    color = Color.Black,
    contentColor = Color.White,
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "OPENCLAW",
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}
