package ai.openclaw.app.gateway

/** Immutable transport address derived from the Supervisor-provided local pairing. */
data class GatewayEndpoint(
  val stableId: String,
  val name: String,
  val host: String,
  val port: Int,
  val tlsEnabled: Boolean = false,
  val tlsFingerprintSha256: String? = null,
  val contextPath: String = "",
)

internal fun normalizeGatewayContextPath(value: String?): String {
  val path = value.orEmpty()
  if (path.isEmpty() || path == "/") return ""
  val prefixed = if (path.startsWith('/')) path else "/$path"
  val encoded = StringBuilder(prefixed.length)
  var index = 0
  while (index < prefixed.length) {
    if (
      prefixed[index] == '%' &&
      index + 2 < prefixed.length &&
      prefixed[index + 1].isAsciiHexDigit() &&
      prefixed[index + 2].isAsciiHexDigit()
    ) {
      encoded.append(prefixed, index, index + 3)
      index += 3
      continue
    }
    val codePoint = prefixed.codePointAt(index)
    if (isGatewayPathCodePoint(codePoint)) {
      encoded.appendCodePoint(codePoint)
    } else {
      for (byte in String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)) {
        val value = byte.toInt() and 0xff
        encoded.append('%')
        encoded.append(HEX_DIGITS[value ushr 4])
        encoded.append(HEX_DIGITS[value and 0x0f])
      }
    }
    index += Character.charCount(codePoint)
  }
  return encoded.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

private fun isGatewayPathCodePoint(value: Int): Boolean =
  value == '/'.code ||
    value == ':'.code ||
    value == '@'.code ||
    value in 'A'.code..'Z'.code ||
    value in 'a'.code..'z'.code ||
    value in '0'.code..'9'.code ||
    (value <= 0x7f && value.toChar() in "-._~!$&'()*+,;=")
