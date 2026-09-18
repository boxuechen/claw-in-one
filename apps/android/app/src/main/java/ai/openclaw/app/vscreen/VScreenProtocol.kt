package ai.openclaw.app.vscreen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val VSCREEN_PROTOCOL_VERSION = 3
internal const val VSCREEN_STATUS_METHOD = "claw.vscreen.status"
internal const val VSCREEN_ENSURE_METHOD = "claw.vscreen.ensure"
internal const val VSCREEN_CLOSE_METHOD = "claw.vscreen.close"
internal const val VSCREEN_WORKLOAD_METHOD = "claw.vscreen.workload"
internal const val VSCREEN_FRAME_PRESENTED_METHOD = "claw.vscreen.frame_presented"
internal const val PREVIEW_SURFACE_PROBE_STATUS_METHOD = "claw.preview.probe.status"
internal const val PREVIEW_SURFACE_PROBE_START_METHOD = "claw.preview.probe.start"
internal const val PREVIEW_SURFACE_PROBE_FINISH_METHOD = "claw.preview.probe.finish"
internal const val VSCREEN_ROUTE = "/claw-in-one/vscreen"
internal const val VSCREEN_CODEC = "h264"
internal const val VSCREEN_AGENT_EVENT_STREAM = "claw-in-one-vscreen.workload"

internal val vscreenMethods =
  setOf(
    VSCREEN_STATUS_METHOD,
    VSCREEN_ENSURE_METHOD,
    VSCREEN_CLOSE_METHOD,
    VSCREEN_WORKLOAD_METHOD,
    VSCREEN_FRAME_PRESENTED_METHOD,
  )

internal val previewSurfaceProbeMethods =
  setOf(
    PREVIEW_SURFACE_PROBE_STATUS_METHOD,
    PREVIEW_SURFACE_PROBE_START_METHOD,
    PREVIEW_SURFACE_PROBE_FINISH_METHOD,
  )

private val producerIdPattern = Regex("[a-z0-9][a-z0-9.-]{0,127}")
private val requestIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
private val attachmentIdPattern =
  Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val tokenPattern = Regex("[0-9a-f]{64}")
private val sessionIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val probeIdPattern = Regex("[0-9a-f]{32}")

internal enum class VScreenForeground {
  Home,
  Workload,
  SystemUi,
  Unknown,
}

internal data class VScreenDescriptor(
  val attachmentId: String,
  val producerId: String,
  val targetRef: String,
  val targetGeneration: Long,
  val sourceGeneration: Long,
  val workloadRequestId: String?,
  val workloadGeneration: Long?,
  val foreground: VScreenForeground,
  val streamPath: String,
  val token: String?,
  val expiresAtMs: Long,
  val displayId: Int,
  val width: Int,
  val height: Int,
  val dpi: Int,
  val rotation: Int,
  val capabilities: Set<String>,
)

internal data class PreviewSurfaceProbeDescriptor(
  val probeId: String,
  val attachmentId: String,
  val producerId: String,
  val targetRef: String,
  val streamPath: String,
  val token: String,
  val expiresAtMs: Long,
  val width: Int,
  val height: Int,
)

/** Canonical workload origin from Gateway events; it never owns VScreen lifetime. */
internal data class VScreenRunReference(
  val sessionKey: String,
  val sessionId: String,
  val runId: String,
)

internal data class VScreenWorkloadEvent(
  val run: VScreenRunReference,
  val producerId: String,
  val workloadRequestId: String,
  val toolCallId: String,
  val producerPayload: JsonObject,
)

internal fun vscreenStatusParams(): String = buildJsonObject { put("protocolVersion", VSCREEN_PROTOCOL_VERSION) }.toString()

internal fun vscreenEnsureParams(producerId: String): String {
  require(producerId.matches(producerIdPattern)) { "Invalid VScreen producer ID" }
  return buildJsonObject {
    put("protocolVersion", VSCREEN_PROTOCOL_VERSION)
    put("producerId", producerId)
  }.toString()
}

