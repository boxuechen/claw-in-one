package ai.openclaw.app.bootstrap

import ai.openclaw.app.supervisor.SUPERVISOR_CONTROL_PROTOCOL_VERSION
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

private const val BOOTSTRAP_ASSET = "claw-in-one/bootstrap.sh"
private const val BOOTSTRAP_FILE_NAME = "bootstrap.sh"
private const val SUPERVISOR_INSTALLER_ASSET = "claw-in-one/install-supervisor.sh"
private const val SUPERVISOR_INSTALLER_FILE_NAME = "install-supervisor.sh"
private const val SUPERVISOR_BINARY_ASSET = "claw-in-one/claw-in-one-supervisor"
private const val SUPERVISOR_BINARY_FILE_NAME = "claw-in-one-supervisor"
private const val GATEWAY_ASSET = "claw-in-one/start-gateway.sh"
private const val GATEWAY_FILE_NAME = "start-gateway.sh"
private const val GATEWAY_CONFIG_CACHE_ASSET = "claw-in-one/gateway-config-cache.sh"
private const val GATEWAY_CONFIG_CACHE_FILE_NAME = "gateway-config-cache.sh"
private const val PRODUCT_PLUGIN_INSTALLER_ASSET = "claw-in-one/install-bundled-product-plugins.mjs"
private const val PRODUCT_PLUGIN_INSTALLER_FILE_NAME = "install-bundled-product-plugins.mjs"
private const val PLUGIN_ALLOWLIST_ASSET = "claw-in-one/reconcile-plugin-allowlist.mjs"
private const val PLUGIN_ALLOWLIST_FILE_NAME = "reconcile-plugin-allowlist.mjs"
private const val PLUGIN_PATHS_ASSET = "claw-in-one/reconcile-plugin-paths.mjs"
private const val PLUGIN_PATHS_FILE_NAME = "reconcile-plugin-paths.mjs"
private const val SUPERVISION_POLICY_ASSET = "claw-in-one/supervision-policy.sh"
private const val SUPERVISION_POLICY_FILE_NAME = "supervision-policy.sh"
private const val ANDROID_USE_DIRECTORY = "android-use/"
private const val ANDROID_USE_ENTRY_ASSET = "claw-in-one/android-use/index.mjs"
private const val ANDROID_USE_ENTRY_FILE_NAME = "index.mjs"
private const val ANDROID_USE_RUNTIME_ASSET = "claw-in-one/android-use/runtime.mjs"
private const val ANDROID_USE_RUNTIME_FILE_NAME = "runtime.mjs"
private const val ANDROID_USE_AUTHORIZATION_ASSET = "claw-in-one/android-use/tool-authorization.mjs"
private const val ANDROID_USE_AUTHORIZATION_FILE_NAME = "tool-authorization.mjs"
private const val ANDROID_USE_MANIFEST_ASSET = "claw-in-one/android-use/openclaw.plugin.json"
private const val ANDROID_USE_MANIFEST_FILE_NAME = "openclaw.plugin.json"
private const val ANDROID_USE_PACKAGE_ASSET = "claw-in-one/android-use/package.json"
private const val ANDROID_USE_PACKAGE_FILE_NAME = "package.json"
private const val ANDROID_USE_SKILL_DIRECTORY = "product-skills/android-use/"
private const val ANDROID_USE_SKILL_ASSET = "claw-in-one/product-skills/android-use/SKILL.md"
private const val ANDROID_USE_SKILL_FILE_NAME = "SKILL.md"
private const val VSCREEN_FOUNDATION_DIRECTORY = "vscreen-foundation/"
private val VSCREEN_FOUNDATION_ASSETS =
  listOf(
    "index.mjs" to "text/javascript",
    "runtime.mjs" to "text/javascript",
    "protocol.mjs" to "text/javascript",
    "workload-registry.mjs" to "text/javascript",
    "producer-registry.mjs" to "text/javascript",
    "remote-producer.mjs" to "text/javascript",
    "display-service.mjs" to "text/javascript",
    "source-relay.mjs" to "text/javascript",
    "websocket.mjs" to "text/javascript",
    "openclaw.plugin.json" to "application/json",
    "package.json" to "application/json",
  )
private const val ANDROID_DEVELOPER_BRIDGE_DIRECTORY = "android-developer-bridge/"
private val ANDROID_DEVELOPER_BRIDGE_ASSETS =
  listOf(
    Triple("", "index.mjs", "text/javascript"),
    Triple("", "runtime.mjs", "text/javascript"),
    Triple("bridge/", "device-bridge.mjs", "text/javascript"),
    Triple("bridge/", "protocol.mjs", "text/javascript"),
    Triple("bridge/", "reverse-port.mjs", "text/javascript"),
    Triple("app-delivery/", "artifact-service.mjs", "text/javascript"),
    Triple("app-delivery/", "device-service.mjs", "text/javascript"),
    Triple("app-delivery/", "install-approval.mjs", "text/javascript"),
    Triple("app-delivery/", "install-result.mjs", "text/javascript"),
    Triple("app-delivery/", "vscreen-assignment.mjs", "text/javascript"),
    Triple("app-delivery/", "tool.mjs", "text/javascript"),
    Triple("app-delivery/", "tool-protocol.mjs", "text/javascript"),
    Triple("app-delivery/", "tool-security.mjs", "text/javascript"),
    Triple("project-build/", "tool.mjs", "text/javascript"),
    Triple("project-build/", "tool-protocol.mjs", "text/javascript"),
    Triple("project-build/", "authorization.mjs", "text/javascript"),
    Triple("vscreen/", "device-producer.mjs", "text/javascript"),
    Triple("vscreen/", "producer.mjs", "text/javascript"),
    Triple("vscreen/", "rpc.mjs", "text/javascript"),
    Triple("vscreen/", "readiness.mjs", "text/javascript"),
    Triple("vscreen/", "helper.mjs", "text/javascript"),
    Triple("", "openclaw.plugin.json", "application/json"),
    Triple("", "package.json", "application/json"),
    Triple("skills/android-development/", "SKILL.md", "text/markdown"),
    Triple("skills/android-native-development/", "SKILL.md", "text/markdown"),
    Triple("skills/flutter-development/", "SKILL.md", "text/markdown"),
    Triple("skills/godot-android-development/", "SKILL.md", "text/markdown"),
    Triple("skills/react-native-development/", "SKILL.md", "text/markdown"),
  )
