package ai.openclaw.app.vscreen

import ai.openclaw.app.gateway.GatewaySession
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID

internal sealed interface PreviewSurfaceProbeState {
  data object Idle : PreviewSurfaceProbeState

  data object Checking : PreviewSurfaceProbeState

  data class Ready(
    val targetId: String,
    val phoneVerificationId: String,
    val probeId: String,
  ) : PreviewSurfaceProbeState

  data class NeedsAction(
    val message: String,
  ) : PreviewSurfaceProbeState
}

internal fun interface PreviewSurfaceProbeRenderer {
  suspend fun awaitFirstFrame(
    transport: VScreenTransport,
    connection: VScreenGatewayConnection,
    descriptor: PreviewSurfaceProbeDescriptor,
  )
}

/**
 * One pre-Chat VScreen readiness probe.
 *
 * It owns no Chat/run and exposes no presentation. The authenticated producer stream must decode
 * into a real Android Surface and deliver one frame before this owner publishes Ready.
 */
internal class PreviewSurfaceProbeController(
  private val scope: CoroutineScope,
  private val transport: VScreenTransport,
  private val producerId: String,
  private val json: Json,
  private val renderer: PreviewSurfaceProbeRenderer = AndroidVScreenSurfaceProbeRenderer(),
  private val createProbeId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
  private val nowMs: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private val mutableState = MutableStateFlow<PreviewSurfaceProbeState>(PreviewSurfaceProbeState.Idle)
  val state = mutableState.asStateFlow()
  private var operation: Job? = null
  private var activeConnection: VScreenGatewayConnection? = null

  fun check(
    expectedTargetRef: String,
    phoneVerificationId: String,
  ) {
    if (
      expectedTargetRef.isBlank() ||
      expectedTargetRef.length > 512 ||
      !phoneVerificationId.matches(Regex("[0-9a-f]{32}"))
    ) {
      publishWithoutConnection(PreviewSurfaceProbeState.NeedsAction("The development phone identity is invalid."))
      return
    }
    val connection = transport.capture()
    if (connection == null) {
      publishWithoutConnection(PreviewSurfaceProbeState.NeedsAction("Connect OpenClaw before checking VScreen."))
      return
    }
    if (!connection.methods.containsAll(previewSurfaceProbeMethods)) {
      publishWithoutConnection(PreviewSurfaceProbeState.NeedsAction("This OpenClaw version cannot check VScreen readiness."))
      return
    }
    val probeId = createProbeId()
    check(probeId.matches(Regex("[0-9a-f]{32}")))
    synchronized(lock) {
      if (operation?.isActive == true) return
      activeConnection = connection
      mutableState.value = PreviewSurfaceProbeState.Checking
      operation =
        scope.launch {
          runProbe(connection, expectedTargetRef, phoneVerificationId, probeId)
        }
    }
  }

  fun invalidateConnection(connection: GatewaySession.RequestLease) {
    val job =
      synchronized(lock) {
        if (activeConnection?.lease !== connection) return
        activeConnection = null
        operation.also { operation = null }
      }
    job?.cancel()
    mutableState.value = PreviewSurfaceProbeState.Idle
  }

  private suspend fun runProbe(
    connection: VScreenGatewayConnection,
    expectedTargetRef: String,
    phoneVerificationId: String,
    probeId: String,
  ) {
    var descriptor: PreviewSurfaceProbeDescriptor? = null
    try {
      descriptor = startOrReconcile(connection, expectedTargetRef, probeId)
      renderer.awaitFirstFrame(transport, connection, descriptor)
      finishOrReconcile(connection, descriptor)
      transport.publish(connection) {
        synchronized(lock) {
          if (activeConnection == connection) {
            mutableState.value =
              PreviewSurfaceProbeState.Ready(
                targetId = expectedTargetRef,
                phoneVerificationId = phoneVerificationId,
                probeId = probeId,
              )
          }
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {
      descriptor?.let { runCatching { finishOrReconcile(connection, it) } }
      transport.publish(connection) {
        synchronized(lock) {
          if (activeConnection == connection) {
            mutableState.value =
              PreviewSurfaceProbeState.NeedsAction(
                "VScreen could not render its authenticated test frame. Try again.",
              )
          }
        }
      }
    } finally {
      synchronized(lock) {
        if (activeConnection == connection) {
          if (mutableState.value is PreviewSurfaceProbeState.Checking) {
            mutableState.value =
              PreviewSurfaceProbeState.NeedsAction(
                "VScreen readiness changed while the test frame was rendering. Try again.",
              )
          }
          activeConnection = null
          operation = null
        }
      }
    }
  }

  private suspend fun startOrReconcile(
    connection: VScreenGatewayConnection,
    expectedTargetRef: String,
    probeId: String,
  ): PreviewSurfaceProbeDescriptor {
    val statusParams = previewSurfaceProbeParams(probeId)
    val raw =
      try {
        transport.request(
          connection,
          PREVIEW_SURFACE_PROBE_START_METHOD,
          previewSurfaceProbeStartParams(producerId, probeId),
          90_000,
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        transport.request(connection, PREVIEW_SURFACE_PROBE_STATUS_METHOD, statusParams, 10_000)
      }
    return parsePreviewSurfaceProbeDescriptor(raw, probeId, producerId, expectedTargetRef, json, nowMs())
      ?: error("OpenClaw did not confirm the VScreen readiness probe")
  }

  private suspend fun finishOrReconcile(
    connection: VScreenGatewayConnection,
    descriptor: PreviewSurfaceProbeDescriptor,
  ) {
    val statusParams = previewSurfaceProbeParams(descriptor.probeId)
    val raw =
      try {
        transport.request(
          connection,
          PREVIEW_SURFACE_PROBE_FINISH_METHOD,
          previewSurfaceProbeFinishParams(descriptor.probeId, descriptor.attachmentId),
          10_000,
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        transport.request(connection, PREVIEW_SURFACE_PROBE_STATUS_METHOD, statusParams, 10_000)
      }
    check(parsePreviewSurfaceProbeIdle(raw, descriptor.probeId, json)) {
      "OpenClaw did not confirm VScreen probe cleanup"
    }
  }

  private fun publishWithoutConnection(value: PreviewSurfaceProbeState) {
    synchronized(lock) {
      if (operation?.isActive == true) return
      mutableState.value = value
    }
  }
}

private class AndroidVScreenSurfaceProbeRenderer(
  private val videoSinkFactory: AndroidVScreenVideoSinkFactory = AndroidMediaCodecVScreenSinkFactory,
) : PreviewSurfaceProbeRenderer {
  override suspend fun awaitFirstFrame(
    transport: VScreenTransport,
    connection: VScreenGatewayConnection,
    descriptor: PreviewSurfaceProbeDescriptor,
  ) {
    val firstFrame = CompletableDeferred<Unit>()
    val texture = SurfaceTexture(false)
    texture.setDefaultBufferSize(descriptor.width, descriptor.height)
    texture.setOnFrameAvailableListener(
      { firstFrame.complete(Unit) },
      Handler(Looper.getMainLooper()),
    )
    val surface = Surface(texture)
    val sink =
      videoSinkFactory.create(
        surface = surface,
        width = descriptor.width,
        height = descriptor.height,
        onFrame = {},
        onFailure = { message -> firstFrame.completeExceptionally(IllegalStateException(message)) },
      )
    val parser = AndroidVScreenVideoParser(descriptor.width, descriptor.height)
    var stream: VScreenStream? = null
    try {
      stream =
        transport.openStream(
          connection = connection,
          routePath = descriptor.streamPath,
          bearerToken = descriptor.token,
          listener =
            object : VScreenStreamListener {
              override fun onOpen() = Unit

              override fun onBytes(bytes: ByteArray) {
                try {
                  parser.append(bytes).forEach(sink::submit)
                } catch (error: Throwable) {
                  firstFrame.completeExceptionally(error)
                }
              }

              override fun onClosed(message: String?) {
                firstFrame.completeExceptionally(
                  IllegalStateException(message ?: "VScreen probe stream ended before its first frame"),
                )
              }
            },
        )
      withTimeout(30_000) { firstFrame.await() }
    } finally {
      stream?.cancel()
      sink.close()
      surface.release()
      texture.release()
    }
  }
}
