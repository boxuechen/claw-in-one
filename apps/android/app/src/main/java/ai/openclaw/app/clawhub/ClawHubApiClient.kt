package ai.openclaw.app.clawhub

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.ceil

internal data class ClawHubGetRequest(
  val pathSegments: List<String>,
  val queryParameters: List<Pair<String, String>> = emptyList(),
) {
  init {
    require(pathSegments.isNotEmpty()) { "A ClawHub path is required." }
    require(pathSegments.none { it.isBlank() || it == "." || it == ".." }) {
      "ClawHub path segments must be explicit."
    }
    require(queryParameters.none { (name, _) -> name.isBlank() }) {
      "ClawHub query parameter names must not be blank."
    }
  }
}

internal enum class ClawHubResponseSource {
  Network,
  Memory,
}

internal data class ClawHubDocument(
  val body: JsonObject,
  val source: ClawHubResponseSource,
)

internal sealed interface ClawHubApiResult {
  data class Success(
    val document: ClawHubDocument,
  ) : ClawHubApiResult

  data class Failure(
    val error: ClawHubApiError,
  ) : ClawHubApiResult
}

internal sealed interface ClawHubApiError {
  data class RateLimited(
    val retryAfterSeconds: Long?,
  ) : ClawHubApiError

  data class Http(
    val statusCode: Int,
    val safeMessage: String?,
  ) : ClawHubApiError

  data object Network : ClawHubApiError

  data object InvalidResponse : ClawHubApiError

  data object ResponseTooLarge : ClawHubApiError
}

internal fun interface ClawHubApiClient {
  suspend fun get(request: ClawHubGetRequest): ClawHubApiResult
}

internal class OkHttpClawHubApiClient(
  private val httpClient: OkHttpClient,
  private val baseUrl: HttpUrl = CLAWHUB_BASE_URL,
  private val userAgent: String,
  private val memoryCache: ClawHubMemoryCache = ClawHubMemoryCache(),
  private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ClawHubApiClient {
  override suspend fun get(request: ClawHubGetRequest): ClawHubApiResult {
    val url = request.toUrl(baseUrl)
    memoryCache.get(url.toString())?.let { body ->
      return ClawHubApiResult.Success(
        ClawHubDocument(body = body, source = ClawHubResponseSource.Memory),
      )
    }

    val httpRequest =
      Request
        .Builder()
        .url(url)
        .get()
        .header("Accept", "application/json")
        .header("User-Agent", userAgent)
        .build()

    return suspendCancellableCoroutine { continuation ->
      val call = httpClient.newCall(httpRequest)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(
            call: Call,
            e: IOException,
          ) {
            continuation.resume(ClawHubApiResult.Failure(ClawHubApiError.Network))
          }

          override fun onResponse(
            call: Call,
            response: Response,
          ) {
            response.use {
              val result =
                try {
                  response.toClawHubResult(nowEpochMillis())
                } catch (_: IOException) {
                  ClawHubApiResult.Failure(ClawHubApiError.Network)
                }
              if (result is ClawHubApiResult.Success) {
                memoryCache.put(url.toString(), result.document.body)
              }
              continuation.resume(result)
            }
          }
        },
      )
    }
  }
}

