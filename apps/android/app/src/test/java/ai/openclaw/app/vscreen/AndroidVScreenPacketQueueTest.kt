package ai.openclaw.app.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVScreenPacketQueueTest {
  @Test
  fun overloadDropsBacklogAndResumesFromCodecConfigAndKeyFrame() {
    val queue = AndroidVScreenPacketQueue(capacity = 3)
    val config = packet(1, codecConfig = true)
    val staleA = packet(2)
    val staleB = packet(3)
    val overflow = packet(4)
    val droppedWhileResyncing = packet(5)
    val keyFrame = packet(6, keyFrame = true)

    queue.offer(config)
    queue.offer(staleA)
    queue.offer(staleB)
    queue.offer(overflow)

    assertEquals(0, queue.size)
    assertTrue(queue.isAwaitingKeyFrame)

    queue.offer(droppedWhileResyncing)
    assertEquals(0, queue.size)

    queue.offer(keyFrame)
    assertFalse(queue.isAwaitingKeyFrame)
    assertEquals(2, queue.size)
    assertSame(config, queue.removeFirstOrNull())
    assertSame(keyFrame, queue.removeFirstOrNull())
  }

  @Test
  fun keyFrameAtOverflowBoundaryResynchronizesImmediately() {
    val queue = AndroidVScreenPacketQueue(capacity = 2)
    val config = packet(1, codecConfig = true)
    val stale = packet(2)
    val keyFrame = packet(3, keyFrame = true)

    queue.offer(config)
    queue.offer(stale)
    queue.offer(keyFrame)

    assertFalse(queue.isAwaitingKeyFrame)
    assertEquals(2, queue.size)
    assertSame(config, queue.removeFirstOrNull())
    assertSame(keyFrame, queue.removeFirstOrNull())
  }

  private fun packet(
    marker: Int,
    codecConfig: Boolean = false,
    keyFrame: Boolean = false,
  ) = AndroidVScreenVideoPacket(
    bytes = byteArrayOf(marker.toByte()),
    presentationTimeUs = marker.toLong(),
    codecConfig = codecConfig,
    keyFrame = keyFrame,
  )
}
