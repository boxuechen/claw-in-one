package ai.openclaw.app.ownership

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Opaque process-local fingerprint used to correlate exact Chat-owned device resources. */
internal fun taskOwnerKey(sessionKey: String): String =
  MessageDigest
    .getInstance("SHA-256")
    .digest(sessionKey.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
