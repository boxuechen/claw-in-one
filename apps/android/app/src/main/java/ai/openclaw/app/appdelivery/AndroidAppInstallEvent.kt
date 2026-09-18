package ai.openclaw.app.appdelivery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal const val ANDROID_APP_INSTALLED_EVENT_STREAM = "claw-in-one-android-app.installed"
private const val ANDROID_APP_INSTALLED_EVENT_PROTOCOL_VERSION = 1

/** Canonical successful installation emitted by App Delivery independently of VScreen. */
internal data class AndroidAppInstallEvent(
  val sessionKey: String,
  val sessionId: String,
  val runId: String,
  val toolCallId: String,
  val packageName: String,
  val versionCode: Long,
  val sha256: String,
)

internal fun parseAndroidAppInstallGatewayEvent(
  event: String,
  payloadJson: String?,
  json: Json,
): AndroidAppInstallEvent? {
  if (event != "agent" || payloadJson.isNullOrBlank()) return null
  val payload = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return null
  if (payload.string("stream") != ANDROID_APP_INSTALLED_EVENT_STREAM) return null
  val sessionKey = payload.string("sessionKey")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val runId = payload.string("runId")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val data = payload["data"] as? JsonObject ?: return null
  if (data.long("protocolVersion") != ANDROID_APP_INSTALLED_EVENT_PROTOCOL_VERSION.toLong()) return null
  if (data.string("phase") != "installed") return null
  val sessionId = data.string("executionSessionId")?.takeIf(SESSION_ID_PATTERN::matches) ?: return null
  val toolCallId = data.string("toolCallId")?.trim()?.takeIf(String::isNotEmpty) ?: return null
  val packageName = data.string("packageName")?.takeIf(ANDROID_PACKAGE_PATTERN::matches) ?: return null
  val versionCode = data.string("versionCode")?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
  val sha256 = data.string("sha256")?.takeIf(SHA256_PATTERN::matches) ?: return null
  return AndroidAppInstallEvent(
    sessionKey = sessionKey,
    sessionId = sessionId,
    runId = runId,
    toolCallId = toolCallId,
    packageName = packageName,
    versionCode = versionCode,
    sha256 = sha256,
  )
}

private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val ANDROID_PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
