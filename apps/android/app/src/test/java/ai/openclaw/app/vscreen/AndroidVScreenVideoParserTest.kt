package ai.openclaw.app.vscreen

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVScreenVideoParserTest {
  @Test
  fun parsesChunkedScrcpyMetadataAndFrames() {
    val codecConfig = byteArrayOf(0, 0, 0, 1, 0x67)
    val keyFrame = byteArrayOf(0, 0, 0, 1, 0x65)
    val stream =
      Buffer()
        .writeInt(H264_CODEC_ID)
        .writeInt(Int.MIN_VALUE)
        .writeInt(1080)
        .writeInt(2400)
        .writeLong(1L shl 62)
        .writeInt(codecConfig.size)
        .write(codecConfig)
        .writeLong((1L shl 61) or 42L)
        .writeInt(keyFrame.size)
        .write(keyFrame)
        .readByteArray()
    val parser = AndroidVScreenVideoParser(1080, 2400)

    assertTrue(parser.append(stream.copyOfRange(0, 3)).isEmpty())
    assertTrue(parser.append(stream.copyOfRange(3, 14)).isEmpty())
    val packets = parser.append(stream.copyOfRange(14, stream.size))

    assertEquals(2, packets.size)
    assertArrayEquals(codecConfig, packets[0].bytes)
    assertTrue(packets[0].codecConfig)
    assertFalse(packets[0].keyFrame)
    assertArrayEquals(keyFrame, packets[1].bytes)
    assertFalse(packets[1].codecConfig)
    assertTrue(packets[1].keyFrame)
    assertEquals(42L, packets[1].presentationTimeUs)
  }

  @Test
  fun rejectsMismatchedMetadataAndOversizedPackets() {
    val wrongSize =
      Buffer()
        .writeInt(H264_CODEC_ID)
        .writeInt(Int.MIN_VALUE)
        .writeInt(720)
        .writeInt(1280)
        .readByteArray()
    assertThrows(IllegalStateException::class.java) {
      AndroidVScreenVideoParser(1080, 2400).append(wrongSize)
    }

    val oversized =
      Buffer()
        .writeInt(H264_CODEC_ID)
        .writeInt(Int.MIN_VALUE)
        .writeInt(1080)
        .writeInt(2400)
        .writeLong(0)
        .writeInt(4 * 1024 * 1024 + 1)
        .readByteArray()
    assertThrows(IllegalStateException::class.java) {
      AndroidVScreenVideoParser(1080, 2400).append(oversized)
    }
  }

  private companion object {
    const val H264_CODEC_ID = 0x68323634
  }
}
