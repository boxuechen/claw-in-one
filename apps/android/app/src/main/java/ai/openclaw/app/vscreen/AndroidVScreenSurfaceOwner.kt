package ai.openclaw.app.vscreen

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/** Session-owned rendering resources. No Activity or View survives presentation detachment. */
internal class AndroidVScreenSurfaceOwner(
  private val onSurface: (Surface) -> Unit,
  private val onVisible: (Boolean) -> Unit,
  private val onFrame: () -> Unit = {},
) {
  private var texture: SurfaceTexture? = null
  private var surface: Surface? = null
  private var viewToken: Any? = null
  private var closed = false

  @Synchronized
  fun bind(view: TextureView) {
    if (closed) return
    val token = Any()
    viewToken = token
    view.surfaceTextureListener =
      object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
          value: SurfaceTexture,
          width: Int,
          height: Int,
        ) {
          synchronized(this@AndroidVScreenSurfaceOwner) {
            if (closed || (viewToken != null && viewToken !== token)) return
            // The same TextureView may receive a new texture after a window detach.
            val retained = texture
            if (retained != null && retained !== value) view.setSurfaceTexture(retained)
            attach(token, retained ?: value)
          }
        }

        override fun onSurfaceTextureSizeChanged(
          value: SurfaceTexture,
          width: Int,
          height: Int,
        ) = Unit

        override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
          val current = synchronized(this@AndroidVScreenSurfaceOwner) { !closed && texture === value && viewToken === token }
          if (current) onFrame()
        }

        override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean = detach(token, value)
      }
    texture?.let {
      view.setSurfaceTexture(it)
      // setSurfaceTexture does not call onSurfaceTextureAvailable.
      attach(token, it)
    }
  }

  @Synchronized
  internal fun attach(
    token: Any,
    value: SurfaceTexture,
  ) {
    if (closed || (viewToken != null && viewToken !== token)) return
    viewToken = token
    if (texture == null) {
      texture = value
      Surface(value).also {
        surface = it
        onSurface(it)
      }
    }
    if (texture === value) onVisible(true)
  }

  /** false transfers texture lifetime to this session, including while the App is hidden. */
  @Synchronized
  internal fun detach(
    token: Any,
    value: SurfaceTexture,
  ): Boolean {
    if (closed || texture !== value) return true
    if (viewToken === token) {
      onVisible(false)
    }
    return false
  }

  @Synchronized
  fun close() {
    if (closed) return
    closed = true
    viewToken = null
    surface?.release()
    surface = null
    texture?.release()
    texture = null
  }
}