private const val WEB_DEVELOPMENT_DIRECTORY = "web-development/"
private val WEB_DEVELOPMENT_ASSETS =
  listOf(
    Triple("", "index.mjs", "text/javascript"),
    Triple("", "runtime.mjs", "text/javascript"),
    Triple("", "authorization.mjs", "text/javascript"),
    Triple("", "project-service.mjs", "text/javascript"),
    Triple("", "result-store.mjs", "text/javascript"),
    Triple("", "tool-protocol.mjs", "text/javascript"),
    Triple("", "openclaw.plugin.json", "application/json"),
    Triple("", "package.json", "application/json"),
    Triple("skills/web-development/", "SKILL.md", "text/markdown"),
  )
private const val PROJECT_WORKSPACES_DIRECTORY = "project-workspaces/"
private val PROJECT_WORKSPACES_ASSETS =
  listOf(
    "index.mjs" to "text/javascript",
    "runtime.mjs" to "text/javascript",
    "capability-readiness.mjs" to "text/javascript",
    "project-query.mjs" to "text/javascript",
    "project-service.mjs" to "text/javascript",
    "protocol.mjs" to "text/javascript",
    "openclaw.plugin.json" to "application/json",
    "package.json" to "application/json",
  )
private const val ANDROID_KOTLIN_PROFILE_DIRECTORY = "android-kotlin-compose-v1/"
private const val ANDROID_NATIVE_PROFILE_DIRECTORY = "android-native-vulkan-v1/"
private const val FLUTTER_PROFILE_DIRECTORY = "flutter-android-v1/"
private const val GODOT_PROFILE_DIRECTORY = "godot-android-v1/"
private const val REACT_NATIVE_PROFILE_DIRECTORY = "react-native-android-v1/"
private val REACT_NATIVE_PROFILE_ASSETS =
  listOf(
    Triple("", "release.json", "application/json"),
    Triple("", "new-project.mjs", "text/javascript"),
    Triple("", "build-project.mjs", "text/javascript"),
    Triple("template/", "App.tsx", "application/octet-stream"),
    Triple("template/", "app.json", "application/json"),
    Triple("template/", "babel.config.js", "text/javascript"),
    Triple("template/", "index.js", "text/javascript"),
    Triple("template/", "jest.config.js", "text/javascript"),
    Triple("template/", "metro.config.js", "text/javascript"),
    Triple("template/", "package.json", "application/json"),
    Triple("template/", "package-lock.json", "application/json"),
    Triple("template/", "tsconfig.json", "application/json"),
    Triple("template/tests/", "App.test.tsx", "application/octet-stream"),
    Triple("template/android/", "build.gradle", "application/octet-stream"),
    Triple("template/android/", "gradle.properties", "application/octet-stream"),
    Triple("template/android/", "settings.gradle", "application/octet-stream"),
    Triple("template/android/app/", "build.gradle", "application/octet-stream"),
    Triple("template/android/app/", "proguard-rules.pro", "application/octet-stream"),
    Triple("template/android/app/src/main/", "AndroidManifest.xml", "text/xml"),
    Triple(
      "template/android/app/src/main/java/starter/",
      "MainActivity.kt",
      "application/octet-stream",
    ),
    Triple(
      "template/android/app/src/main/java/starter/",
      "MainApplication.kt",
      "application/octet-stream",
    ),
    Triple(
      "template/android/app/src/main/res/drawable/",
      "rn_edit_text_material.xml",
      "text/xml",
    ),
    Triple("template/android/app/src/main/res/values/", "strings.xml", "text/xml"),
    Triple("template/android/app/src/main/res/values/", "styles.xml", "text/xml"),
  )
private const val WEB_PROFILE_DIRECTORY = "web-development-v1/"
private val WEB_PROFILE_ASSETS =
  listOf(
    Triple("", "release.json", "application/json"),
    Triple("", "new-project.mjs", "text/javascript"),
    Triple("", "build-project.mjs", "text/javascript"),
    Triple("", "serve-project.mjs", "text/javascript"),
    Triple("template/", "package.json", "application/json"),
    Triple("template/", "package-lock.json", "application/json"),
    Triple("template/", "index.html", "text/html"),
    Triple("template/", "tsconfig.json", "application/json"),
    Triple("template/", "server.mjs", "text/javascript"),
    Triple("template/src/", "main.tsx", "application/octet-stream"),
    Triple("template/src/", "style.css", "text/css"),
  )
private const val HANDOFF_ENV_FILE_NAME = "bootstrap.env"
private const val HANDOFF_EVENTS_FILE_NAME = "handoff.events"
private const val SUPERVISOR_COMMAND_FILE_NAME = "supervisor.command"
private const val SUPERVISOR_STATUS_FILE_NAME = "supervisor.status"
private const val BOOTSTRAP_BUNDLE_ID_LENGTH = 32
private const val BOOTSTRAP_ROOT_RELATIVE_PATH = "Download/ClawInOne"

internal data class BootstrapPublication(
  val relativePath: String,
  val handoffRelativePath: String,
  val linuxSharedDirectory: String,
  val linuxHandoffDirectory: String,
  val linuxEventsFile: String,
  val supervisorRelativePath: String,
  val linuxSupervisorCommandFile: String,
  val linuxSupervisorStatusFile: String,
  val command: String,
)