internal fun vscreenCloseParams(
  attachmentId: String,
  targetGeneration: Long,
  sourceGeneration: Long,
): String {
  require(attachmentId.matches(attachmentIdPattern)) { "Invalid VScreen attachment ID" }
  require(targetGeneration > 0) { "Invalid VScreen target generation" }
  require(sourceGeneration > 0) { "Invalid VScreen source generation" }
  return buildJsonObject {
    put("protocolVersion", VSCREEN_PROTOCOL_VERSION)
    put("attachmentId", attachmentId)
    put("targetGeneration", targetGeneration)
    put("sourceGeneration", sourceGeneration)
  }.toString()
}

internal fun parseVScreenClosed(
  raw: String,
  attachmentId: String,
  targetGeneration: Long,
  sourceGeneration: Long,
  json: Json,
): Boolean {
  if (!attachmentId.matches(attachmentIdPattern) || targetGeneration <= 0 || sourceGeneration <= 0) return false
  val root = raw.objectOrNull(json) ?: return false
  return root.int("protocolVersion") == VSCREEN_PROTOCOL_VERSION &&
    root.string("status") == "closed" &&
    root.string("attachmentId") == attachmentId &&
    root.long("targetGeneration") == targetGeneration &&
    root.long("sourceGeneration") == sourceGeneration
}

internal fun parseVScreenUnavailable(
  raw: String,
  json: Json,
): Boolean {
  val root = raw.objectOrNull(json) ?: return false
  return root.int("protocolVersion") == VSCREEN_PROTOCOL_VERSION &&
    root.string("status") == "unavailable"
}

internal fun vscreenWorkloadParams(
  producerId: String,
  workloadRequestId: String,
): String {
  require(producerId.matches(producerIdPattern)) { "Invalid VScreen producer ID" }
  require(workloadRequestId.matches(requestIdPattern)) { "Invalid VScreen workload request ID" }
  return buildJsonObject {
    put("protocolVersion", VSCREEN_PROTOCOL_VERSION)
    put("producerId", producerId)
    put("workloadRequestId", workloadRequestId)
  }.toString()
}

internal fun vscreenFramePresentedParams(
  attachmentId: String,
  workloadRequestId: String?,
): String {
  require(attachmentId.matches(attachmentIdPattern)) { "Invalid VScreen attachment ID" }
  require(workloadRequestId == null || workloadRequestId.matches(requestIdPattern)) {
    "Invalid VScreen workload request ID"
  }
  return buildJsonObject {
    put("protocolVersion", VSCREEN_PROTOCOL_VERSION)
    put("attachmentId", attachmentId)
    workloadRequestId?.let { put("workloadRequestId", it) }
  }.toString()
}

internal fun parseVScreenFramePresented(
  raw: String,
  attachmentId: String,
  workloadRequestId: String?,
  json: Json,
): Boolean {
  if (!attachmentId.matches(attachmentIdPattern)) return false
  if (workloadRequestId != null && !workloadRequestId.matches(requestIdPattern)) return false
  val root = raw.objectOrNull(json) ?: return false
  if (
    root.int("protocolVersion") != VSCREEN_PROTOCOL_VERSION ||
    root.string("status") != "recorded" ||
    root.string("attachmentId") != attachmentId
  ) {
    return false
  }
  return if (workloadRequestId == null) {
    root.string("workloadRequestId") == null
  } else {
    root.string("workloadRequestId") == workloadRequestId
  }
}

internal fun previewSurfaceProbeParams(probeId: String): String {
  require(probeId.matches(probeIdPattern)) { "Invalid VScreen probe identity" }
  return buildJsonObject {
    put("protocolVersion", VSCREEN_PROTOCOL_VERSION)
    put("probeId", probeId)
  }.toString()
}

internal fun previewSurfaceProbeStartParams(
  producerId: String,
  probeId: String,
): String {
  require(producerId.matches(producerIdPattern)) { "Invalid VScreen producer ID" }
  require(probeId.matches(probeIdPattern)) { "Invalid VScreen probe identity" }
  return buildJsonObject {
    put("protocolVersion", VSCREEN_PROTOCOL_VERSION)
    put("producerId", producerId)
    put("probeId", probeId)
  }.toString()
}