internal object ClawHubHttpClientFactory {
  fun create(cacheDirectory: File): OkHttpClient =
    OkHttpClient
      .Builder()
      .cache(Cache(File(cacheDirectory, "clawhub-http"), CLAWHUB_DISK_CACHE_BYTES))
      .cookieJar(CookieJar.NO_COOKIES)
      .authenticator(Authenticator.NONE)
      .proxyAuthenticator(Authenticator.NONE)
      .followRedirects(false)
      .followSslRedirects(false)
      .retryOnConnectionFailure(false)
      .connectTimeout(CLAWHUB_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(CLAWHUB_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .callTimeout(CLAWHUB_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .build()
}

internal class ClawHubMemoryCache(
  private val maxEntries: Int = CLAWHUB_MEMORY_CACHE_ENTRIES,
  private val maxAgeMillis: Long = CLAWHUB_MEMORY_CACHE_MAX_AGE_MILLIS,
  private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
  private data class Entry(
    val body: JsonObject,
    val expiresAtMillis: Long,
  )

  private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)

  init {
    require(maxEntries > 0) { "ClawHub memory cache must retain at least one entry." }
    require(maxAgeMillis > 0) { "ClawHub memory cache age must be positive." }
  }

  @Synchronized
  fun get(key: String): JsonObject? {
    val entry = entries[key] ?: return null
    if (entry.expiresAtMillis <= monotonicMillis()) {
      entries.remove(key)
      return null
    }
    return entry.body
  }

  @Synchronized
  fun put(
    key: String,
    body: JsonObject,
  ) {
    entries[key] = Entry(body = body, expiresAtMillis = monotonicMillis() + maxAgeMillis)
    while (entries.size > maxEntries) {
      entries.remove(entries.entries.first().key)
    }
  }
}

private fun ClawHubGetRequest.toUrl(baseUrl: HttpUrl): HttpUrl =
  baseUrl
    .newBuilder()
    .apply {
      pathSegments.forEach(::addPathSegment)
      queryParameters.forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()

private fun Response.toClawHubResult(nowEpochMillis: Long): ClawHubApiResult {
  if (code == 429) {
    return ClawHubApiResult.Failure(
      ClawHubApiError.RateLimited(
        retryAfterSeconds = parseRetryAfterSeconds(header("Retry-After"), nowEpochMillis),
      ),
    )
  }
  if (!isSuccessful) {
    val safeMessage = body.readBounded(CLAWHUB_ERROR_MAX_BYTES)?.decodeToString()?.safeServerMessage()
    return ClawHubApiResult.Failure(
      ClawHubApiError.Http(statusCode = code, safeMessage = safeMessage),
    )
  }
  val contentType = body.contentType()
  if (contentType != null && contentType.subtype != "json" && !contentType.subtype.endsWith("+json")) {
    return ClawHubApiResult.Failure(ClawHubApiError.InvalidResponse)
  }
  val bytes =
    body.readBounded(CLAWHUB_RESPONSE_MAX_BYTES)
      ?: return ClawHubApiResult.Failure(ClawHubApiError.ResponseTooLarge)
  val jsonObject =
    try {
      ClawHubJson.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (_: SerializationException) {
      return ClawHubApiResult.Failure(ClawHubApiError.InvalidResponse)
    } catch (_: IllegalArgumentException) {
      return ClawHubApiResult.Failure(ClawHubApiError.InvalidResponse)
    }
  return ClawHubApiResult.Success(
    ClawHubDocument(body = jsonObject, source = ClawHubResponseSource.Network),
  )
}

private fun okhttp3.ResponseBody.readBounded(maxBytes: Long): ByteArray? {
  val contentLength = contentLength()
  if (contentLength > maxBytes) return null
  val buffer = Buffer()
  source().use { source ->
    while (buffer.size <= maxBytes) {
      val remaining = maxBytes + 1L - buffer.size
      if (source.read(buffer, remaining) == -1L) break
    }
  }
  if (buffer.size > maxBytes) return null
  return buffer.readByteArray()
}

private fun String.safeServerMessage(): String? =
  lineSequence()
    .joinToString(separator = " ") { it.trim() }
    .trim()
    .take(CLAWHUB_SAFE_MESSAGE_MAX_CHARS)
    .takeIf(String::isNotEmpty)

private fun parseRetryAfterSeconds(
  rawValue: String?,
  nowEpochMillis: Long,
): Long? {
  val value = rawValue?.trim()?.takeIf(String::isNotEmpty) ?: return null
  value.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
  val retryAt =
    runCatching {
      ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull() ?: return null
  val remainingMillis = retryAt.toEpochMilli() - Instant.ofEpochMilli(nowEpochMillis).toEpochMilli()
  return ceil(remainingMillis.coerceAtLeast(0L) / 1_000.0).toLong()
}

private val CLAWHUB_BASE_URL = "https://clawhub.ai/".toHttpUrl()
private const val CLAWHUB_DISK_CACHE_BYTES = 20L * 1024L * 1024L
private const val CLAWHUB_RESPONSE_MAX_BYTES = 2L * 1024L * 1024L
private const val CLAWHUB_ERROR_MAX_BYTES = 4L * 1024L
private const val CLAWHUB_SAFE_MESSAGE_MAX_CHARS = 160
private const val CLAWHUB_MEMORY_CACHE_ENTRIES = 64
private const val CLAWHUB_MEMORY_CACHE_MAX_AGE_MILLIS = 5L * 60L * 1_000L
private const val CLAWHUB_CONNECT_TIMEOUT_SECONDS = 10L
private const val CLAWHUB_READ_TIMEOUT_SECONDS = 15L
private const val CLAWHUB_CALL_TIMEOUT_SECONDS = 20L
