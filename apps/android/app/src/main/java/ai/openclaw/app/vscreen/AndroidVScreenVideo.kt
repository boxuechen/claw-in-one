package ai.openclaw.app.vscreen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean

private const val SCRCPY_H264_CODEC_ID = 0x68323634
private const val SCRCPY_SESSION_FLAG = Long.MIN_VALUE
private const val SCRCPY_PACKET_CONFIG_FLAG = 1L shl 62
private const val SCRCPY_PACKET_KEY_FRAME_FLAG = 1L shl 61
private const val SCRCPY_PACKET_PTS_MASK = SCRCPY_PACKET_KEY_FRAME_FLAG - 1
private const val MAX_VSCREEN_PACKET_BYTES = 4 * 1024 * 1024
private const val MAX_VSCREEN_PENDING_BYTES = MAX_VSCREEN_PACKET_BYTES * 2L
private const val MAX_DECODER_QUEUE_SIZE = 32

internal data class AndroidVScreenVideoPacket(
  val bytes: ByteArray,
  val presentationTimeUs: Long,
  val codecConfig: Boolean,
  val keyFrame: Boolean,
)

/**
 * A live preview must prefer current frames over an ever-growing backlog.
 *
 * Dropping arbitrary H.264 inter frames would leave the decoder on an invalid
 * dependency chain, so overload clears the stale backlog and waits for the next
 * key frame. Codec configuration is retained and replayed before that key frame.
 */
internal class AndroidVScreenPacketQueue(
  private val capacity: Int = MAX_DECODER_QUEUE_SIZE,
) {
  private val pending = ArrayDeque<AndroidVScreenVideoPacket>()
  private var codecConfig: AndroidVScreenVideoPacket? = null
  private var awaitingKeyFrame = false

  init {
    require(capacity > 1) { "VScreen decoder queue capacity must be greater than one" }
  }

  val size: Int
    get() = pending.size

  val isAwaitingKeyFrame: Boolean
    get() = awaitingKeyFrame

  fun offer(packet: AndroidVScreenVideoPacket) {
    if (packet.codecConfig) codecConfig = packet

    if (awaitingKeyFrame) {
      if (!packet.keyFrame) return
      enqueueResync(packet)
      return
    }

    if (pending.size >= capacity) {
      pending.clear()
      awaitingKeyFrame = true
      if (packet.keyFrame) enqueueResync(packet)
      return
    }

    pending.addLast(packet)
  }

  fun removeFirstOrNull(): AndroidVScreenVideoPacket? = pending.removeFirstOrNull()

  fun clear() {
    pending.clear()
    codecConfig = null
    awaitingKeyFrame = false
  }

  private fun enqueueResync(keyFrame: AndroidVScreenVideoPacket) {
    pending.clear()
    codecConfig?.takeUnless { it === keyFrame }?.let(pending::addLast)
    pending.addLast(keyFrame)
    awaitingKeyFrame = false
  }
}

internal class AndroidVScreenVideoParser(
  private val expectedWidth: Int,
  private val expectedHeight: Int,
) {
  private val buffer = Buffer()
  private var codecRead = false
  private var sessionRead = false

  fun append(bytes: ByteArray): List<AndroidVScreenVideoPacket> {
    if (bytes.isEmpty()) return emptyList()
    if (buffer.size + bytes.size > MAX_VSCREEN_PENDING_BYTES) {
      throw IllegalStateException("Android VScreen stream exceeded its buffer limit")
    }
    buffer.write(bytes)
    if (!codecRead) {
      if (buffer.size < 4L) return emptyList()
      val codecId = buffer.readInt()
      if (codecId != SCRCPY_H264_CODEC_ID) throw IllegalStateException("Android VScreen stream codec was unsupported")
      codecRead = true
    }

    val packets = mutableListOf<AndroidVScreenVideoPacket>()
    while (buffer.size >= 12L) {
      val header = buffer.peek()
      val timestampAndFlags = header.readLong()
      val packetSize = header.readInt().toLong() and 0xffff_ffffL
      if (timestampAndFlags and SCRCPY_SESSION_FLAG != 0L) {
        val width = (timestampAndFlags and 0xffff_ffffL).toInt()
        val height = packetSize.toInt()
        if (width != expectedWidth || height != expectedHeight) {
          throw IllegalStateException("Android VScreen stream dimensions changed unexpectedly")
        }
        buffer.skip(12L)
        sessionRead = true
        continue
      }
      if (!sessionRead) throw IllegalStateException("Android VScreen stream omitted its session header")
      if (packetSize <= 0L || packetSize > MAX_VSCREEN_PACKET_BYTES) {
        throw IllegalStateException("Android VScreen packet size was invalid")
      }
      if (buffer.size < 12L + packetSize) break
      buffer.skip(12L)
      val codecConfig = timestampAndFlags and SCRCPY_PACKET_CONFIG_FLAG != 0L
      val payload = buffer.readByteArray(packetSize)
      packets +=
        AndroidVScreenVideoPacket(
          bytes = payload,
          presentationTimeUs = timestampAndFlags and SCRCPY_PACKET_PTS_MASK,
          codecConfig = codecConfig,
          keyFrame = timestampAndFlags and SCRCPY_PACKET_KEY_FRAME_FLAG != 0L,
        )
    }
    return packets
  }
}