internal fun previewSurfaceProbeFinishParams(
  probeId: String,
  attachmentId: String,
): String {
  require(probeId.matches(probeIdPattern)) { "Invalid VScreen probe identity" }
  require(attachmentId.matches(attachmentIdPattern)) { "Invalid VScreen attachment identity" }
  return buildJsonObject {
    put("protocolVersion", VSCREEN_PROTOCOL_VERSION)
    put("probeId", probeId)
    put("attachmentId", attachmentId)
  }.toString()
}

internal fun parsePreviewSurfaceProbeDescriptor(
  raw: String,
  requestedProbeId: String,
  requestedProducerId: String,
  expectedTargetRef: String,
  json: Json,
  nowMs: Long,
): PreviewSurfaceProbeDescriptor? {
  if (!requestedProbeId.matches(probeIdPattern) || !requestedProducerId.matches(producerIdPattern)) return null
  val root = raw.objectOrNull(json) ?: return null
  if (
    root.int("protocolVersion") != VSCREEN_PROTOCOL_VERSION ||
    root.string("status") != "ready" ||
    root.string("kind") != "probe"
  ) {
    return null
  }
  val probeId = root.string("probeId")?.takeIf { it == requestedProbeId } ?: return null
  val attachmentId = root.string("attachmentId")?.takeIf(attachmentIdPattern::matches) ?: return null
  val producerId = root.string("producerId")?.takeIf { it == requestedProducerId } ?: return null
  val targetRef = root.string("targetRef")?.takeIf { it == expectedTargetRef && it.length <= 512 } ?: return null
  val streamPath = root.string("streamPath")?.takeIf { it == VSCREEN_ROUTE } ?: return null
  root.string("codec")?.takeIf { it == VSCREEN_CODEC } ?: return null
  val token = root.string("token")?.takeIf(tokenPattern::matches) ?: return null
  val expiresAtMs = root.long("expiresAtMs")?.takeIf { it > nowMs } ?: return null
  val display = root["display"] as? JsonObject ?: return null
  display.int("id")?.takeIf { it > 0 } ?: return null
  val width = display.int("width")?.takeIf { it in 1..4096 } ?: return null
  val height = display.int("height")?.takeIf { it in 1..4096 } ?: return null
  display.int("dpi")?.takeIf { it in 72..960 } ?: return null
  val capabilities = root.stringSet("capabilities") ?: return null
  if ("pointer-v1" in capabilities) return null
  return PreviewSurfaceProbeDescriptor(
    probeId,
    attachmentId,
    producerId,
    targetRef,
    streamPath,
    token,
    expiresAtMs,
    width,
    height,
  )
}

internal fun parsePreviewSurfaceProbeIdle(
  raw: String,
  requestedProbeId: String,
  json: Json,
): Boolean {
  if (!requestedProbeId.matches(probeIdPattern)) return false
  val root = raw.objectOrNull(json) ?: return false
  return root.int("protocolVersion") == VSCREEN_PROTOCOL_VERSION &&
    root.string("status") == "unavailable" &&
    root.string("probeId") == requestedProbeId
}

