package ai.openclaw.app

import ai.openclaw.app.i18n.nativeLocaleChanges
import ai.openclaw.app.i18n.nativeString
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service that keeps the user-visible same-phone Gateway and Node connection alive. */
class NodeForegroundService : Service() {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var notificationJob: Job? = null
  private var runtimeRestoreJob: Job? = null
  private var activeRuntime: NodeRuntime? = null
  private var latestStartId = 0
  private var foregroundStarted = false

  @Volatile private var disconnectRequested = false

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    val initial =
      buildNotification(
        title = nativeString("OpenClaw Node"),
        text = nativeString("Starting…"),
      )
    foregroundStarted =
      try {
        startForegroundWithTypes(notification = initial)
        true
      } catch (err: ForegroundServiceStartNotAllowedException) {
        // Android can recreate a sticky service while the app is backgrounded even though a new
        // foreground-service promotion is no longer permitted. End that stale restart cleanly;
        // the foreground Activity will explicitly resume the service when the user returns.
        Log.w("OpenClawNodeService", "Foreground promotion denied during background restart", err)
        stopSelf()
        false
      }
  }

  private fun startRuntimeIfNeeded(startId: Int) {
    if (activeRuntime != null || runtimeRestoreJob?.isActive == true || disconnectRequested) return
    val app = application as NodeApp
    runtimeRestoreJob =
      scope.launch(Dispatchers.Default) {
        try {
          restoreStickyRuntime(
            createRuntime = app::ensureBackgroundRuntime,
            disconnectRequested = { disconnectRequested },
            disconnectRuntime = NodeRuntime::disconnect,
          ) { restoredRuntime ->
            withContext(Dispatchers.Main) {
              runtimeRestoreJob = null
              if (disconnectRequested) {
                false
              } else {
                activeRuntime = restoredRuntime
                observeRuntime(restoredRuntime)
                true
              }
            }
          }
        } catch (err: CancellationException) {
          throw err
        } catch (err: Throwable) {
          Log.e("OpenClawNodeService", "Failed to restore node runtime", err)
          withContext(Dispatchers.Main) {
            runtimeRestoreJob = null
            if (!disconnectRequested && !stopSelfResult(startId)) {
              startRuntimeIfNeeded(latestStartId)
            }
          }
        }
      }
  }

  private fun observeRuntime(runtime: NodeRuntime) {
    notificationJob =
      scope.launch {
        val notificationStates =
          combine(
            runtime.gatewayConnectionDisplay,
            runtime.serverName,
          ) { connection, server ->
            ServiceNotificationState(
              status = connection.statusText,
              server = server,
              connected = connection.isConnected,
            )
          }
        refreshNotificationOnLocaleChanges(
          states = notificationStates,
          localeChanges = nativeLocaleChanges,
        ).collect { update ->
          ensureChannelForLocaleRevision(update.localeRevision)
          val state = update.state
          val title = if (state.connected) nativeString("OpenClaw Node · Connected") else nativeString("OpenClaw Node")
          val displayStatus = gatewayConnectionStatusForDisplay(state.status)
          val text =
            state.server?.let { nativeString("\$status · \$server", displayStatus, it) } ?: displayStatus

          startForegroundWithTypes(
            notification = buildNotification(title = title, text = text),
          )
        }
      }
  }

  private var channelLocaleRevision: Long? = null

  private fun ensureChannelForLocaleRevision(localeRevision: Long) {
    if (channelLocaleRevision == localeRevision) return
    ensureChannel()
    channelLocaleRevision = localeRevision
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    latestStartId = maxOf(latestStartId, startId)
    if (!foregroundStarted) {
      stopSelfResult(startId)
      return START_NOT_STICKY
    }
    when (intent?.action) {
      ACTION_STOP -> {
        startSuppressed.set(true)
        disconnectRequested = true
        runtimeRestoreJob?.cancel()
        runtimeRestoreJob = null
        notificationJob?.cancel()
        notificationJob = null
        activeRuntime?.disconnect()
        activeRuntime = null
        (application as NodeApp).disconnectRuntimeAsync()
        stopSelfResult(startId)
        return START_NOT_STICKY
      }
      ACTION_RESUME -> {
        startSuppressed.set(false)
        disconnectRequested = false
      }
    }
    if (disconnectRequested || startSuppressed.get()) {
      // A STOP can lose stopSelfResult to a newer queued start. Let the newest
      // start id close the service instead of leaving a disconnected FGS alive.
      stopSelfResult(startId)
      return START_NOT_STICKY
    }
    // START_STICKY recreates the service in a fresh process and calls this with a null intent.
    startRuntimeIfNeeded(startId)
    // Keep running; connection is managed by NodeRuntime (auto-reconnect + manual).
    return START_STICKY
  }

  override fun onDestroy() {
    notificationJob?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?) = null

  private fun ensureChannel() {
    val mgr = getSystemService(NotificationManager::class.java)
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        nativeString("Connection"),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = nativeString("OpenClaw node connection status")
        setShowBadge(false)
      }
    mgr.createNotificationChannel(channel)
  }

  private fun buildNotification(
    title: String,
    text: String,
  ): Notification {
    val launchPending = mainActivityPendingIntent(this, requestCode = 1)
    val stopIntent = Intent(this, NodeForegroundService::class.java).setAction(ACTION_STOP)
    val stopPending =
      PendingIntent.getService(
        this,
        2,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(launchPending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, nativeString("Disconnect"), stopPending)
      .build()
  }

  private fun startForegroundWithTypes(notification: Notification) {
    val foregroundServiceType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        // specialUse was introduced in API 34; older releases require no typed bit.
        0
      }
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      notification,
      foregroundServiceType,
    )
  }

  companion object {
    private const val CHANNEL_ID = "connection"
    private const val NOTIFICATION_ID = 1

    private const val ACTION_STOP = "ai.openclaw.app.action.STOP"
    private const val ACTION_RESUME = "ai.openclaw.app.action.RESUME"
    private val startSuppressed = AtomicBoolean(false)

    fun start(context: Context) {
      if (startSuppressed.get()) return
      val intent = Intent(context, NodeForegroundService::class.java)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      startSuppressed.set(true)
      val intent = Intent(context, NodeForegroundService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }

    internal fun resume(
      context: Context,
      startNow: Boolean,
    ) {
      startSuppressed.set(false)
      if (!startNow) return
      val intent = Intent(context, NodeForegroundService::class.java).setAction(ACTION_RESUME)
      context.startForegroundService(intent)
    }
  }
}