internal fun bootstrapPublication(
  sourceContents: Map<String, ByteArray>,
  handoffId: String,
  release: OpenClawReleaseManifest = PINNED_OPENCLAW_RELEASE,
): BootstrapPublication {
  require(handoffId.matches(Regex("[0-9a-f]{32}"))) { "Invalid Bootstrap handoff ID" }
  val identity =
    buildString {
      append("protocol=").append(BOOTSTRAP_HANDOFF_PROTOCOL_VERSION).append('\n')
      append("supervisor.protocol=").append(SUPERVISOR_CONTROL_PROTOCOL_VERSION).append('\n')
      append("openclaw.version=").append(release.version).append('\n')
      append("node.version=").append(release.nodeVersion).append('\n')
      append("node.url=").append(release.nodeArchiveUrl).append('\n')
      append("node.sizeBytes=").append(release.nodeArchiveSizeBytes).append('\n')
      append("node.sha256=").append(release.nodeArchiveSha256).append('\n')
      append("openclaw.url=").append(release.packageUrl).append('\n')
      append("openclaw.sizeBytes=").append(release.packageSizeBytes).append('\n')
      append("openclaw.integrity=").append(release.packageIntegrity).append('\n')
      append("openclaw.signature=").append(release.packageSignature).append('\n')
      append("registry.keyId=").append(release.registryKeyId).append('\n')
      append("registry.publicKey=").append(release.registryPublicKey).append('\n')
      sourceContents.toSortedMap().forEach { (fileName, content) ->
        append("source.")
          .append(fileName)
          .append('=')
          .append(sha256(content))
          .append('\n')
      }
    }
  val bundleId =
    sha256(identity.toByteArray(StandardCharsets.UTF_8)).take(BOOTSTRAP_BUNDLE_ID_LENGTH)
  val relativePath = "$BOOTSTRAP_ROOT_RELATIVE_PATH/bootstrap-$bundleId/"
  val handoffRelativePath = "$BOOTSTRAP_ROOT_RELATIVE_PATH/handoff-$handoffId/"
  val supervisorRelativePath = "$BOOTSTRAP_ROOT_RELATIVE_PATH/supervisor-$handoffId/"
  val linuxSharedDirectory = "/mnt/shared/${relativePath.removeSuffix("/")}"
  val linuxHandoffDirectory = "/mnt/shared/${handoffRelativePath.removeSuffix("/")}"
  val linuxSupervisorDirectory = "/mnt/shared/${supervisorRelativePath.removeSuffix("/")}"
  return BootstrapPublication(
    relativePath = relativePath,
    handoffRelativePath = handoffRelativePath,
    linuxSharedDirectory = linuxSharedDirectory,
    linuxHandoffDirectory = linuxHandoffDirectory,
    linuxEventsFile = "$linuxHandoffDirectory/$HANDOFF_EVENTS_FILE_NAME",
    supervisorRelativePath = supervisorRelativePath,
    linuxSupervisorCommandFile = "$linuxSupervisorDirectory/$SUPERVISOR_COMMAND_FILE_NAME",
    linuxSupervisorStatusFile = "$linuxSupervisorDirectory/$SUPERVISOR_STATUS_FILE_NAME",
    command =
      "CLAW_IN_ONE_CONFIG_FILE='$linuxHandoffDirectory/$HANDOFF_ENV_FILE_NAME' " +
        "bash '$linuxSharedDirectory/$BOOTSTRAP_FILE_NAME'",
  )
}

private fun sha256(content: ByteArray): String =
  MessageDigest
    .getInstance("SHA-256")
    .digest(content)
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun generateBootstrapHandoffId(random: SecureRandom = SecureRandom()): String = randomHex(random, byteCount = 16)

internal fun generateBootstrapHandoffSecret(random: SecureRandom = SecureRandom()): String = randomHex(random, byteCount = 32)

internal fun generateSupervisorSecret(random: SecureRandom = SecureRandom()): String = randomHex(random, byteCount = 32)

