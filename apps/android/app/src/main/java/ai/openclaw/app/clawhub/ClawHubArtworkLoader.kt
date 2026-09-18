package ai.openclaw.app.clawhub

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.coroutines.resume

internal data class ClawHubArtworkPayload(
  val bytes: ByteArray,
  val contentType: String,
)

/** Fetches only public artwork locations sanctioned for the official ClawHub catalog. */
internal class ClawHubArtworkLoader(
  private val httpClient: OkHttpClient,
  private val memoryCache: ClawHubArtworkMemoryCache = ClawHubArtworkMemoryCache(),
  private val urlPolicy: (HttpUrl) -> Boolean = ::isAllowedClawHubArtworkUrl,
) {
  suspend fun load(rawUrl: String): ClawHubArtworkPayload? {
    val url = rawUrl.toHttpUrlOrNull()?.takeIf(urlPolicy) ?: return null
    val cacheKey = url.toString()
    memoryCache.get(cacheKey)?.let { return it }
    val request =
      Request
        .Builder()
        .url(url)
        .get()
        .header("Accept", ALLOWED_ARTWORK_TYPES.joinToString(","))
        .build()
    return suspendCancellableCoroutine { continuation ->
      val call = httpClient.newCall(request)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
            if (continuation.isActive) continuation.resume(null)
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
            response.use {
              val payload = response.toArtworkPayload()
              if (payload != null) memoryCache.put(cacheKey, payload)
              if (continuation.isActive) continuation.resume(payload)
            }
          }
        },
      )
    }
  }
}

internal class ClawHubArtworkMemoryCache(
  private val maxBytes: Int = CLAWHUB_ARTWORK_MEMORY_CACHE_BYTES,
) {
  private val entries = LinkedHashMap<String, ClawHubArtworkPayload>(16, 0.75f, true)
  private var currentBytes = 0

  init {
    require(maxBytes > 0) { "Artwork memory cache size must be positive." }
  }

  @Synchronized
  fun get(key: String): ClawHubArtworkPayload? = entries[key]

  @Synchronized
  fun put(
    key: String,
    value: ClawHubArtworkPayload,
  ) {
    entries.remove(key)?.let { currentBytes -= it.bytes.size }
    if (value.bytes.size > maxBytes) return
    entries[key] = value
    currentBytes += value.bytes.size
    while (currentBytes > maxBytes) {
      val eldest = entries.entries.firstOrNull() ?: break
      currentBytes -= eldest.value.bytes.size
      entries.remove(eldest.key)
    }
  }
}

internal fun isAllowedClawHubArtworkUrl(url: HttpUrl): Boolean =
  url.isHttps &&
    url.port == 443 &&
    url.username.isEmpty() &&
    url.password.isEmpty() &&
    url.host.lowercase(Locale.US) in ALLOWED_ARTWORK_HOSTS

private fun Response.toArtworkPayload(): ClawHubArtworkPayload? {
  if (!isSuccessful || isRedirect) return null
  val body = body
  if (body.contentLength() > CLAWHUB_ARTWORK_MAX_BYTES) return null
  val contentType =
    body
      .contentType()
      ?.let { "${it.type}/${it.subtype}".lowercase(Locale.US) }
      ?.takeIf(ALLOWED_ARTWORK_TYPES::contains)
      ?: return null
  val buffer = Buffer()
  while (buffer.size <= CLAWHUB_ARTWORK_MAX_BYTES) {
    val remaining = CLAWHUB_ARTWORK_MAX_BYTES + 1L - buffer.size
    if (body.source().read(buffer, remaining) == -1L) break
  }
  if (buffer.size > CLAWHUB_ARTWORK_MAX_BYTES) return null
  return buffer
    .readByteArray()
    .takeIf(ByteArray::isNotEmpty)
    ?.let { ClawHubArtworkPayload(bytes = it, contentType = contentType) }
}

private val ALLOWED_ARTWORK_HOSTS = setOf("cdn.simpleicons.org")
private val ALLOWED_ARTWORK_TYPES = setOf("image/jpeg", "image/png", "image/svg+xml", "image/webp")
private const val CLAWHUB_ARTWORK_MAX_BYTES = 512 * 1024L
private const val CLAWHUB_ARTWORK_MEMORY_CACHE_BYTES = 4 * 1024 * 1024
