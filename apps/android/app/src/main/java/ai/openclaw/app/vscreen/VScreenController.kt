package ai.openclaw.app.vscreen

import ai.openclaw.app.gateway.GatewaySession
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class VScreenGatewayConnection(
  val stableId: String,
  val generation: Long,
  val catalogRevision: Long,
  val methods: Set<String>,
  val lease: GatewaySession.RequestLease,
)

internal interface VScreenStream {
  fun send(text: String): Boolean

  fun cancel()
}

internal interface VScreenStreamListener {
  fun onOpen()

  fun onBytes(bytes: ByteArray)

  fun onClosed(message: String?)
}

internal interface VScreenTransport {
  fun capture(): VScreenGatewayConnection?

  fun publish(
    connection: VScreenGatewayConnection,
    block: () -> Unit,
  ): Boolean

  suspend fun request(
    connection: VScreenGatewayConnection,
    method: String,
    params: String,
    timeoutMs: Long,
  ): String

  fun openStream(
    connection: VScreenGatewayConnection,
    routePath: String,
    bearerToken: String,
    listener: VScreenStreamListener,
  ): VScreenStream
}

internal enum class VScreenRuntime {
  Starting,
  Ready,
  Closing,
  Reconnecting,
  Unavailable,
}

internal data class VScreenState(
  val runtime: VScreenRuntime = VScreenRuntime.Unavailable,
  val foreground: VScreenForeground = VScreenForeground.Unknown,
  val attachmentId: String? = null,
  val producerId: String? = null,
  val targetRef: String? = null,
  val targetGeneration: Long? = null,
  val sourceGeneration: Long? = null,
  val workloadRequestId: String? = null,
  val workloadGeneration: Long? = null,
  val width: Int? = null,
  val height: Int? = null,
  val rotation: Int = 0,
  val interactive: Boolean = false,
  val message: String? = null,
)

internal enum class VScreenPointerPhase {
  Down,
  Move,
  Up,
  Cancel,
}

internal data class VScreenPointerEvent(
  val attachmentId: String,
  val sequence: Long,
  val pointerId: Long,
  val phase: VScreenPointerPhase,
  val normalizedX: Float,
  val normalizedY: Float,
  val pressure: Float,
)

