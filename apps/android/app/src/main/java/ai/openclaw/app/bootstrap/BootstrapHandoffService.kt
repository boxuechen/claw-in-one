package ai.openclaw.app.bootstrap

import ai.openclaw.app.NodeApp
import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.mainActivityPendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps signed Bootstrap event monitoring alive while Terminal installs the Supervisor. */
class BootstrapHandoffService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var stateJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    publishForegroundNotification(nativeString("Waiting for Debian"))
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    val controller = (application as NodeApp).bootstrapHandoff
    controller.start()
    if (stateJob?.isActive != true) {
      stateJob =
        scope.launch {
          controller.state.collect { state ->
            when (state) {
              BootstrapHandoffState.Stopped -> Unit
              BootstrapHandoffState.Starting ->
                publishForegroundNotification(nativeString("Preparing secure handoff"))
              BootstrapHandoffState.Waiting ->
                publishForegroundNotification(nativeString("Waiting for the Terminal bootstrap"))
              is BootstrapHandoffState.Progress ->
                publishForegroundNotification(bootstrapNotificationText(state.stage))
              is BootstrapHandoffState.Ready,
              is BootstrapHandoffState.Failed,
              -> {
                ServiceCompat.stopForeground(
                  this@BootstrapHandoffService,
                  ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf()
              }
            }
          }
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?) = null

  private fun ensureChannel() {
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        nativeString("ClawInOne setup"),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = nativeString("One-time local service setup")
        setShowBadge(false)
      }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun publishForegroundNotification(text: String) {
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      buildNotification(text),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun buildNotification(text: String): Notification =
    NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(nativeString("ClawInOne setup"))
      .setContentText(text)
      .setContentIntent(mainActivityPendingIntent(this, requestCode = NOTIFICATION_ID))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()

  companion object {
    private const val CHANNEL_ID = "claw_in_one_bootstrap"
    private const val NOTIFICATION_ID = 7302

    fun start(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, BootstrapHandoffService::class.java),
      )
    }
  }
}

internal fun bootstrapNotificationText(stage: SupervisorProgress): String =
  when (stage) {
    SupervisorProgress.BootstrapExecuted -> nativeString("Debian connected")
    SupervisorProgress.InstallingSupervisor -> nativeString("Installing the local service")
    SupervisorProgress.StartingSupervisor -> nativeString("Starting the local service")
  }
