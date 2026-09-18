package ai.openclaw.app.accessibility

import ai.openclaw.app.NodeApp
import ai.openclaw.app.androiduse.AndroidUseControlState
import ai.openclaw.app.androiduse.AndroidUseLeaseController
import ai.openclaw.app.androiduse.AndroidUseRevocation
import ai.openclaw.app.i18n.nativeString
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Reachable, process-local Stop surface shown over the approved target app. */
internal class AndroidUseStopOverlay(
  private val service: OpenClawAccessibilityService,
  private val controller: AndroidUseLeaseController,
  private val scope: CoroutineScope,
  windowContext: Context,
) {
  private val windowManager = windowContext.getSystemService(WindowManager::class.java)
  private var attached = false
  private var receiverRegistered = false
  private var displayListenerRegistered = false
  private val displayManager = windowContext.getSystemService(DisplayManager::class.java)
  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = refreshStopAvailability()

      override fun onDisplayRemoved(displayId: Int) = refreshStopAvailability()

      override fun onDisplayChanged(displayId: Int) = refreshStopAvailability()
    }
  private var stateJob: Job? = null
  private val density = windowContext.resources.displayMetrics.density
  private val keyguardManager = windowContext.getSystemService(KeyguardManager::class.java)
  private val screenReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        when (intent?.action) {
          Intent.ACTION_SCREEN_OFF -> controller.setStopSurfaceAvailable(false)
          Intent.ACTION_USER_PRESENT -> refreshStopAvailability()
          Intent.ACTION_SCREEN_ON -> refreshStopAvailability()
        }
      }
    }
  private val stopView =
    TextView(windowContext).apply {
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)
      textSize = 14f
      minHeight = dp(48)
      setPadding(dp(18), dp(10), dp(18), dp(10))
      elevation = dp(8).toFloat()
      background =
        GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          cornerRadius = dp(24).toFloat()
          setColor(Color.argb(238, 35, 35, 38))
          setStroke(dp(1), Color.argb(110, 255, 255, 255))
        }
      visibility = View.GONE
      setOnClickListener {
        val runtime = (service.application as? NodeApp)?.peekRuntime()
        if (runtime != null) {
          runtime.stopAndroidUseFromSafetySurface()
        } else {
          controller.revoke(AndroidUseRevocation.UserStop)
        }
      }
    }

  fun attach(): Boolean {
    if (attached) return true
    val params =
      WindowManager
        .LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
          PixelFormat.TRANSLUCENT,
        ).apply {
          gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
          y = dp(8)
        }
    val added = runCatching { windowManager.addView(stopView, params) }.isSuccess
    attached = added
    if (!added) return false
    receiverRegistered =
      runCatching {
        ContextCompat.registerReceiver(
          service,
          screenReceiver,
          IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
          },
          ContextCompat.RECEIVER_NOT_EXPORTED,
        )
      }.isSuccess
    displayListenerRegistered =
      runCatching {
        checkNotNull(displayManager).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
      }.isSuccess
    refreshStopAvailability()
    if (!receiverRegistered || !displayListenerRegistered) {
      detach()
      return false
    }
    stateJob =
      scope.launch {
        controller.state.collectLatest { state ->
          when (state) {
            is AndroidUseControlState.Active -> {
              stopView.text =
                TextUtils.concat(
                  service.nativeString("Stop"),
                  " · ",
                  state.targetPackage.substringAfterLast('.'),
                )
              stopView.contentDescription =
                TextUtils.concat(
                  service.nativeString("Stop"),
                  " ",
                  state.targetPackage,
                )
              stopView.visibility = View.VISIBLE
            }
            is AndroidUseControlState.Inactive -> stopView.visibility = View.GONE
          }
        }
      }
    return true
  }

  fun detach() {
    stateJob?.cancel()
    stateJob = null
    controller.setStopSurfaceAvailable(false)
    if (receiverRegistered) {
      receiverRegistered = false
      runCatching { service.unregisterReceiver(screenReceiver) }
    }
    if (displayListenerRegistered) {
      displayListenerRegistered = false
      runCatching { displayManager?.unregisterDisplayListener(displayListener) }
    }
    if (!attached) return
    attached = false
    runCatching { windowManager.removeViewImmediate(stopView) }
  }

  private fun refreshStopAvailability() {
    // VScreen can keep global wakefulness true after the phone screen turns off.
    // Stop must be reachable on the actual main display, independently of virtual displays.
    val mainScreenOn = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
    controller.setStopSurfaceAvailable(
      attached && receiverRegistered && displayListenerRegistered && mainScreenOn && keyguardManager?.isDeviceLocked != true,
    )
  }

  private fun dp(value: Int): Int = (value * density).toInt()

  companion object {
    fun create(
      service: OpenClawAccessibilityService,
      controller: AndroidUseLeaseController,
      scope: CoroutineScope,
    ): AndroidUseStopOverlay? {
      val appContext = service.applicationContext
      val display =
        appContext
          .getSystemService(DisplayManager::class.java)
          ?.getDisplay(Display.DEFAULT_DISPLAY)
          ?: return null
      val windowContext =
        runCatching {
          service.createWindowContext(
            display,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null,
          )
        }.getOrNull() ?: return null
      return AndroidUseStopOverlay(service, controller, scope, windowContext)
    }
  }
}