private fun randomHex(
  random: SecureRandom,
  byteCount: Int,
): String {
  val bytes = ByteArray(byteCount)
  random.nextBytes(bytes)
  return bytes.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal enum class BootstrapDeliveryError {
  StorageUnavailable,
  WriteFailed,
}

internal sealed interface BootstrapPublishResult {
  data class Published(
    val uri: String,
    val command: String,
  ) : BootstrapPublishResult

  data class Failed(
    val error: BootstrapDeliveryError,
  ) : BootstrapPublishResult
}

internal sealed interface BootstrapDeliveryState {
  data object NotPrepared : BootstrapDeliveryState

  data object Preparing : BootstrapDeliveryState

  data class Ready(
    val uri: String,
    val command: String,
  ) : BootstrapDeliveryState

  data class Failed(
    val error: BootstrapDeliveryError,
  ) : BootstrapDeliveryState
}

internal fun interface BootstrapPublisher {
  suspend fun publish(): BootstrapPublishResult
}

internal class MediaStoreBootstrapPublisher(
  context: Context,
  private val repository: BootstrapHandoffRepository,
) : BootstrapPublisher {
  private val appContext = context.applicationContext

  override suspend fun publish(): BootstrapPublishResult =
    withContext(Dispatchers.IO) {
      repository.loadActive()?.let { active ->
        return@withContext BootstrapPublishResult.Published(active.bootstrapUri, active.command)
      }
      repository.discardActive()

      val resolver = appContext.contentResolver
      val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
      val bundleSources = bootstrapSources()
      val supervisorBinarySha256 =
        sha256(bundleSources.single { it.fileName == SUPERVISOR_BINARY_FILE_NAME }.content)
      val requestId = generateBootstrapHandoffId()
      val handoffSecret = generateBootstrapHandoffSecret()
      var supervisorSecret = generateSupervisorSecret()
      while (supervisorSecret == handoffSecret) {
        supervisorSecret = generateSupervisorSecret()
      }
      val publication =
        bootstrapPublication(
          sourceContents = bundleSources.associate { (it.relativePath + it.fileName) to it.content },
          handoffId = requestId,
        )
      val inserted = mutableListOf<Uri>()

      try {
        var bootstrapUri: Uri? = null
        bundleSources.forEach { source ->
          val uri = publishImmutableSource(source.copy(relativePath = publication.relativePath + source.relativePath))
          if (source.fileName == BOOTSTRAP_FILE_NAME) bootstrapUri = uri
        }

        val environmentUri =
          publishNewSource(
            BootstrapSource(
              relativePath = publication.handoffRelativePath,
              fileName = HANDOFF_ENV_FILE_NAME,
              mimeType = "application/octet-stream",
              content =
                supervisorEnvironment(
                  requestId = requestId,
                  handoffSecretHex = handoffSecret,
                  supervisorSecretHex = supervisorSecret,
                  linuxSharedDirectory = publication.linuxSharedDirectory,
                  linuxEventsFile = publication.linuxEventsFile,
                  linuxSupervisorCommandFile = publication.linuxSupervisorCommandFile,
                  linuxSupervisorStatusFile = publication.linuxSupervisorStatusFile,
                  supervisorBinarySha256 = supervisorBinarySha256,
                ).toByteArray(StandardCharsets.UTF_8),
            ),
          ).also(inserted::add)
        val eventsUri =
          publishNewSource(
            BootstrapSource(
              relativePath = publication.handoffRelativePath,
              fileName = HANDOFF_EVENTS_FILE_NAME,
              mimeType = "application/x-ndjson",
              content = ByteArray(0),
            ),
          ).also(inserted::add)
        val supervisorCommandUri =
          publishNewSource(
            BootstrapSource(
              relativePath = publication.supervisorRelativePath,
              fileName = SUPERVISOR_COMMAND_FILE_NAME,
              mimeType = "application/octet-stream",
              content = ByteArray(0),
            ),
          ).also(inserted::add)
        val supervisorStatusUri =
          publishNewSource(
            BootstrapSource(
              relativePath = publication.supervisorRelativePath,
              fileName = SUPERVISOR_STATUS_FILE_NAME,
              mimeType = "application/octet-stream",
              content = ByteArray(0),
            ),
          ).also(inserted::add)
        val session =
          BootstrapHandoffSession(
            requestId = requestId,
            handoffSecretHex = handoffSecret,
            supervisorSecretHex = supervisorSecret,
            bootstrapUri = checkNotNull(bootstrapUri).toString(),
            environmentUri = environmentUri.toString(),
            eventsUri = eventsUri.toString(),
            supervisorCommandUri = supervisorCommandUri.toString(),
            supervisorStatusUri = supervisorStatusUri.toString(),
            command = publication.command,
            createdAtEpochSeconds = System.currentTimeMillis() / 1_000,
          )
        check(repository.saveActive(session)) { "Could not persist Bootstrap handoff" }
        BootstrapPublishResult.Published(session.bootstrapUri, session.command)
      } catch (_: BootstrapStorageUnavailable) {
        inserted.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        BootstrapPublishResult.Failed(BootstrapDeliveryError.StorageUnavailable)
      } catch (_: Exception) {
        inserted.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        repository.discardActive(requestId)
        BootstrapPublishResult.Failed(BootstrapDeliveryError.WriteFailed)
      }
    }

  private fun publishImmutableSource(source: BootstrapSource): Uri {
    val resolver = appContext.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val selection =
      "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?"
    val existingUri =
      resolver
        .query(
          collection,
          arrayOf(MediaStore.Downloads._ID),
          selection,
          arrayOf(source.relativePath, source.fileName),
          null,
        )?.use { cursor ->
          if (!cursor.moveToFirst()) {
            null
          } else {
            ContentUris.withAppendedId(
              collection,
              cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)),
            )
          }
        }
    if (existingUri == null) return publishNewSource(source)
    val existing = resolver.openInputStream(existingUri)?.use { it.readBytes() }
    check(existing?.contentEquals(source.content) == true) {
      "Immutable Bootstrap source changed: ${source.relativePath}${source.fileName}"
    }
    return existingUri
  }

  private fun publishNewSource(source: BootstrapSource): Uri {
    val resolver = appContext.contentResolver
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, source.fileName)
        put(MediaStore.Downloads.MIME_TYPE, source.mimeType)
        put(MediaStore.Downloads.RELATIVE_PATH, source.relativePath)
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
    val uri =
      resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw BootstrapStorageUnavailable()
    try {
      resolver.openOutputStream(uri, "wt")?.use { output ->
        ByteArrayInputStream(source.content).use { input -> input.copyTo(output) }
      } ?: throw BootstrapStorageUnavailable()
      val updated =
        resolver.update(
          uri,
          ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
          null,
          null,
        )
      check(updated == 1) { "MediaStore did not publish ${source.fileName}" }
      resolver
        .query(
          uri,
          arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.IS_PENDING),
          null,
          null,
          null,
        )?.use { cursor ->
          check(cursor.moveToFirst()) { "Published file cannot be read" }
          val actualName =
            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
          val pending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING))
          check(actualName == source.fileName) {
            "MediaStore renamed ${source.fileName} to $actualName"
          }
          check(pending == 0) { "MediaStore left ${source.fileName} pending" }
        } ?: error("Published file cannot be queried")
      return uri
    } catch (error: Exception) {
      runCatching { resolver.delete(uri, null, null) }
      throw error
    }
  }

  internal fun bootstrapSources(): List<BootstrapSource> =
    listOf(
      BootstrapSource("", BOOTSTRAP_FILE_NAME, "application/x-sh", readAsset(BOOTSTRAP_ASSET)),
      BootstrapSource(
        "",
        SUPERVISOR_INSTALLER_FILE_NAME,
        "application/x-sh",
        readAsset(SUPERVISOR_INSTALLER_ASSET),
      ),
      BootstrapSource(
        "",
        SUPERVISOR_BINARY_FILE_NAME,
        "application/octet-stream",
        readAsset(SUPERVISOR_BINARY_ASSET),
      ),
      BootstrapSource("", GATEWAY_FILE_NAME, "application/x-sh", readAsset(GATEWAY_ASSET)),
      BootstrapSource("", GATEWAY_CONFIG_CACHE_FILE_NAME, "application/x-sh", readAsset(GATEWAY_CONFIG_CACHE_ASSET)),
      BootstrapSource(
        "",
        PRODUCT_PLUGIN_INSTALLER_FILE_NAME,
        "text/javascript",
        readAsset(PRODUCT_PLUGIN_INSTALLER_ASSET),
      ),
      BootstrapSource("", PLUGIN_ALLOWLIST_FILE_NAME, "text/javascript", readAsset(PLUGIN_ALLOWLIST_ASSET)),
      BootstrapSource("", PLUGIN_PATHS_FILE_NAME, "text/javascript", readAsset(PLUGIN_PATHS_ASSET)),
      BootstrapSource(
        "",
        SUPERVISION_POLICY_FILE_NAME,
        "application/x-sh",
        readAsset(SUPERVISION_POLICY_ASSET),
      ),
      BootstrapSource(
        ANDROID_USE_DIRECTORY,
        ANDROID_USE_ENTRY_FILE_NAME,
        "text/javascript",
        readAsset(ANDROID_USE_ENTRY_ASSET),
      ),
      BootstrapSource(
        ANDROID_USE_DIRECTORY,
        ANDROID_USE_RUNTIME_FILE_NAME,
        "text/javascript",
        readAsset(ANDROID_USE_RUNTIME_ASSET),
      ),
      BootstrapSource(
        ANDROID_USE_DIRECTORY,
        ANDROID_USE_AUTHORIZATION_FILE_NAME,
        "text/javascript",
        readAsset(ANDROID_USE_AUTHORIZATION_ASSET),
      ),
      BootstrapSource(
        ANDROID_USE_DIRECTORY,
        ANDROID_USE_MANIFEST_FILE_NAME,
        "application/json",
        readAsset(ANDROID_USE_MANIFEST_ASSET),
      ),
      BootstrapSource(
        ANDROID_USE_DIRECTORY,
        ANDROID_USE_PACKAGE_FILE_NAME,
        "application/json",
        readAsset(ANDROID_USE_PACKAGE_ASSET),
      ),
      BootstrapSource(
        ANDROID_USE_SKILL_DIRECTORY,
        ANDROID_USE_SKILL_FILE_NAME,
        "text/markdown",
        readAsset(ANDROID_USE_SKILL_ASSET),
      ),
      *VSCREEN_FOUNDATION_ASSETS
        .map { (fileName, mediaType) ->
          BootstrapSource(
            VSCREEN_FOUNDATION_DIRECTORY,
            fileName,
            mediaType,
            readAsset("claw-in-one/vscreen-foundation/$fileName"),
          )
        }.toTypedArray(),
      *ANDROID_DEVELOPER_BRIDGE_ASSETS
        .map { (relativePath, fileName, mediaType) ->
          BootstrapSource(
            ANDROID_DEVELOPER_BRIDGE_DIRECTORY + relativePath,
            fileName,
            mediaType,
            readAsset(
              "claw-in-one/${ANDROID_DEVELOPER_BRIDGE_DIRECTORY}${relativePath}$fileName",
            ),
          )
        }.toTypedArray(),
      *WEB_DEVELOPMENT_ASSETS
        .map { (relativePath, fileName, mediaType) ->
          BootstrapSource(
            WEB_DEVELOPMENT_DIRECTORY + relativePath,
            fileName,
            mediaType,
            readAsset("claw-in-one/${WEB_DEVELOPMENT_DIRECTORY}${relativePath}$fileName"),
          )
        }.toTypedArray(),
      *PROJECT_WORKSPACES_ASSETS
        .map { (fileName, mediaType) ->
          BootstrapSource(
            PROJECT_WORKSPACES_DIRECTORY,
            fileName,
            mediaType,
            readAsset("claw-in-one/project-workspaces/$fileName"),
          )
        }.toTypedArray(),
      BootstrapSource(
        ANDROID_KOTLIN_PROFILE_DIRECTORY,
        "release.json",
        "application/json",
        readAsset("claw-in-one/android-kotlin-compose-v1/release.json"),
      ),
      BootstrapSource(
        ANDROID_KOTLIN_PROFILE_DIRECTORY,
        "new-project.mjs",
        "text/javascript",
        readAsset("claw-in-one/android-kotlin-compose-v1/new-project.mjs"),
      ),
      BootstrapSource(
        "${ANDROID_KOTLIN_PROFILE_DIRECTORY}template/",
        "settings.gradle.kts",
        "application/octet-stream",
        readAsset("claw-in-one/android-kotlin-compose-v1/template/settings.gradle.kts"),
      ),
      BootstrapSource(
        "${ANDROID_KOTLIN_PROFILE_DIRECTORY}template/",
        "build.gradle.kts",
        "application/octet-stream",
        readAsset("claw-in-one/android-kotlin-compose-v1/template/build.gradle.kts"),
      ),
      BootstrapSource(
        "${ANDROID_KOTLIN_PROFILE_DIRECTORY}template/",
        "gradle.properties",
        "application/octet-stream",
        readAsset("claw-in-one/android-kotlin-compose-v1/template/gradle.properties"),
      ),
      BootstrapSource(
        "${ANDROID_KOTLIN_PROFILE_DIRECTORY}template/app/",
        "build.gradle.kts",
        "application/octet-stream",
        readAsset("claw-in-one/android-kotlin-compose-v1/template/app/build.gradle.kts"),
      ),
      BootstrapSource(
        "${ANDROID_KOTLIN_PROFILE_DIRECTORY}template/app/src/main/",
        "AndroidManifest.xml",
        "text/xml",
        readAsset("claw-in-one/android-kotlin-compose-v1/template/app/src/main/AndroidManifest.xml"),
      ),
      BootstrapSource(
        "${ANDROID_KOTLIN_PROFILE_DIRECTORY}template/app/src/main/res/values/",
        "styles.xml",
        "text/xml",
        readAsset("claw-in-one/android-kotlin-compose-v1/template/app/src/main/res/values/styles.xml"),
      ),
      BootstrapSource(
        "${ANDROID_KOTLIN_PROFILE_DIRECTORY}template/app/src/main/java/starter/",
        "MainActivity.kt",
        "application/octet-stream",
        readAsset(
          "claw-in-one/android-kotlin-compose-v1/template/app/src/main/java/starter/MainActivity.kt",
        ),
      ),
      BootstrapSource(
        ANDROID_NATIVE_PROFILE_DIRECTORY,
        "release.json",
        "application/json",
        readAsset("claw-in-one/android-native-vulkan-v1/release.json"),
      ),
      BootstrapSource(
        ANDROID_NATIVE_PROFILE_DIRECTORY,
        "new-project.mjs",
        "text/javascript",
        readAsset("claw-in-one/android-native-vulkan-v1/new-project.mjs"),
      ),
      BootstrapSource(
        "${ANDROID_NATIVE_PROFILE_DIRECTORY}template/",
        "settings.gradle.kts",
        "application/octet-stream",
        readAsset("claw-in-one/android-native-vulkan-v1/template/settings.gradle.kts"),
      ),
      BootstrapSource(
        "${ANDROID_NATIVE_PROFILE_DIRECTORY}template/",
        "build.gradle.kts",
        "application/octet-stream",
        readAsset("claw-in-one/android-native-vulkan-v1/template/build.gradle.kts"),
      ),
      BootstrapSource(
        "${ANDROID_NATIVE_PROFILE_DIRECTORY}template/",
        "gradle.properties",
        "application/octet-stream",
        readAsset("claw-in-one/android-native-vulkan-v1/template/gradle.properties"),
      ),
      BootstrapSource(
        "${ANDROID_NATIVE_PROFILE_DIRECTORY}template/app/",
        "build.gradle.kts",
        "application/octet-stream",
        readAsset("claw-in-one/android-native-vulkan-v1/template/app/build.gradle.kts"),
      ),
      BootstrapSource(
        "${ANDROID_NATIVE_PROFILE_DIRECTORY}template/app/src/main/",
        "AndroidManifest.xml",
        "text/xml",
        readAsset("claw-in-one/android-native-vulkan-v1/template/app/src/main/AndroidManifest.xml"),
      ),
      BootstrapSource(
        "${ANDROID_NATIVE_PROFILE_DIRECTORY}template/app/src/main/cpp/",
        "CMakeLists.txt",
        "application/octet-stream",
        readAsset("claw-in-one/android-native-vulkan-v1/template/app/src/main/cpp/CMakeLists.txt"),
      ),
      BootstrapSource(
        "${ANDROID_NATIVE_PROFILE_DIRECTORY}template/app/src/main/cpp/",
        "main.c",
        "text/x-c",
        readAsset("claw-in-one/android-native-vulkan-v1/template/app/src/main/cpp/main.c"),
      ),
      BootstrapSource(
        FLUTTER_PROFILE_DIRECTORY,
        "release.json",
        "application/json",
        readAsset("claw-in-one/flutter-android-v1/release.json"),
      ),
      BootstrapSource(
        FLUTTER_PROFILE_DIRECTORY,
        "new-project.mjs",
        "text/javascript",
        readAsset("claw-in-one/flutter-android-v1/new-project.mjs"),
      ),
      BootstrapSource(
        GODOT_PROFILE_DIRECTORY,
        "release.json",
        "application/json",
        readAsset("claw-in-one/godot-android-v1/release.json"),
      ),
      BootstrapSource(
        GODOT_PROFILE_DIRECTORY,
        "new-project.mjs",
        "text/javascript",
        readAsset("claw-in-one/godot-android-v1/new-project.mjs"),
      ),
      BootstrapSource(
        "${GODOT_PROFILE_DIRECTORY}template/",
        "project.godot",
        "application/octet-stream",
        readAsset("claw-in-one/godot-android-v1/template/project.godot"),
      ),
      BootstrapSource(
        "${GODOT_PROFILE_DIRECTORY}template/",
        "export_presets.cfg",
        "application/octet-stream",
        readAsset("claw-in-one/godot-android-v1/template/export_presets.cfg"),
      ),
      BootstrapSource(
        "${GODOT_PROFILE_DIRECTORY}template/",
        "main.gd",
        "application/octet-stream",
        readAsset("claw-in-one/godot-android-v1/template/main.gd"),
      ),
      BootstrapSource(
        "${GODOT_PROFILE_DIRECTORY}template/",
        "main.tscn",
        "application/octet-stream",
        readAsset("claw-in-one/godot-android-v1/template/main.tscn"),
      ),
      BootstrapSource(
        "${GODOT_PROFILE_DIRECTORY}template/",
        "icon.svg",
        "image/svg+xml",
        readAsset("claw-in-one/godot-android-v1/template/icon.svg"),
      ),
      *REACT_NATIVE_PROFILE_ASSETS
        .map { (relativePath, fileName, mediaType) ->
          BootstrapSource(
            REACT_NATIVE_PROFILE_DIRECTORY + relativePath,
            fileName,
            mediaType,
            readAsset(
              "claw-in-one/${REACT_NATIVE_PROFILE_DIRECTORY}${relativePath}$fileName",
            ),
          )
        }.toTypedArray(),
      *WEB_PROFILE_ASSETS
        .map { (relativePath, fileName, mediaType) ->
          BootstrapSource(
            WEB_PROFILE_DIRECTORY + relativePath,
            fileName,
            mediaType,
            readAsset("claw-in-one/${WEB_PROFILE_DIRECTORY}${relativePath}$fileName"),
          )
        }.toTypedArray(),
    )

  private fun readAsset(path: String): ByteArray = appContext.assets.open(path).use { it.readBytes() }
}

