package ai.openclaw.app.vscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface

internal data class AndroidVScreenDisplayGeometry(
  val width: Int,
  val height: Int,
  val dpi: Int,
  val rotation: Int,
)

internal fun interface AndroidVScreenDisplaySubscription {
  fun close()
}

internal fun interface AndroidVScreenDisplayObserver {
  fun observe(
    displayId: Int,
    onChanged: (AndroidVScreenDisplayGeometry?) -> Unit,
  ): AndroidVScreenDisplaySubscription
}

internal object NoOpAndroidVScreenDisplayObserver : AndroidVScreenDisplayObserver {
  override fun observe(
    displayId: Int,
    onChanged: (AndroidVScreenDisplayGeometry?) -> Unit,
  ): AndroidVScreenDisplaySubscription = AndroidVScreenDisplaySubscription {}
}

internal class SystemAndroidVScreenDisplayObserver(
  context: Context,
) : AndroidVScreenDisplayObserver {
  private val appContext = context.applicationContext
  private val displayManager = appContext.getSystemService(DisplayManager::class.java)
  private val handler = Handler(Looper.getMainLooper())

  override fun observe(
    displayId: Int,
    onChanged: (AndroidVScreenDisplayGeometry?) -> Unit,
  ): AndroidVScreenDisplaySubscription {
    val listener =
      object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(changedDisplayId: Int) {
          if (changedDisplayId == displayId) onChanged(read(displayId))
        }

        override fun onDisplayChanged(changedDisplayId: Int) {
          if (changedDisplayId == displayId) onChanged(read(displayId))
        }

        override fun onDisplayRemoved(changedDisplayId: Int) {
          if (changedDisplayId == displayId) onChanged(null)
        }
      }
    displayManager.registerDisplayListener(listener, handler)
    onChanged(read(displayId))
    return AndroidVScreenDisplaySubscription { displayManager.unregisterDisplayListener(listener) }
  }

  private fun read(displayId: Int): AndroidVScreenDisplayGeometry? {
    val display = displayManager.getDisplay(displayId) ?: return null
    val mode = display.mode
    val rotation = display.rotation
    val rotated = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    return AndroidVScreenDisplayGeometry(
      width = if (rotated) mode.physicalHeight else mode.physicalWidth,
      height = if (rotated) mode.physicalWidth else mode.physicalHeight,
      dpi =
        appContext
          .createDisplayContext(display)
          .resources.displayMetrics.densityDpi,
      rotation = rotation,
    )
  }
}
