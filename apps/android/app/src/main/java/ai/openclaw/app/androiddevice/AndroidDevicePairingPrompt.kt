package ai.openclaw.app.androiddevice

import ai.openclaw.app.MainActivity
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeForegroundService
import ai.openclaw.app.R
import ai.openclaw.app.i18n.nativeString
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val actionReplyAndroidDevicePairing =
  "ai.openclaw.app.action.REPLY_ANDROID_DEVICE_PAIRING"

private const val remoteInputPairingDetails =
  "ai.openclaw.app.remote_input.ANDROID_DEVICE_PAIRING_DETAILS"
private const val pairingChannelId = "claw-in-one.android-device.pairing"
private const val pairingNotificationId = 2
private const val pairingRequestCode = 2
private const val pairingNotificationTimeoutMs = 120_000L
private const val pairingResultTimeoutMs = 45_000L

internal data class AndroidDevicePairingReply(
  val endpoint: String,
  val pairingCode: String,
)

internal fun parseAndroidDevicePairingReply(value: CharSequence?): AndroidDevicePairingReply? {
  val parts = value?.toString()?.trim()?.split(Regex("\\s+")) ?: return null
  if (parts.size != 2) return null
  val endpoint = parts[0]
  val pairingCode = parts[1]
  if (endpoint.length !in 3..300 || pairingCode.matches(Regex("[0-9]{6}")).not()) return null
  val portSeparator = endpoint.lastIndexOf(':')
  if (portSeparator <= 0 || portSeparator == endpoint.lastIndex) return null
  val port = endpoint.substring(portSeparator + 1).toIntOrNull() ?: return null
  if (port !in 1..65_535 || endpoint.any(Char::isWhitespace)) return null
  return AndroidDevicePairingReply(endpoint = endpoint, pairingCode = pairingCode)
}

internal fun androidDevicePairingReplyIntent(context: Context): Intent =
  Intent(context, AndroidDevicePairingReplyReceiver::class.java)
    .setAction(actionReplyAndroidDevicePairing)
    .setData(
      Uri
        .Builder()
        .scheme("clawinone")
        .authority("android-device")
        .appendPath("pair")
        .build(),
    )

internal fun isAndroidDevicePairingReplyIntent(intent: Intent?): Boolean =
  intent?.action == actionReplyAndroidDevicePairing &&
    intent.data == androidDevicePairingReplyIntentData()

private fun androidDevicePairingReplyIntentData(): Uri =
  Uri
    .Builder()
    .scheme("clawinone")
    .authority("android-device")
    .appendPath("pair")
    .build()

/** Owns the one-time system notification used while Android's pairing dialog stays visible. */
internal class AndroidDevicePairingPrompt(
  context: Context,
) {
  private val appContext = context.applicationContext
  private val manager = appContext.getSystemService(NotificationManager::class.java)

  @SuppressLint("MissingPermission")
  fun show(): Boolean {
    ensureChannel()
    if (!canPost()) return false
    manager.notify(pairingNotificationId, promptNotification())
    return true
  }

  @SuppressLint("MissingPermission")
  fun showInvalidReply(): Boolean {
    ensureChannel()
    if (!canPost()) return false
    manager.notify(
      pairingNotificationId,
      promptNotification(
        title = nativeString("Check the pairing details"),
        body = nativeString("Reply with the IPv4 pairing address and six-digit code, separated by a space."),
      ),
    )
    return true
  }

  @SuppressLint("MissingPermission")
  fun showPairing() {
    if (!canPost()) return
    manager.notify(
      pairingNotificationId,
      baseBuilder(
        title = nativeString("Pairing this phone…"),
        body = nativeString("Keep Android's pairing dialog open."),
      ).setProgress(0, 0, true).setTimeoutAfter(pairingResultTimeoutMs).build(),
    )
  }

  @SuppressLint("MissingPermission")
  fun showFailure() {
    if (!canPost()) return
    val contentIntent =
      PendingIntent.getActivity(
        appContext,
        pairingRequestCode,
        Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    manager.notify(
      pairingNotificationId,
      baseBuilder(
        title = nativeString("Pairing was not completed"),
        body = nativeString("Return to ClawInOne and start again with a new pairing code."),
      ).setContentIntent(contentIntent).setAutoCancel(true).build(),
    )
  }

  fun cancel() {
    manager.cancel(pairingNotificationId)
  }

  private fun promptNotification(
    title: String = nativeString("Pair this phone"),
    body: String = nativeString("Keep Android's pairing dialog open, then reply here with its address and code."),
  ): Notification {
    val replyIntent =
      PendingIntent.getBroadcast(
        appContext,
        pairingRequestCode,
        androidDevicePairingReplyIntent(appContext),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
      )
    val remoteInput =
      RemoteInput
        .Builder(remoteInputPairingDetails)
        .setLabel(nativeString("10.0.0.2:12345 123456"))
        .build()
    val replyAction =
      NotificationCompat.Action
        .Builder(0, nativeString("Enter pairing details"), replyIntent)
        .addRemoteInput(remoteInput)
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        .build()
    return baseBuilder(title, body)
      .addAction(replyAction)
      .setOngoing(true)
      .setTimeoutAfter(pairingNotificationTimeoutMs)
      .build()
  }

  private fun baseBuilder(
    title: String,
    body: String,
  ): NotificationCompat.Builder =
    NotificationCompat
      .Builder(appContext, pairingChannelId)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setCategory(NotificationCompat.CATEGORY_SYSTEM)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
      .setOnlyAlertOnce(true)

  private fun canPost(): Boolean =
    (
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) &&
      NotificationManagerCompat.from(appContext).areNotificationsEnabled()

  private fun ensureChannel() {
    manager.createNotificationChannel(
      NotificationChannel(
        pairingChannelId,
        nativeString("Android development setup"),
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = nativeString("One-time prompts used to pair the Linux development environment with this phone.")
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        setShowBadge(false)
      },
    )
  }
}

class AndroidDevicePairingReplyReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    if (!isAndroidDevicePairingReplyIntent(intent)) return
    val prompt = AndroidDevicePairingPrompt(context)
    val reply =
      parseAndroidDevicePairingReply(
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputPairingDetails),
      )
    if (reply == null) {
      prompt.showInvalidReply()
      return
    }
    prompt.showPairing()
    val app = context.applicationContext as? NodeApp ?: return
    val pendingResult = goAsync()
    runCatching { NodeForegroundService.resume(context, startNow = true) }
    app.launchRuntimeTask {
      try {
        val runtime = app.ensureBackgroundRuntime()
        runtime.pairAndroidDeviceConnection(reply.endpoint, reply.pairingCode)
        val result =
          withTimeoutOrNull(pairingResultTimeoutMs) {
            runtime.androidDeviceConnectionState.first { state ->
              state.operation == null &&
                (state.snapshot?.status == AndroidDeviceStatus.Ready || state.notice != null)
            }
          }
        if (result?.snapshot?.status == AndroidDeviceStatus.Ready) {
          prompt.cancel()
        } else {
          prompt.showFailure()
        }
      } finally {
        pendingResult.finish()
      }
    }
  }
}
