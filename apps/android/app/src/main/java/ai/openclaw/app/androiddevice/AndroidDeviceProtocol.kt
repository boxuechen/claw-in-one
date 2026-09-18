package ai.openclaw.app.androiddevice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal const val ANDROID_DEVICE_PROTOCOL_VERSION = 1
internal const val ANDROID_DEVICE_STATUS_METHOD = "claw.androidDevice.status"
internal const val ANDROID_DEVICE_PAIR_METHOD = "claw.androidDevice.pair"
internal const val ANDROID_DEVICE_CONNECT_METHOD = "claw.androidDevice.connect"
internal const val ANDROID_DEVICE_VERIFY_RECONNECT_METHOD = "claw.androidDevice.verifyReconnect"
internal const val ANDROID_DEVICE_FORGET_METHOD = "claw.androidDevice.forget"

internal val androidDeviceMethods =
  setOf(
    ANDROID_DEVICE_STATUS_METHOD,
    ANDROID_DEVICE_PAIR_METHOD,
    ANDROID_DEVICE_CONNECT_METHOD,
    ANDROID_DEVICE_VERIFY_RECONNECT_METHOD,
    ANDROID_DEVICE_FORGET_METHOD,
  )

internal enum class AndroidDeviceStatus(
  val wireValue: String,
) {
  SetupRequired("setup_required"),
  Pairing("pairing"),
  Ready("ready"),
  Offline("offline"),
  Connecting("connecting"),
  Forgetting("forgetting"),
  Revoked("revoked"),
  Unavailable("unavailable"),
  ;

  companion object {
    fun fromWire(value: String?): AndroidDeviceStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

internal data class AndroidDeviceTarget(
  val id: String,
  val product: String,
  val model: String,
  val androidApi: Int,
)

internal data class AndroidDeviceSnapshot(
  val status: AndroidDeviceStatus,
  val paired: Boolean,
  val connected: Boolean,
  val reasonCode: String?,
  val verificationId: String?,
  val target: AndroidDeviceTarget?,
)

internal fun androidDeviceStatusParams(): String = buildJsonObject { put("protocolVersion", JsonPrimitive(ANDROID_DEVICE_PROTOCOL_VERSION)) }.toString()

internal fun androidDevicePairParams(
  endpoint: String,
  pairingCode: String,
  challengeId: String,
): String =
  buildJsonObject {
    put("protocolVersion", JsonPrimitive(ANDROID_DEVICE_PROTOCOL_VERSION))
    put("endpoint", JsonPrimitive(endpoint))
    put("pairingCode", JsonPrimitive(pairingCode))
    put("challengeId", JsonPrimitive(challengeId))
  }.toString()

internal fun androidDeviceConnectParams(endpoint: String?): String =
  buildJsonObject {
    put("protocolVersion", JsonPrimitive(ANDROID_DEVICE_PROTOCOL_VERSION))
    endpoint?.let { put("endpoint", JsonPrimitive(it)) }
  }.toString()

internal fun androidDeviceVerifyReconnectParams(
  verificationId: String,
  endpoint: String?,
): String =
  buildJsonObject {
    put("protocolVersion", JsonPrimitive(ANDROID_DEVICE_PROTOCOL_VERSION))
    put("verificationId", JsonPrimitive(verificationId))
    endpoint?.let { put("endpoint", JsonPrimitive(it)) }
  }.toString()

internal fun androidDeviceForgetParams(): String = androidDeviceStatusParams()

internal fun parseAndroidDeviceSnapshot(
  raw: String,
  json: Json,
): AndroidDeviceSnapshot? {
  val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
  if (root.int("protocolVersion") != ANDROID_DEVICE_PROTOCOL_VERSION) return null
  val status = AndroidDeviceStatus.fromWire(root.string("status")) ?: return null
  val paired = root.boolean("paired") ?: return null
  val connected = root.boolean("connected") ?: return null
  val verificationId =
    when (val value = root["verificationId"]) {
      null,
      JsonNull,
      -> null
      is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
      else -> return null
    }
  val target = (root["target"] as? JsonObject)?.toAndroidDeviceTarget()
  if ((root["target"] != null && root["target"] !is kotlinx.serialization.json.JsonNull) && target == null) return null
  if (paired != (target != null) || (connected && (!paired || status != AndroidDeviceStatus.Ready))) return null
  if (status == AndroidDeviceStatus.Ready && !connected) return null
  if (status == AndroidDeviceStatus.Offline && !paired) return null
  if (verificationId != null && status != AndroidDeviceStatus.Ready) return null
  if ((status == AndroidDeviceStatus.SetupRequired || status == AndroidDeviceStatus.Revoked) && paired) return null
  return AndroidDeviceSnapshot(
    status = status,
    paired = paired,
    connected = connected,
    reasonCode = root.string("reasonCode"),
    verificationId = verificationId,
    target = target,
  )
}

private fun JsonObject.toAndroidDeviceTarget(): AndroidDeviceTarget? {
  val id = string("id") ?: return null
  if (!id.matches(Regex("[0-9a-f]{64}"))) return null
  val product = string("product")?.takeIf { it.length in 1..128 } ?: return null
  val model = string("model")?.takeIf { it.length in 1..128 } ?: return null
  val androidApi = string("androidApi")?.toIntOrNull()?.takeIf { it in 21..100 } ?: return null
  return AndroidDeviceTarget(id = id, product = product, model = model, androidApi = androidApi)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