internal fun interface AndroidVScreenVideoSinkFactory {
  fun create(
    surface: Surface,
    width: Int,
    height: Int,
    onFrame: () -> Unit,
    onFailure: (String) -> Unit,
  ): AndroidVScreenVideoSink
}

internal interface AndroidVScreenVideoSink {
  fun submit(packet: AndroidVScreenVideoPacket)

  fun setRenderingEnabled(enabled: Boolean) {}

  fun close()
}

internal object AndroidMediaCodecVScreenSinkFactory : AndroidVScreenVideoSinkFactory {
  override fun create(
    surface: Surface,
    width: Int,
    height: Int,
    onFrame: () -> Unit,
    onFailure: (String) -> Unit,
  ): AndroidVScreenVideoSink = AndroidMediaCodecVScreenSink(surface, width, height, onFrame, onFailure)
}

private class AndroidMediaCodecVScreenSink(
  private val surface: Surface,
  width: Int,
  height: Int,
  private val onFrame: () -> Unit,
  private val onFailure: (String) -> Unit,
) : AndroidVScreenVideoSink {
  private val closed = AtomicBoolean(false)
  private val failed = AtomicBoolean(false)
  private val rendering = AtomicBoolean(true)
  private val thread = HandlerThread("ClawVScreenDecoder").apply { start() }
  private val handler = Handler(thread.looper)
  private val pendingPackets = AndroidVScreenPacketQueue()
  private val availableInputs = ArrayDeque<Int>()
  private var codec: MediaCodec? = null

  init {
    handler.post {
      if (closed.get()) return@post
      try {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = decoder
        decoder.setCallback(
          object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(
              codec: MediaCodec,
              index: Int,
            ) {
              if (closed.get()) return
              availableInputs.addLast(index)
              drainInputs(codec)
            }

            override fun onOutputBufferAvailable(
              codec: MediaCodec,
              index: Int,
              info: MediaCodec.BufferInfo,
            ) {
              if (closed.get()) return
              val render = rendering.get()
              codec.releaseOutputBuffer(index, render)
              // Surface decoders may report a zero BufferInfo size even when a
              // decoded frame was released to the output Surface.
              if (render) onFrame()
            }

            override fun onOutputFormatChanged(
              codec: MediaCodec,
              format: MediaFormat,
            ) = Unit

            override fun onError(
              codec: MediaCodec,
              exception: MediaCodec.CodecException,
            ) {
              fail("Android could not decode VScreen")
            }
          },
          handler,
        )
        decoder.configure(
          MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height),
          surface,
          null,
          0,
        )
        decoder.start()
      } catch (_: Throwable) {
        fail("Android could not start the VScreen decoder")
      }
    }
  }

  override fun submit(packet: AndroidVScreenVideoPacket) {
    if (closed.get()) return
    handler.post {
      if (closed.get()) return@post
      pendingPackets.offer(packet)
      codec?.let(::drainInputs)
    }
  }

  override fun setRenderingEnabled(enabled: Boolean) {
    rendering.set(enabled)
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    handler.post {
      pendingPackets.clear()
      availableInputs.clear()
      codec?.let { decoder ->
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
      }
      codec = null
      thread.quitSafely()
    }
  }

  private fun drainInputs(decoder: MediaCodec) {
    while (!closed.get() && pendingPackets.size > 0 && availableInputs.isNotEmpty()) {
      val index = availableInputs.removeFirst()
      val packet = pendingPackets.removeFirstOrNull() ?: return
      val input = decoder.getInputBuffer(index)
      if (input == null || input.capacity() < packet.bytes.size) {
        fail("VScreen produced an unsupported video packet")
        return
      }
      input.clear()
      input.put(packet.bytes)
      val flags =
        (if (packet.codecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0) or
          (if (packet.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
      decoder.queueInputBuffer(index, 0, packet.bytes.size, packet.presentationTimeUs, flags)
    }
  }

  private fun fail(message: String) {
    if (failed.compareAndSet(false, true)) onFailure(message)
    close()
  }
}