/** Owns one App-global display attachment. Workload origins never own its lifetime. */
internal class VScreenController(
  private val scope: CoroutineScope,
  private val transport: VScreenTransport,
  private val json: Json,
  private val targets: VScreenTargetWriter,
  private val producers: VScreenProducerRegistry,
  private val producerId: String,
  private val onWorkloadRequested: (VScreenWorkloadEvent) -> Unit = {},
  private val onWorkloadAccepted: (VScreenGatewayConnection, VScreenWorkloadEvent) -> Unit = { _, _ -> },
  private val onWorkloadFailed: (VScreenWorkloadEvent, String) -> Unit = { _, _ -> },
  private val onHumanPointerDown: (VScreenTargetCandidate) -> Unit = {},
  private val displayObserver: AndroidVScreenDisplayObserver = NoOpAndroidVScreenDisplayObserver,
  private val videoSinkFactory: AndroidVScreenVideoSinkFactory = AndroidMediaCodecVScreenSinkFactory,
  private val nowMs: () -> Long = System::currentTimeMillis,
  private val workloadFirstFrameTimeoutMs: Long = WORKLOAD_FIRST_FRAME_TIMEOUT_MS,
) {
  private data class ActiveDisplay(
    val connection: VScreenGatewayConnection,
    var descriptor: VScreenDescriptor,
    var geometry: AndroidVScreenDisplayGeometry = descriptor.geometry(),
    var stream: VScreenStream? = null,
    var streamGeneration: Long = 0,
    var sink: AndroidVScreenVideoSink? = null,
    var parser: AndroidVScreenVideoParser? = null,
    var surface: Surface? = null,
    var surfaceVisible: Boolean = false,
    var sinkGeneration: Long = 0,
    var decoderRecovering: Boolean = false,
    var decoderRecoveryAttempts: Int = 0,
    var awaitingDecoderKeyFrame: Boolean = false,
    var codecConfig: AndroidVScreenVideoPacket? = null,
    var surfaceOwner: AndroidVScreenSurfaceOwner? = null,
    var displaySubscription: AndroidVScreenDisplaySubscription? = null,
    var firstFrameReporting: Boolean = false,
    var homeFrameReported: Boolean = false,
    var firstFrameReportedForWorkload: String? = null,
    var workload: VScreenWorkloadEvent? = null,
    var workloadFirstFrameTimeout: Job? = null,
    var lastInputSequence: Long = -1,
    val activePointers: MutableSet<Long> = mutableSetOf(),
  )

  private val lock = Any()
  private val mutableState = MutableStateFlow(VScreenState())
  val state = mutableState.asStateFlow()
  private var active: ActiveDisplay? = null
  private var mutation: Job? = null
  private var closeMutation: Job? = null
  private var closeRequested: (() -> Unit)? = null
  private var closingDisplay: ActiveDisplay? = null
  private var presentationVisible = false
  private var appForeground = true

  init {
    require(workloadFirstFrameTimeoutMs > 0) { "VScreen workload first-frame timeout must be positive" }
  }

  fun ensure() {
    val current =
      synchronized(lock) {
        if (
          mutation?.isActive == true ||
          closeMutation?.isActive == true ||
          closeRequested != null
        ) {
          return
        }
        active
      }
    if (current != null) {
      val surface = synchronized(lock) { current.surface?.takeIf(Surface::isValid) }
      if (surface != null && synchronized(lock) { current.sink == null }) {
        synchronized(lock) { current.decoderRecoveryAttempts = 0 }
        if (synchronized(lock) { current.stream == null }) {
          attachSurface(current.descriptor.attachmentId, surface)
        } else {
          installDecoder(current, surface, recovering = true)
        }
      }
      return
    }
    requestDisplay(workload = null, reconnecting = mutableState.value.runtime == VScreenRuntime.Reconnecting)
  }

  fun close(onClosed: () -> Unit) {
    synchronized(lock) {
      if (closeRequested != null || closeMutation?.isActive == true) return
      closeRequested = onClosed
      mutableState.value = active?.state(VScreenRuntime.Closing) ?: VScreenState(runtime = VScreenRuntime.Closing)
    }
    schedulePendingClose()
  }

  fun onGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    val workload = parseVScreenGatewayEvent(event, payloadJson, json) ?: return
    onWorkloadRequested(workload)
    if (producers.resolve(workload) == null) {
      onWorkloadFailed(workload, "This VScreen producer is unavailable")
      ensure()
      return
    }
    requestDisplay(workload = workload, reconnecting = false)
  }

  private fun requestDisplay(
    workload: VScreenWorkloadEvent?,
    reconnecting: Boolean,
  ) {
    val connection = transport.capture()
    if (connection == null) {
      publishUnavailable("Connect OpenClaw to open VScreen", reconnecting)
      workload?.let { onWorkloadFailed(it, "Connect OpenClaw to open VScreen") }
      return
    }
    synchronized(lock) {
      if (closeRequested != null || closeMutation?.isActive == true) {
        if (workload != null) onWorkloadFailed(workload, "VScreen is closing")
        return
      }
      if (mutation?.isActive == true) {
        if (workload != null) onWorkloadFailed(workload, "Another VScreen mutation is active")
        return
      }
      if (!connection.methods.containsAll(vscreenMethods)) {
        publishUnavailableLocked("This OpenClaw version cannot open VScreen", reconnecting)
        workload?.let { onWorkloadFailed(it, "This OpenClaw version cannot open VScreen") }
        return
      }
      mutableState.value =
        mutableState.value.copy(
          runtime = if (reconnecting) VScreenRuntime.Reconnecting else VScreenRuntime.Starting,
          message = null,
        )
      mutation =
        scope.launch {
          val ownedMutation = currentCoroutineContext()[Job]
          try {
            val method = if (workload == null) VSCREEN_ENSURE_METHOD else VSCREEN_WORKLOAD_METHOD
            val params =
              if (workload == null) {
                vscreenEnsureParams(producerId)
              } else {
                vscreenWorkloadParams(workload.producerId, workload.workloadRequestId)
              }
            val raw = transport.request(connection, method, params, if (workload == null) 90_000 else 30_000)
            val descriptor =
              parseVScreenDescriptor(
                raw,
                requestedProducerId = workload?.producerId ?: producerId,
                requestedWorkloadId = workload?.workloadRequestId,
                json = json,
                nowMs = nowMs(),
              ) ?: error("OpenClaw returned an invalid VScreen descriptor")
            currentCoroutineContext().ensureActive()
            val display =
              publishDescriptor(connection, descriptor, workload)
                ?: error("The VScreen connection changed before publication")
            currentCoroutineContext().ensureActive()
            workload?.let {
              onWorkloadAccepted(connection, it)
              scheduleWorkloadFirstFrameTimeout(display, it)
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Throwable) {
            Log.w(VSCREEN_LOG_TAG, "VScreen display request failed", error)
            val reason =
              error.message
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(MAX_VSCREEN_ERROR_REASON_LENGTH)
            val message =
              if (reason == null) {
                "VScreen could not establish its Android display"
              } else {
                "VScreen could not establish its Android display: $reason"
              }
            if (workload == null) {
              publishUnavailable(message, reconnecting)
            } else {
              onWorkloadFailed(workload, message)
              recoverHome(connection, message)
            }
          } finally {
            synchronized(lock) {
              if (mutation === ownedMutation) mutation = null
            }
            schedulePendingClose()
          }
        }
    }
  }

  private fun publishDescriptor(
    connection: VScreenGatewayConnection,
    descriptor: VScreenDescriptor,
    workload: VScreenWorkloadEvent? = null,
  ): ActiveDisplay? {
    var retired: ActiveDisplay? = null
    var created: ActiveDisplay? = null
    var publishedDisplay: ActiveDisplay? = null
    val published =
      transport.publish(connection) {
        synchronized(lock) {
          val current = active
          if (
            current != null &&
            current.connection == connection &&
            current.descriptor.attachmentId == descriptor.attachmentId &&
            current.descriptor.targetGeneration == descriptor.targetGeneration &&
            current.descriptor.sourceGeneration == descriptor.sourceGeneration
          ) {
            current.descriptor =
              descriptor.copy(
                token = descriptor.token ?: current.descriptor.token,
                expiresAtMs = descriptor.expiresAtMs.takeIf { it > 0 } ?: current.descriptor.expiresAtMs,
              )
            current.firstFrameReporting = false
            current.firstFrameReportedForWorkload = null
            current.workloadFirstFrameTimeout?.cancel()
            current.workloadFirstFrameTimeout = null
            current.workload = workload
            publishedDisplay = current
            mutableState.value = current.state(VScreenRuntime.Starting)
          } else {
            check(descriptor.token != null) { "New VScreen attachment has no stream authorization" }
            retired = current
            val next = ActiveDisplay(connection, descriptor, workload = workload)
            created = next
            publishedDisplay = next
            active = next
            mutableState.value = next.state(VScreenRuntime.Starting)
          }
        }
      }
    if (!published) {
      publishUnavailable("The VScreen connection changed", reconnecting = true)
      return null
    }
    retired?.let {
      targets.clear(it.descriptor.attachmentId, it.descriptor.targetGeneration)
      closeLocal(it)
    }
    created?.let(::observeDisplay)
    return publishedDisplay
  }

  private fun scheduleWorkloadFirstFrameTimeout(
    display: ActiveDisplay,
    workload: VScreenWorkloadEvent,
  ) {
    val timeout =
      scope.launch {
        delay(workloadFirstFrameTimeoutMs)
        beginWorkloadHomeRecovery(
          display,
          workload,
          "VScreen did not present the workload's first frame",
        )
      }
    synchronized(lock) {
      if (
        active !== display ||
        display.descriptor.workloadRequestId != workload.workloadRequestId ||
        display.firstFrameReportedForWorkload == workload.workloadRequestId
      ) {
        timeout.cancel()
      } else {
        display.workloadFirstFrameTimeout?.cancel()
        display.workloadFirstFrameTimeout = timeout
      }
    }
  }

  private fun beginWorkloadHomeRecovery(
    display: ActiveDisplay,
    workload: VScreenWorkloadEvent,
    message: String,
  ) {
    lateinit var recovery: Job
    synchronized(lock) {
      if (
        active !== display ||
        display.descriptor.workloadRequestId != workload.workloadRequestId ||
        display.firstFrameReportedForWorkload == workload.workloadRequestId ||
        mutation?.isActive == true
      ) {
        return
      }
      display.workloadFirstFrameTimeout = null
      recovery =
        scope.launch(start = CoroutineStart.LAZY) {
          try {
            recoverHome(display.connection, message, display)
          } finally {
            synchronized(lock) {
              if (mutation === recovery) mutation = null
            }
          }
        }
      mutation = recovery
    }
    try {
      onWorkloadFailed(workload, message)
    } finally {
      recovery.start()
    }
  }

  private suspend fun recoverHome(
    connection: VScreenGatewayConnection,
    workloadFailure: String,
    expectedDisplay: ActiveDisplay? = null,
  ) {
    val retired =
      synchronized(lock) {
        if (expectedDisplay != null && active !== expectedDisplay) return
        active.also {
          active = null
          mutableState.value = VScreenState(runtime = VScreenRuntime.Starting)
        }
      }
    retired?.let {
      targets.clear(it.descriptor.attachmentId, it.descriptor.targetGeneration)
      closeLocal(it)
    }
    try {
      val raw = transport.request(connection, VSCREEN_ENSURE_METHOD, vscreenEnsureParams(producerId), 90_000)
      val descriptor =
        parseVScreenDescriptor(
          raw,
          requestedProducerId = producerId,
          requestedWorkloadId = null,
          json = json,
          nowMs = nowMs(),
        ) ?: error("OpenClaw returned an invalid VScreen Home descriptor")
      check(descriptor.foreground == VScreenForeground.Home && descriptor.workloadRequestId == null) {
        "OpenClaw did not return VScreen Home"
      }
      currentCoroutineContext().ensureActive()
      publishDescriptor(connection, descriptor)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      Log.w(VSCREEN_LOG_TAG, "VScreen Home recovery failed", error)
      val reason = error.conciseVScreenReason()
      val message =
        if (reason == null) {
          "$workloadFailure. VScreen could not return to Home"
        } else {
          "$workloadFailure. VScreen could not return to Home: $reason"
        }
      publishUnavailable(message.take(MAX_VSCREEN_ERROR_REASON_LENGTH), reconnecting = false)
    }
  }

  fun attachSurface(
    attachmentId: String,
    surface: Surface,
  ) {
    val display =
      synchronized(lock) {
        active?.takeIf {
          it.descriptor.attachmentId == attachmentId && it.sink == null
        }
      } ?: return
    if (!surface.isValid) {
      markViewerUnavailable(display, "The VScreen surface is unavailable")
      return
    }
    synchronized(lock) {
      if (active !== display) return
      display.surface = surface
      if (display.parser == null) {
        display.parser = AndroidVScreenVideoParser(display.descriptor.width, display.descriptor.height)
      }
    }
    if (!installDecoder(display, surface, recovering = display.stream != null)) return
    if (synchronized(lock) { display.stream != null }) return

    val token = display.descriptor.token
    if (token == null) {
      retireTarget(display, "VScreen stream authorization is unavailable")
      return
    }
    val streamGeneration =
      synchronized(lock) {
        if (active !== display || display.stream != null) return
        ++display.streamGeneration
      }
    val stream =
      try {
        transport.openStream(
          display.connection,
          display.descriptor.streamPath,
          token,
          object : VScreenStreamListener {
            override fun onOpen() = Unit

            override fun onBytes(bytes: ByteArray) {
              val parser =
                synchronized(lock) {
                  if (active !== display || display.streamGeneration != streamGeneration) return
                  display.parser
                } ?: return
              try {
                parser.append(bytes).forEach { submitVideoPacket(display, streamGeneration, it) }
              } catch (error: Throwable) {
                Log.w(VSCREEN_LOG_TAG, "VScreen video stream was invalid", error)
                retireTarget(display, "The VScreen video stream was invalid")
              }
            }

            override fun onClosed(message: String?) {
              val current = synchronized(lock) { active === display && display.streamGeneration == streamGeneration }
              if (current) retireTarget(display, message ?: "The VScreen stream ended")
            }
          },
        )
      } catch (_: Throwable) {
        retireTarget(display, "The VScreen connection could not be opened")
        return
      }
    synchronized(lock) {
      if (active !== display || display.streamGeneration != streamGeneration || display.stream != null) {
        stream.cancel()
      } else {
        display.stream = stream
      }
    }
  }

  private fun installDecoder(
    display: ActiveDisplay,
    surface: Surface,
    recovering: Boolean,
  ): Boolean {
    val generation =
      synchronized(lock) {
        if (active !== display || display.sink != null || !surface.isValid) return false
        display.surface = surface
        display.decoderRecovering = recovering
        display.awaitingDecoderKeyFrame = recovering
        if (recovering) mutableState.value = display.state(VScreenRuntime.Reconnecting)
        ++display.sinkGeneration
      }
    val sink =
      try {
        videoSinkFactory.create(
          surface,
          display.descriptor.width,
          display.descriptor.height,
          onFrame = {
            onDecoderFrame(display, generation)
            reportFirstFrame(display)
          },
          onFailure = { onDecoderFailure(display, generation, it) },
        )
      } catch (_: Throwable) {
        markViewerUnavailable(display, "Android could not start the VScreen decoder")
        return false
      }
    val installed =
      synchronized(lock) {
        if (active !== display || display.sinkGeneration != generation || display.sink != null) {
          false
        } else {
          display.sink = sink
          updateRenderingLocked(display)
          true
        }
      }
    if (!installed) sink.close()
    return installed
  }

  private fun submitVideoPacket(
    display: ActiveDisplay,
    streamGeneration: Long,
    packet: AndroidVScreenVideoPacket,
  ) {
    val submission =
      synchronized(lock) {
        if (active !== display || display.streamGeneration != streamGeneration) return
        if (packet.codecConfig) display.codecConfig = packet
        val sink = display.sink ?: return
        if (!display.awaitingDecoderKeyFrame) return@synchronized sink to listOf(packet)
        if (!packet.keyFrame) return
        display.awaitingDecoderKeyFrame = false
        sink to
          buildList {
            display.codecConfig?.takeUnless { it === packet }?.let(::add)
            add(packet)
          }
      }
    submission.second.forEach(submission.first::submit)
  }

  private fun onDecoderFailure(
    display: ActiveDisplay,
    generation: Long,
    message: String,
  ) {
    var failedSink: AndroidVScreenVideoSink? = null
    var recoverySurface: Surface? = null
    synchronized(lock) {
      if (active !== display || display.sinkGeneration != generation) return
      failedSink = display.sink
      display.sink = null
      display.decoderRecovering = true
      display.awaitingDecoderKeyFrame = true
      display.decoderRecoveryAttempts += 1
      mutableState.value =
        display.state(
          if (display.decoderRecoveryAttempts <= MAX_DECODER_RECOVERY_ATTEMPTS) {
            VScreenRuntime.Reconnecting
          } else {
            VScreenRuntime.Unavailable
          },
          message,
        )
      if (display.decoderRecoveryAttempts <= MAX_DECODER_RECOVERY_ATTEMPTS) {
        recoverySurface = display.surface?.takeIf(Surface::isValid)
      }
    }
    failedSink?.close()
    recoverySurface?.let { installDecoder(display, it, recovering = true) }
  }

  private fun onDecoderFrame(
    display: ActiveDisplay,
    generation: Long,
  ) {
    synchronized(lock) {
      if (active !== display || display.sinkGeneration != generation || !display.decoderRecovering) return
      display.decoderRecovering = false
      display.decoderRecoveryAttempts = 0
      if (
        hasReportedVScreenFrame(
          display.homeFrameReported,
          display.firstFrameReportedForWorkload,
          display.descriptor.workloadRequestId,
        )
      ) {
        mutableState.value = display.state(VScreenRuntime.Ready)
      }
    }
  }

  private fun markViewerUnavailable(
    display: ActiveDisplay,
    message: String,
  ) {
    synchronized(lock) {
      if (active !== display) return
      display.decoderRecovering = false
      mutableState.value = display.state(VScreenRuntime.Unavailable, message)
    }
  }

  fun surfaceOwner(attachmentId: String): AndroidVScreenSurfaceOwner? =
    synchronized(lock) {
      val display = active?.takeIf { it.descriptor.attachmentId == attachmentId } ?: return@synchronized null
      display.surfaceOwner ?: AndroidVScreenSurfaceOwner(
        onSurface = { attachSurface(attachmentId, it) },
        onVisible = { visible ->
          synchronized(lock) {
            if (active === display) {
              display.surfaceVisible = visible
              updateRenderingLocked(display)
            }
          }
        },
        onFrame = { reportFirstFrame(display) },
      ).also { display.surfaceOwner = it }
    }

  fun setPresentationVisible(visible: Boolean) {
    synchronized(lock) {
      presentationVisible = visible
      active?.let(::updateRenderingLocked)
    }
  }

  private fun updateRenderingLocked(display: ActiveDisplay) {
    display.sink?.setRenderingEnabled(display.surfaceVisible && presentationVisible && appForeground)
  }

  private fun schedulePendingClose() {
    var display: ActiveDisplay? = null
    var completion: (() -> Unit)? = null
    synchronized(lock) {
      if (
        closeRequested == null ||
        closeMutation?.isActive == true ||
        mutation?.isActive == true
      ) {
        return
      }
      display = active
      if (display == null) {
        completion = closeRequested
        closeRequested = null
        mutableState.value = VScreenState()
      }
    }
    val current = display
    if (current == null) {
      completion?.invoke()
    } else {
      startClose(current)
    }
  }

  private fun startClose(display: ActiveDisplay) {
    lateinit var job: Job
    job =
      scope.launch(start = CoroutineStart.LAZY) {
        val confirmed = closeRemote(display)
        var completion: (() -> Unit)? = null
        var releaseLocal = false
        synchronized(lock) {
          if (closeMutation !== job || closingDisplay !== display) return@launch
          closeMutation = null
          closingDisplay = null
          if (confirmed) {
            if (active === display) active = null
            completion = closeRequested
            closeRequested = null
            mutableState.value = VScreenState()
            releaseLocal = true
          } else {
            closeRequested = null
            mutableState.value =
              if (active === display) {
                display.state(VScreenRuntime.Ready, "VScreen could not close. Try again")
              } else {
                VScreenState(message = "VScreen close could not be confirmed")
              }
          }
        }
        if (releaseLocal) {
          targets.clear(display.descriptor.attachmentId, display.descriptor.targetGeneration)
          closeLocal(display)
          completion?.invoke()
        }
      }
    synchronized(lock) {
      if (
        active !== display ||
        closeRequested == null ||
        closeMutation?.isActive == true ||
        mutation?.isActive == true
      ) {
        return
      }
      closingDisplay = display
      closeMutation = job
      mutableState.value = display.state(VScreenRuntime.Closing)
    }
    job.start()
  }

  private suspend fun closeRemote(display: ActiveDisplay): Boolean {
    val params =
      vscreenCloseParams(
        attachmentId = display.descriptor.attachmentId,
        targetGeneration = display.descriptor.targetGeneration,
        sourceGeneration = display.descriptor.sourceGeneration,
      )
    val closed =
      try {
        val raw = transport.request(display.connection, VSCREEN_CLOSE_METHOD, params, 30_000)
        parseVScreenClosed(
          raw,
          display.descriptor.attachmentId,
          display.descriptor.targetGeneration,
          display.descriptor.sourceGeneration,
          json,
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        Log.w(VSCREEN_LOG_TAG, "VScreen close request failed; reconciling status", error)
        false
      }
    if (closed) return true
    return try {
      val raw = transport.request(display.connection, VSCREEN_STATUS_METHOD, vscreenStatusParams(), 10_000)
      parseVScreenUnavailable(raw, json)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (statusError: Throwable) {
      Log.w(VSCREEN_LOG_TAG, "VScreen close status reconciliation failed", statusError)
      false
    }
  }

  fun setAppForeground(foreground: Boolean) {
    synchronized(lock) {
      appForeground = foreground
      active?.let(::updateRenderingLocked)
    }
  }

  fun sendPointer(event: VScreenPointerEvent): Boolean =
    synchronized(lock) {
      val display = active ?: return@synchronized false
      if (
        mutableState.value.runtime != VScreenRuntime.Ready ||
        "pointer-v1" !in display.descriptor.capabilities ||
        event.attachmentId != display.descriptor.attachmentId ||
        event.sequence <= display.lastInputSequence ||
        event.pointerId < 0 ||
        event.normalizedX !in 0f..1f ||
        event.normalizedY !in 0f..1f ||
        event.pressure !in 0f..1f
      ) {
        return@synchronized false
      }
      val pointerActive = event.pointerId in display.activePointers
      if (
        (event.phase == VScreenPointerPhase.Down && pointerActive) ||
        (event.phase != VScreenPointerPhase.Down && !pointerActive)
      ) {
        return@synchronized false
      }
      val payload =
        buildJsonObject {
          put("type", "pointer")
          put("attachmentId", event.attachmentId)
          put("sequence", event.sequence)
          put("pointerId", event.pointerId)
          put("phase", event.phase.name.lowercase())
          put("normalizedX", event.normalizedX)
          put("normalizedY", event.normalizedY)
          put("pressure", event.pressure)
        }.toString()
      if (event.phase == VScreenPointerPhase.Down) onHumanPointerDown(display.target())
      if (display.stream?.send(payload) != true) return@synchronized false
      display.lastInputSequence = event.sequence
      when (event.phase) {
        VScreenPointerPhase.Down -> display.activePointers += event.pointerId
        VScreenPointerPhase.Up,
        VScreenPointerPhase.Cancel,
        -> display.activePointers -= event.pointerId
        VScreenPointerPhase.Move -> Unit
      }
      true
    }

  fun invalidateConnection(connection: GatewaySession.RequestLease) {
    val retired: ActiveDisplay?
    synchronized(lock) {
      if (active?.connection?.lease !== connection && transport.capture()?.lease !== connection) return
      mutation?.cancel()
      mutation = null
      retired = active
      active = null
      mutableState.value = mutableState.value.copy(runtime = VScreenRuntime.Reconnecting, message = null)
    }
    retired?.let {
      targets.clear(it.descriptor.attachmentId, it.descriptor.targetGeneration)
      closeLocal(it)
    }
  }

  private fun reportFirstFrame(display: ActiveDisplay) {
    val workloadId = display.descriptor.workloadRequestId
    synchronized(lock) {
      if (
        active !== display ||
        display.firstFrameReporting ||
        hasReportedVScreenFrame(
          display.homeFrameReported,
          display.firstFrameReportedForWorkload,
          workloadId,
        )
      ) {
        return
      }
      display.firstFrameReporting = true
    }
    scope.launch {
      val recorded =
        try {
          val raw =
            transport.request(
              display.connection,
              VSCREEN_FRAME_PRESENTED_METHOD,
              vscreenFramePresentedParams(display.descriptor.attachmentId, workloadId),
              10_000,
            )
          parseVScreenFramePresented(raw, display.descriptor.attachmentId, workloadId, json)
        } catch (error: Throwable) {
          Log.w(VSCREEN_LOG_TAG, "VScreen first-frame confirmation failed", error)
          false
        }
      var failedWorkload: VScreenWorkloadEvent? = null
      synchronized(lock) {
        if (active !== display) return@synchronized
        display.firstFrameReporting = false
        if (recorded) {
          if (workloadId == null) {
            display.homeFrameReported = true
          } else {
            display.firstFrameReportedForWorkload = workloadId
          }
          display.workloadFirstFrameTimeout?.cancel()
          display.workloadFirstFrameTimeout = null
          display.workload = null
          targets.publish(display.target())
          mutableState.value = display.state(VScreenRuntime.Ready)
        } else {
          failedWorkload = display.workload
        }
      }
      if (!recorded && isActive(display)) {
        val workload = failedWorkload
        if (workload == null) {
          retireTarget(display, "The VScreen first frame could not be confirmed")
        } else {
          beginWorkloadHomeRecovery(display, workload, "The VScreen workload first frame could not be confirmed")
        }
      }
    }
  }

  private fun observeDisplay(display: ActiveDisplay) {
    val subscription =
      displayObserver.observe(display.descriptor.displayId) { geometry ->
        if (geometry == null) {
          if (isActive(display)) retireTarget(display, "The VScreen display ended")
        } else {
          updateGeometry(display, geometry)
        }
      }
    synchronized(lock) {
      if (active !== display || display.displaySubscription != null) {
        subscription.close()
      } else {
        display.displaySubscription = subscription
      }
    }
  }

  private fun updateGeometry(
    display: ActiveDisplay,
    geometry: AndroidVScreenDisplayGeometry,
  ) {
    if (geometry.width !in 1..4096 || geometry.height !in 1..4096 || geometry.dpi !in 72..960 || geometry.rotation !in 0..3) {
      retireTarget(display, "The VScreen display geometry is invalid")
      return
    }
    synchronized(lock) {
      if (active !== display || display.geometry == geometry) return
      display.geometry = geometry
      if (mutableState.value.runtime == VScreenRuntime.Ready) targets.publish(display.target())
      mutableState.value = display.state(mutableState.value.runtime)
    }
  }

  /** Producer, transport and Android display loss retire the exact target. */
  private fun retireTarget(
    display: ActiveDisplay,
    message: String,
  ) {
    val closing =
      synchronized(lock) {
        if (active !== display) return
        if (closingDisplay === display) {
          active = null
          true
        } else {
          false
        }
      }
    if (closing) {
      targets.clear(display.descriptor.attachmentId, display.descriptor.targetGeneration)
      closeLocal(display)
      return
    }
    val failedWorkload =
      synchronized(lock) {
        if (active !== display) return
        display.workload?.also {
          mutation?.cancel()
          mutation = null
        }
      }
    if (failedWorkload != null) {
      beginWorkloadHomeRecovery(display, failedWorkload, message)
      return
    }
    synchronized(lock) {
      if (active !== display) return
      active = null
      targets.clear(display.descriptor.attachmentId, display.descriptor.targetGeneration)
      mutableState.value = display.state(VScreenRuntime.Unavailable, message)
    }
    closeLocal(display)
  }

  private fun publishUnavailable(
    message: String,
    reconnecting: Boolean,
  ) {
    synchronized(lock) { publishUnavailableLocked(message, reconnecting) }
  }

  private fun publishUnavailableLocked(
    message: String,
    reconnecting: Boolean,
  ) {
    mutableState.value =
      mutableState.value.copy(
        runtime = if (reconnecting) VScreenRuntime.Reconnecting else VScreenRuntime.Unavailable,
        message = message,
      )
  }

  private fun isActive(display: ActiveDisplay): Boolean = synchronized(lock) { active === display }

  private fun closeLocal(display: ActiveDisplay) {
    display.workloadFirstFrameTimeout?.cancel()
    display.workloadFirstFrameTimeout = null
    display.displaySubscription?.close()
    display.displaySubscription = null
    display.streamGeneration += 1
    display.stream?.cancel()
    display.stream = null
    display.sinkGeneration += 1
    display.sink?.close()
    display.sink = null
    display.parser = null
    display.surfaceVisible = false
    display.surface = null
    display.codecConfig = null
    display.awaitingDecoderKeyFrame = false
    display.decoderRecovering = false
    display.surfaceOwner?.close()
    display.surfaceOwner = null
  }

  private fun ActiveDisplay.state(
    runtime: VScreenRuntime,
    message: String? = null,
  ): VScreenState =
    VScreenState(
      runtime = runtime,
      foreground = descriptor.foreground,
      attachmentId = descriptor.attachmentId,
      producerId = descriptor.producerId,
      targetRef = descriptor.targetRef,
      targetGeneration = descriptor.targetGeneration,
      sourceGeneration = descriptor.sourceGeneration,
      workloadRequestId = descriptor.workloadRequestId,
      workloadGeneration = descriptor.workloadGeneration,
      width = geometry.width,
      height = geometry.height,
      rotation = geometry.rotation,
      interactive = "pointer-v1" in descriptor.capabilities,
      message = message,
    )

  private fun ActiveDisplay.target(): VScreenTargetCandidate =
    VScreenTargetCandidate(
      producerId = descriptor.producerId,
      workloadRequestId = descriptor.workloadRequestId,
      attachmentId = descriptor.attachmentId,
      targetRef = descriptor.targetRef,
      targetGeneration = descriptor.targetGeneration,
      sourceGeneration = descriptor.sourceGeneration,
      displayId = descriptor.displayId,
      width = geometry.width,
      height = geometry.height,
      dpi = geometry.dpi,
      rotation = geometry.rotation,
    )
}

private fun VScreenDescriptor.geometry(): AndroidVScreenDisplayGeometry = AndroidVScreenDisplayGeometry(width, height, dpi, rotation)

internal fun hasReportedVScreenFrame(
  homeFrameReported: Boolean,
  firstFrameReportedForWorkload: String?,
  workloadId: String?,
): Boolean = if (workloadId == null) homeFrameReported else firstFrameReportedForWorkload == workloadId

private const val VSCREEN_LOG_TAG = "ClawInOneVScreen"
private const val MAX_VSCREEN_ERROR_REASON_LENGTH = 160
private const val MAX_DECODER_RECOVERY_ATTEMPTS = 3
private const val WORKLOAD_FIRST_FRAME_TIMEOUT_MS = 35_000L

private fun Throwable.conciseVScreenReason(): String? =
  message
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.take(MAX_VSCREEN_ERROR_REASON_LENGTH)