internal data class BootstrapSource(
  val relativePath: String,
  val fileName: String,
  val mimeType: String,
  val content: ByteArray,
)

private class BootstrapStorageUnavailable : Exception()

internal fun supervisorEnvironment(
  requestId: String,
  handoffSecretHex: String,
  supervisorSecretHex: String,
  linuxSharedDirectory: String,
  linuxEventsFile: String,
  linuxSupervisorCommandFile: String,
  linuxSupervisorStatusFile: String,
  supervisorBinarySha256: String,
  release: OpenClawReleaseManifest = PINNED_OPENCLAW_RELEASE,
  environment: ExecutionEnvironmentReleaseManifest = PINNED_EXECUTION_ENVIRONMENT,
  capabilities: CapabilityPackReleaseManifest = PINNED_CAPABILITY_PACKS,
): String {
  require(requestId.matches(Regex("[0-9a-f]{32}")))
  require(handoffSecretHex.matches(Regex("[0-9a-f]{64}")))
  require(supervisorSecretHex.matches(Regex("[0-9a-f]{64}")))
  require(handoffSecretHex != supervisorSecretHex)
  require(supervisorBinarySha256.matches(Regex("[0-9a-f]{64}")))
  require(
    linuxSupervisorCommandFile ==
      "/mnt/shared/Download/ClawInOne/supervisor-$requestId/$SUPERVISOR_COMMAND_FILE_NAME",
  )
  require(
    linuxSupervisorStatusFile ==
      "/mnt/shared/Download/ClawInOne/supervisor-$requestId/$SUPERVISOR_STATUS_FILE_NAME",
  )
  return buildString {
    appendShellAssignment("CLAW_IN_ONE_SHARED_DIR", linuxSharedDirectory)
    appendShellAssignment("CLAW_IN_ONE_HANDOFF_PROTOCOL_VERSION", BOOTSTRAP_HANDOFF_PROTOCOL_VERSION.toString())
    appendShellAssignment("CLAW_IN_ONE_HANDOFF_REQUEST_ID", requestId)
    appendShellAssignment("CLAW_IN_ONE_HANDOFF_SECRET", handoffSecretHex)
    appendShellAssignment("CLAW_IN_ONE_HANDOFF_EVENTS_FILE", linuxEventsFile)
    appendShellAssignment("CLAW_IN_ONE_SUPERVISOR_PROTOCOL_VERSION", SUPERVISOR_CONTROL_PROTOCOL_VERSION.toString())
    appendShellAssignment("CLAW_IN_ONE_SUPERVISOR_ID", requestId)
    appendShellAssignment("CLAW_IN_ONE_SUPERVISOR_SECRET", supervisorSecretHex)
    appendShellAssignment("CLAW_IN_ONE_SUPERVISOR_COMMAND_FILE", linuxSupervisorCommandFile)
    appendShellAssignment("CLAW_IN_ONE_SUPERVISOR_STATUS_FILE", linuxSupervisorStatusFile)
    appendShellAssignment("CLAW_IN_ONE_SUPERVISOR_BINARY_SHA256", supervisorBinarySha256)
    appendShellAssignment("CLAW_IN_ONE_OPENCLAW_VERSION", release.version)
    appendShellAssignment("CLAW_IN_ONE_NODE_VERSION", release.nodeVersion)
    appendShellAssignment("CLAW_IN_ONE_NODE_URL", release.nodeArchiveUrl)
    appendShellAssignment("CLAW_IN_ONE_NODE_SIZE_BYTES", release.nodeArchiveSizeBytes.toString())
    appendShellAssignment("CLAW_IN_ONE_NODE_SHA256", release.nodeArchiveSha256)
    appendShellAssignment("CLAW_IN_ONE_OPENCLAW_URL", release.packageUrl)
    appendShellAssignment("CLAW_IN_ONE_OPENCLAW_SIZE_BYTES", release.packageSizeBytes.toString())
    appendShellAssignment("CLAW_IN_ONE_OPENCLAW_INTEGRITY", release.packageIntegrity)
    appendShellAssignment("CLAW_IN_ONE_OPENCLAW_SIGNATURE", release.packageSignature)
    appendShellAssignment("CLAW_IN_ONE_NPM_KEY_ID", release.registryKeyId)
    appendShellAssignment("CLAW_IN_ONE_NPM_PUBLIC_KEY", release.registryPublicKey)
    appendShellAssignment("CLAW_IN_ONE_DEBIAN_SERIES", environment.debianSeries)
    appendShellAssignment(
      "CLAW_IN_ONE_PACKAGE_SET_GENERATION",
      environment.packageSetGeneration.toString(),
    )
    appendShellAssignment("CLAW_IN_ONE_GENERAL_NODE_VERSION", environment.generalNodeVersion)
    appendShellAssignment("CLAW_IN_ONE_GENERAL_NODE_NPM_VERSION", environment.generalNodeNpmVersion)
    appendShellAssignment("CLAW_IN_ONE_GENERAL_NODE_URL", environment.generalNodeUrl)
    appendShellAssignment(
      "CLAW_IN_ONE_GENERAL_NODE_SIZE_BYTES",
      environment.generalNodeSizeBytes.toString(),
    )
    appendShellAssignment("CLAW_IN_ONE_GENERAL_NODE_SHA256", environment.generalNodeSha256)
    appendShellAssignment("CLAW_IN_ONE_SCRCPY_SERVER_VERSION", environment.scrcpyServerVersion)
    appendShellAssignment("CLAW_IN_ONE_SCRCPY_SERVER_URL", environment.scrcpyServerUrl)
    appendShellAssignment(
      "CLAW_IN_ONE_SCRCPY_SERVER_SIZE_BYTES",
      environment.scrcpyServerSizeBytes.toString(),
    )
    appendShellAssignment("CLAW_IN_ONE_SCRCPY_SERVER_SHA256", environment.scrcpyServerSha256)
    appendShellAssignment(
      "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_VERSION",
      capabilities.androidCommandToolsVersion,
    )
    appendShellAssignment("CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_URL", capabilities.androidCommandToolsUrl)
    appendShellAssignment(
      "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_SIZE_BYTES",
      capabilities.androidCommandToolsSizeBytes.toString(),
    )
    appendShellAssignment(
      "CLAW_IN_ONE_ANDROID_COMMAND_TOOLS_SHA256",
      capabilities.androidCommandToolsSha256,
    )
    appendShellAssignment("CLAW_IN_ONE_ANDROID_SDK_CHANNEL", capabilities.androidSdkChannel.toString())
    appendShellAssignment("CLAW_IN_ONE_ANDROID_PLATFORM", capabilities.androidPlatform)
    appendShellAssignment("CLAW_IN_ONE_ANDROID_BUILD_TOOLS", capabilities.androidBuildTools)
    appendShellAssignment("CLAW_IN_ONE_ANDROID_GRADLE_VERSION", capabilities.androidGradleVersion)
    appendShellAssignment("CLAW_IN_ONE_ANDROID_GRADLE_URL", capabilities.androidGradleUrl)
    appendShellAssignment(
      "CLAW_IN_ONE_ANDROID_GRADLE_SIZE_BYTES",
      capabilities.androidGradleSizeBytes.toString(),
    )
    appendShellAssignment("CLAW_IN_ONE_ANDROID_GRADLE_SHA256", capabilities.androidGradleSha256)
    appendShellAssignment("CLAW_IN_ONE_ANDROID_NATIVE_NDK", capabilities.androidNativeNdk)
    appendShellAssignment("CLAW_IN_ONE_ANDROID_NATIVE_CMAKE", capabilities.androidNativeCmake)
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_VERSION", capabilities.flutterVersion)
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_DART_VERSION", capabilities.flutterDartVersion)
    appendShellAssignment(
      "CLAW_IN_ONE_FLUTTER_FRAMEWORK_REVISION",
      capabilities.flutterFrameworkRevision,
    )
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_ENGINE_REVISION", capabilities.flutterEngineRevision)
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_URL", capabilities.flutterUrl)
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_SIZE_BYTES", capabilities.flutterSizeBytes.toString())
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_SHA256", capabilities.flutterSha256)
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_ANDROID_PLATFORM", capabilities.flutterAndroidPlatform)
    appendShellAssignment("CLAW_IN_ONE_FLUTTER_ANDROID_NDK", capabilities.flutterAndroidNdk)
    appendShellAssignment("CLAW_IN_ONE_GODOT_VERSION", capabilities.godotVersion)
    appendShellAssignment("CLAW_IN_ONE_GODOT_BUILD", capabilities.godotBuild)
    appendShellAssignment("CLAW_IN_ONE_GODOT_ENGINE_URL", capabilities.godotEngineUrl)
    appendShellAssignment(
      "CLAW_IN_ONE_GODOT_ENGINE_SIZE_BYTES",
      capabilities.godotEngineSizeBytes.toString(),
    )
    appendShellAssignment("CLAW_IN_ONE_GODOT_ENGINE_SHA256", capabilities.godotEngineSha256)
    appendShellAssignment(
      "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_URL",
      capabilities.godotExportTemplatesUrl,
    )
    appendShellAssignment(
      "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_SIZE_BYTES",
      capabilities.godotExportTemplatesSizeBytes.toString(),
    )
    appendShellAssignment(
      "CLAW_IN_ONE_GODOT_EXPORT_TEMPLATES_SHA256",
      capabilities.godotExportTemplatesSha256,
    )
    appendShellAssignment(
      "CLAW_IN_ONE_GODOT_ANDROID_DEBUG_TEMPLATE_SHA256",
      capabilities.godotAndroidDebugTemplateSha256,
    )
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_VERSION", capabilities.reactNativeVersion)
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_REACT_VERSION", capabilities.reactNativeReactVersion)
    appendShellAssignment(
      "CLAW_IN_ONE_REACT_NATIVE_COMMUNITY_CLI_VERSION",
      capabilities.reactNativeCommunityCliVersion,
    )
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_GRADLE_VERSION", capabilities.reactNativeGradleVersion)
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_GRADLE_URL", capabilities.reactNativeGradleUrl)
    appendShellAssignment(
      "CLAW_IN_ONE_REACT_NATIVE_GRADLE_SIZE_BYTES",
      capabilities.reactNativeGradleSizeBytes.toString(),
    )
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_GRADLE_SHA256", capabilities.reactNativeGradleSha256)
    appendShellAssignment(
      "CLAW_IN_ONE_REACT_NATIVE_ANDROID_GRADLE_PLUGIN",
      capabilities.reactNativeAndroidGradlePlugin,
    )
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_ANDROID_PLATFORM", capabilities.reactNativeAndroidPlatform)
    appendShellAssignment(
      "CLAW_IN_ONE_REACT_NATIVE_ANDROID_BUILD_TOOLS",
      capabilities.reactNativeAndroidBuildTools,
    )
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_ANDROID_NDK", capabilities.reactNativeAndroidNdk)
    appendShellAssignment("CLAW_IN_ONE_REACT_NATIVE_ANDROID_CMAKE", capabilities.reactNativeAndroidCmake)
    appendShellAssignment(
      "CLAW_IN_ONE_REACT_NATIVE_HERMES_COMPILER_VERSION",
      capabilities.reactNativeHermesCompilerVersion,
    )
    appendShellAssignment(
      "CLAW_IN_ONE_REACT_NATIVE_HERMES_COMPILER_SHA256",
      capabilities.reactNativeHermesCompilerSha256,
    )
    appendShellAssignment(
      "CLAW_IN_ONE_REACT_NATIVE_PACKAGE_LOCK_SHA256",
      capabilities.reactNativePackageLockSha256,
    )
    appendShellAssignment("CLAW_IN_ONE_WEB_REACT_VERSION", capabilities.webReactVersion)
    appendShellAssignment("CLAW_IN_ONE_WEB_REACT_DOM_VERSION", capabilities.webReactDomVersion)
    appendShellAssignment("CLAW_IN_ONE_WEB_VITE_VERSION", capabilities.webViteVersion)
    appendShellAssignment("CLAW_IN_ONE_WEB_TYPESCRIPT_VERSION", capabilities.webTypescriptVersion)
    appendShellAssignment("CLAW_IN_ONE_WEB_PACKAGE_LOCK_SHA256", capabilities.webPackageLockSha256)
  }
}