/** Restores process-local state after Android recreates a sticky service in a fresh process. */
internal suspend fun <T> restoreStickyRuntime(
  createRuntime: () -> T,
  disconnectRequested: () -> Boolean,
  disconnectRuntime: (T) -> Unit,
  activateRuntime: suspend (T) -> Boolean,
) {
  // A queued recovery may begin after STOP; do not construct process state once
  // disconnect has already won. The post-create check still closes the race during construction.
  if (disconnectRequested()) return
  val runtime = createRuntime()
  var activated = false
  try {
    if (!disconnectRequested()) {
      activated = activateRuntime(runtime)
    }
  } finally {
    // Ownership transfers only after activation. Stop/cancellation during the
    // dispatcher hop must disconnect the recovered runtime instead of leaking it.
    if (!activated) {
      disconnectRuntime(runtime)
    }
  }
}

/** Connection fields that drive foreground notification title/body text. */
private data class ServiceNotificationState(
  val status: String,
  val server: String?,
  val connected: Boolean,
)

/** Re-emits stable runtime state when app-owned notification copy changes locale. */
internal data class LocaleAwareNotificationState<T>(
  val state: T,
  val localeRevision: Long,
)

internal fun <T> refreshNotificationOnLocaleChanges(
  states: Flow<T>,
  localeChanges: Flow<Long>,
): Flow<LocaleAwareNotificationState<T>> =
  combine(states, localeChanges) { state, localeRevision ->
    LocaleAwareNotificationState(state = state, localeRevision = localeRevision)
  }
