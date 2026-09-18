package ai.openclaw.app.androiddevice

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

private const val DEVICE_BRIDGE_CHALLENGE_RELATIVE_PATH = "Download/ClawInOne/developer-bridge/"

internal data class AndroidDeviceChallenge(
  val id: String,
  val storageLocator: String,
)

internal interface AndroidDeviceChallengeStore {
  suspend fun create(): AndroidDeviceChallenge

  suspend fun delete(challenge: AndroidDeviceChallenge)
}

internal class MediaStoreAndroidDeviceChallengeStore(
  context: Context,
  private val random: SecureRandom = SecureRandom(),
) : AndroidDeviceChallengeStore {
  private val appContext = context.applicationContext

  override suspend fun create(): AndroidDeviceChallenge =
    withContext(Dispatchers.IO) {
      val id = randomHex(16)
      val fileName = "challenge-$id.txt"
      val content = "${randomHex(32)}\n".toByteArray(Charsets.UTF_8)
      val resolver = appContext.contentResolver
      val values =
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, fileName)
          put(MediaStore.Downloads.MIME_TYPE, "text/plain")
          put(MediaStore.Downloads.RELATIVE_PATH, DEVICE_BRIDGE_CHALLENGE_RELATIVE_PATH)
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
      val uri =
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          ?: error("Android Downloads is unavailable")
      try {
        resolver.openOutputStream(uri, "wt")?.use { it.write(content) }
          ?: error("Could not write the same-phone challenge")
        check(
          resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
          ) == 1,
        ) { "Could not publish the same-phone challenge" }
        val publishedName =
          resolver
            .query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
              if (!cursor.moveToFirst()) null else cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
            }
        check(publishedName == fileName) { "Android renamed the same-phone challenge" }
        AndroidDeviceChallenge(id = id, storageLocator = uri.toString())
      } catch (error: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
      }
    }

  override suspend fun delete(challenge: AndroidDeviceChallenge) {
    withContext(Dispatchers.IO) {
      runCatching { appContext.contentResolver.delete(challenge.storageLocator.toUri(), null, null) }
    }
  }

  private fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    random.nextBytes(bytes)
    return bytes.joinToString("") { byte ->
      (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
  }
}