private fun StringBuilder.appendShellAssignment(
  name: String,
  value: String,
) {
  append(name)
  append("='")
  append(value.replace("'", "'\"'\"'"))
  append("'\n")
}

/** Owns publication and replacement of the one-time Terminal handoff. */
internal class BootstrapDeliveryController(
  private val publisher: BootstrapPublisher,
  private val copyCommand: (String) -> Unit,
  private val discardActive: suspend () -> Unit = {},
) {
  constructor(
    context: Context,
    repository: BootstrapHandoffRepository,
  ) : this(
    publisher = MediaStoreBootstrapPublisher(context, repository),
    copyCommand = { command ->
      val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
      clipboard.setPrimaryClip(ClipData.newPlainText("ClawInOne bootstrap", command))
    },
    discardActive = { withContext(Dispatchers.IO) { repository.discardActive() } },
  )

  private val prepareMutex = Mutex()
  private val _state = MutableStateFlow<BootstrapDeliveryState>(BootstrapDeliveryState.NotPrepared)
  val state: StateFlow<BootstrapDeliveryState> = _state.asStateFlow()

  suspend fun prepare() {
    prepareMutex.withLock {
      if (_state.value is BootstrapDeliveryState.Ready) return
      publishLocked()
    }
  }

  suspend fun resetAndPrepare() {
    prepareMutex.withLock {
      _state.value = BootstrapDeliveryState.Preparing
      discardActive()
      publishLocked()
    }
  }

  private suspend fun publishLocked() {
    _state.value = BootstrapDeliveryState.Preparing
    _state.value =
      when (val result = publisher.publish()) {
        is BootstrapPublishResult.Published -> {
          BootstrapDeliveryState.Ready(uri = result.uri, command = result.command)
        }
        is BootstrapPublishResult.Failed -> BootstrapDeliveryState.Failed(result.error)
      }
  }

  fun copyReadyCommand(): Boolean {
    val ready = _state.value as? BootstrapDeliveryState.Ready ?: return false
    return runCatching { copyCommand(ready.command) }.isSuccess
  }
}