internal fun parseVScreenDescriptor(
  raw: String,
  requestedProducerId: String,
  requestedWorkloadId: String?,
  json: Json,
  nowMs: Long,
): VScreenDescriptor? {
  if (!requestedProducerId.matches(producerIdPattern)) return null
  if (requestedWorkloadId != null && !requestedWorkloadId.matches(requestIdPattern)) return null
  val root = raw.objectOrNull(json) ?: return null
  if (
    root.int("protocolVersion") != VSCREEN_PROTOCOL_VERSION ||
    root.string("status") !in setOf("ready", "streaming") ||
    root.string("kind") != "display"
  ) {
    return null
  }
  val attachmentId = root.string("attachmentId")?.takeIf(attachmentIdPattern::matches) ?: return null
  val producerId = root.string("producerId")?.takeIf { it == requestedProducerId } ?: return null
  val targetRef = root.string("targetRef")?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return null
  val targetGeneration = root.long("targetGeneration")?.takeIf { it > 0 } ?: return null
  val sourceGeneration = root.long("sourceGeneration")?.takeIf { it > 0 } ?: return null
  val streamPath = root.string("streamPath")?.takeIf { it == VSCREEN_ROUTE } ?: return null
  root.string("codec")?.takeIf { it == VSCREEN_CODEC } ?: return null
  val status = root.string("status") ?: return null
  val token = root.string("token")?.takeIf(tokenPattern::matches)
  val expiresAtMs = root.long("expiresAtMs")?.takeIf { it > nowMs } ?: 0L
  if (status == "ready" && (token == null || expiresAtMs == 0L)) return null
  val display = root["display"] as? JsonObject ?: return null
  val displayId = display.int("id")?.takeIf { it > 0 } ?: return null
  val width = display.int("width")?.takeIf { it in 1..4096 } ?: return null
  val height = display.int("height")?.takeIf { it in 1..4096 } ?: return null
  val dpi = display.int("dpi")?.takeIf { it in 72..960 } ?: return null
  val rotation = display.int("rotation")?.takeIf { it in 0..3 } ?: 0
  val capabilities = root.stringSet("capabilities") ?: return null
  if ("video/h264" !in capabilities) return null
  val workload = root["workload"] as? JsonObject
  val workloadId = workload?.string("requestId")?.takeIf(requestIdPattern::matches)
  val workloadGeneration = workload?.long("generation")?.takeIf { it > 0 }
  if ((workloadId == null) != (workloadGeneration == null)) return null
  if (requestedWorkloadId != null && workloadId != requestedWorkloadId) return null
  val foreground =
    when (root.string("foreground")) {
      "home" -> VScreenForeground.Home
      "workload" -> VScreenForeground.Workload
      "system_ui" -> VScreenForeground.SystemUi
      else -> VScreenForeground.Unknown
    }
  return VScreenDescriptor(
    attachmentId,
    producerId,
    targetRef,
    targetGeneration,
    sourceGeneration,
    workloadId,
    workloadGeneration,
    foreground,
    streamPath,
    token,
    expiresAtMs,
    displayId,
    width,
    height,
    dpi,
    rotation,
    capabilities,
  )
}

/** Accepts only Foundation workload events; lifecycle completion does not own VScreen. */
internal fun parseVScreenGatewayEvent(
  event: String,
  payloadJson: String?,
  json: Json,
): VScreenWorkloadEvent? {
  if (event != "agent" || payloadJson.isNullOrBlank()) return null
  val payload = payloadJson.objectOrNull(json) ?: return null
  if (payload.string("stream") != VSCREEN_AGENT_EVENT_STREAM) return null
  val sessionKey = payload.string("sessionKey")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val runId = payload.string("runId")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val data = payload["data"] as? JsonObject ?: return null
  if (data.int("protocolVersion") != VSCREEN_PROTOCOL_VERSION || data.string("phase") != "offered") return null
  val producerId = data.string("producerId")?.takeIf(producerIdPattern::matches) ?: return null
  val workloadRequestId = data.string("workloadRequestId")?.takeIf(requestIdPattern::matches) ?: return null
  val sessionId = data.string("executionSessionId")?.trim()?.takeIf(sessionIdPattern::matches) ?: return null
  val toolCallId = data.string("toolCallId")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val producerPayload = data["producerPayload"] as? JsonObject ?: return null
  return VScreenWorkloadEvent(
    run = VScreenRunReference(sessionKey, sessionId, runId),
    producerId = producerId,
    workloadRequestId = workloadRequestId,
    toolCallId = toolCallId,
    producerPayload = producerPayload,
  )
}

private fun String.objectOrNull(json: Json): JsonObject? = runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

private fun JsonObject.stringSet(key: String): Set<String>? {
  val values = this[key] as? JsonArray ?: return null
  return values.mapTo(linkedSetOf()) { element ->
    (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
  }
}
