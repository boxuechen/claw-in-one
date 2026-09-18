package ai.openclaw.app.appdelivery

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** App-private history survives receipt expiry. Every user launch still checks the installed bytes. */
@Suppress("DEPRECATION")
internal class AndroidAppResultStorage(
  private val context: Context,
) {
  private val preferences = context.getSharedPreferences("android-app-results", Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  fun load(): List<AndroidAppResult> = runCatching { json.decodeFromString<List<AndroidAppResult>>(preferences.getString("results", "[]") ?: "[]") }.getOrDefault(emptyList())

  fun save(results: List<AndroidAppResult>) {
    preferences.edit { putString("results", json.encodeToString(results)) }
  }

  suspend fun verifyInstalled(result: AndroidAppResult): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(result.packageName, 0)
        if (info.longVersionCode != result.versionCode) return@runCatching false
        val source = info.applicationInfo?.sourceDir ?: return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256")
        File(source).inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
          }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val after = pm.getPackageInfo(result.packageName, 0)
        sha256 == result.sha256 && after.longVersionCode == result.versionCode && after.lastUpdateTime == info.lastUpdateTime && after.applicationInfo?.sourceDir == source
      }.getOrDefault(false)
    }

  fun displayName(packageName: String): String =
    runCatching {
      context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

  fun open(result: AndroidAppResult) {
    val intent = context.packageManager.getLaunchIntentForPackage(result.packageName) ?: error("App has no launcher")
    check(intent.component?.packageName == result.packageName)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
  }
}
