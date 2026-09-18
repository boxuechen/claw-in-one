package ai.openclaw.app.webdelivery

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class WebProjectResultStorage(
  private val context: Context,
) {
  private val preferences = context.getSharedPreferences("web-project-results", Context.MODE_PRIVATE)
  private val json = Json { ignoreUnknownKeys = true }

  fun load(): List<WebProjectResult> =
    runCatching {
      json.decodeFromString<List<WebProjectResult>>(preferences.getString("results", "[]") ?: "[]")
    }.getOrDefault(emptyList())

  fun save(results: List<WebProjectResult>) {
    preferences.edit { putString("results", json.encodeToString(results)) }
  }

  @Suppress("DEPRECATION")
  fun openInChrome(url: String) {
    val intent =
      Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        setPackage("com.android.chrome")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    check(intent.resolveActivity(context.packageManager)?.packageName == "com.android.chrome") {
      "Android Chrome is unavailable"
    }
    context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
  }
}
