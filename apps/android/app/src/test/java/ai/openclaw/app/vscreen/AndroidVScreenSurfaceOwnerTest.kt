package ai.openclaw.app.vscreen

import android.graphics.SurfaceTexture
import android.view.TextureView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidVScreenSurfaceOwnerTest {
  @Test fun recreatedViewKeepsOneSurfaceAndIgnoresOldViewCallbacks() {
    var starts = 0
    var frames = 0
    val visibility = mutableListOf<Boolean>()
    val owner = AndroidVScreenSurfaceOwner({ starts++ }, { visibility += it }, { frames++ })
    val first = TextureView(RuntimeEnvironment.getApplication())
    owner.bind(first)
    val listener = first.surfaceTextureListener!!
    val texture = SurfaceTexture(0)
    listener.onSurfaceTextureAvailable(texture, 720, 1560)
    listener.onSurfaceTextureUpdated(texture)
    assertEquals(1, frames)
    assertFalse(listener.onSurfaceTextureDestroyed(texture))
    assertEquals(listOf(true, false), visibility)
    val replacement = SurfaceTexture(0)
    listener.onSurfaceTextureAvailable(replacement, 720, 1560)
    assertSame(texture, first.surfaceTexture)
    assertFalse(listener.onSurfaceTextureDestroyed(texture))
    visibility.clear()
    val second = TextureView(RuntimeEnvironment.getApplication())
    owner.bind(second)
    assertSame(texture, second.surfaceTexture)
    assertEquals(1, starts)
    assertEquals(listOf(true), visibility)
    assertFalse(listener.onSurfaceTextureDestroyed(texture))
    listener.onSurfaceTextureAvailable(texture, 720, 1560)
    listener.onSurfaceTextureUpdated(texture)
    assertEquals(listOf(true), visibility)
    assertEquals(1, frames)
    second.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
    assertEquals(2, frames)
    owner.close()
    assertTrue(second.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture))
    second.surfaceTextureListener!!.onSurfaceTextureAvailable(texture, 720, 1560)
    second.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
    assertEquals(1, starts)
    assertEquals(2, frames)
  }
}
